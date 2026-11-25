const json = (data, status = 200, headers = {}) => new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff", ...headers } });
const fail = (message, status = 400) => json({ error: message }, status);
const now = () => Math.floor(Date.now() / 1000);
// R2's free storage allowance is account-wide. Keep 2 GB reserved for any other
// buckets or temporary usage, and use decimal bytes to match Cloudflare's GB pricing.
const R2_FREE_STORAGE_BYTES = 10_000_000_000;
const R2_ACCOUNT_RESERVE_BYTES = 2_000_000_000;
const MAX_APK_STORAGE_BYTES = R2_FREE_STORAGE_BYTES - R2_ACCOUNT_RESERVE_BYTES;
const MAX_APK_BYTES = 100 * 1024 * 1024;
const enc = new TextEncoder();
const bytesToHex = b => [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, "0")).join("");
const hexToBytes = h => Uint8Array.from(h.match(/../g) || [], x => parseInt(x, 16));
const random = (n = 32) => { const b = new Uint8Array(n); crypto.getRandomValues(b); return bytesToHex(b); };
const digest = async text => bytesToHex(await crypto.subtle.digest("SHA-256", enc.encode(text)));
const hashPassword = async password => {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  // Cloudflare Workers WebCrypto caps PBKDF2 at 100,000 iterations.
  const rounds = 100000;
  const bits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt, iterations: rounds, hash: "SHA-256" }, key, 256);
  return `pbkdf2-sha256$${rounds}$${bytesToHex(salt)}$${bytesToHex(bits)}`;
};
const verifyPassword = async (password, stored) => {
  try { const [, rounds, salt, expected] = stored.split("$"); const key = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]); const got = new Uint8Array(await crypto.subtle.deriveBits({ name: "PBKDF2", salt: hexToBytes(salt), iterations: Number(rounds), hash: "SHA-256" }, key, 256)); const want = hexToBytes(expected); return got.length === want.length && got.every((v, i) => v === want[i]); } catch { return false; }
};
async function bodyJson(request) { try { const text = await request.text(); if (text.length > 65536) throw new Error(); return JSON.parse(text); } catch { throw Object.assign(new Error("Invalid JSON request or body exceeds 64 KiB"), { status: 400 }); } }
function cookieValue(request, name) { return (request.headers.get("cookie") || "").split(";").map(v => v.trim()).map(v => v.split("=")).find(([k]) => k === name)?.slice(1).join("="); }
function originOk(request, env) { return request.headers.get("origin") === new URL(request.url).origin && request.headers.get("sec-fetch-site") !== "cross-site"; }
function sessionCookie(value, maxAge) { return `__Host-panel_session=${value}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${maxAge}`; }
async function rateLimitLogin(request, env) {
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const keys = [await digest(`login:${ip}`), "login:global"];
  for (const key of keys) {
    const row = await env.DB.prepare("INSERT INTO login_limits(bucket,window_start,attempts) VALUES(?,?,1) ON CONFLICT(bucket) DO UPDATE SET attempts=CASE WHEN window_start<=? THEN 1 ELSE attempts+1 END,window_start=CASE WHEN window_start<=? THEN excluded.window_start ELSE window_start END RETURNING attempts").bind(key, now(), now() - 300, now() - 300).first();
    if (row.attempts > (key === "login:global" ? 60 : 10)) throw Object.assign(new Error("Too many requests; try again later"), { status: 429 });
  }
}
async function auth(request, env, write = false) {
  if (!env.DB) throw Object.assign(new Error("D1 binding DB is not configured"), { status: 503 });
  const token = cookieValue(request, "__Host-panel_session");
  if (!token || !/^[a-f0-9]{64}$/i.test(token)) throw Object.assign(new Error("Sign in to continue"), { status: 401 });
  const row = await env.DB.prepare("SELECT s.token_hash,s.csrf,a.username FROM sessions s JOIN admins a ON a.id=s.admin_id WHERE s.token_hash=? AND s.expires_at>?").bind(await digest(token), now()).first();
  if (!row) throw Object.assign(new Error("Sign in to continue"), { status: 401 });
  if (write && (!originOk(request, env) || request.headers.get("x-csrf-token") !== row.csrf)) throw Object.assign(new Error("Origin or CSRF token is invalid"), { status: 403 });
  return { ...row, token };
}
function cleanOrigin(raw) {
  let u; try { u = new URL(raw); } catch { throw Object.assign(new Error("Use an HTTPS origin without credentials, path, query or fragment"), { status: 400 }); }
  if (u.protocol !== "https:" || !u.hostname || u.username || u.password || (u.pathname !== "/" && u.pathname !== "") || u.search || u.hash || /[\\\x00-\x20]/.test(raw)) throw Object.assign(new Error("Use an HTTPS origin without credentials, path, query or fragment"), { status: 400 });
  return u.origin;
}
function validateConfig(c) {
  if (!c || !Number.isSafeInteger(c.revision) || !Array.isArray(c.servers) || c.servers.length < 1 || c.servers.length > 10 || typeof c.defaultServerId !== "string") throw Object.assign(new Error("Configure between 1 and 10 servers"), { status: 400 });
  const ids = new Set(), urls = new Set();
  for (const s of c.servers) {
    if (!s || typeof s.id !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(s.id) || ids.has(s.id)) throw Object.assign(new Error("Server IDs must be unique and contain 1–64 letters, numbers, underscores or hyphens"), { status: 400 });
    if (typeof s.name !== "string" || !s.name.trim() || s.name.trim().length > 80 || /[\x00-\x1f\x7f]/.test(s.name)) throw Object.assign(new Error("Server names must contain 1–80 characters"), { status: 400 });
    s.name = s.name.trim(); s.url = cleanOrigin(s.url);
    if (urls.has(s.url)) throw Object.assign(new Error("Server URLs must be unique after normalization"), { status: 400 }); ids.add(s.id); urls.add(s.url);
  }
  if (!ids.has(c.defaultServerId)) throw Object.assign(new Error("The default server must exist in the list"), { status: 400 });
  return c;
}
async function getConfig(env) { const row = await env.DB.prepare("SELECT revision,default_server_id,servers_json FROM config WHERE id=1").first(); if (!row) throw new Error("D1 schema is not initialized"); return { revision: row.revision, servers: JSON.parse(row.servers_json), defaultServerId: row.default_server_id }; }
function releaseFromRow(r, origin) { return { versionCode: r.version_code, versionName: r.version_name, sha256: r.sha256, notes: r.notes, sourceUrl: r.source_url, packageName: r.package_name, sizeBytes: r.size_bytes, status: r.status, createdAt: r.created_at, publishedAt: r.published_at }; }
function validateRelease(input) {
  if (!input || !Number.isInteger(input.versionCode) || input.versionCode < 1 || input.versionCode > 2100000000 || typeof input.versionName !== "string" || !input.versionName.trim() || input.versionName.length > 100 || /[\x00-\x1f\x7f]/.test(input.versionName) || typeof input.notes !== "string" || input.notes.length > 12000) throw Object.assign(new Error("Invalid version metadata or notes"), { status: 400 });
  let u; try { u = new URL(input.sourceUrl); } catch { throw Object.assign(new Error("Provide an HTTPS URL for GPL corresponding source"), { status: 400 }); }
  if (input.sourceUrl.length > 2048 || u.protocol !== "https:" || !u.hostname || u.username || u.password || /[\x00-\x1f\x7f]/.test(input.sourceUrl)) throw Object.assign(new Error("Corresponding source must be an HTTPS URL without credentials"), { status: 400 });
}
// Bounded APK ZIP and Android binary-XML validation. This checks structure and
// metadata only; it does not cryptographically verify the APK signer.
function apkError(message = "Invalid or unsupported APK / binary Android manifest") { return Object.assign(new Error(message), { status: 400 }); }
function u16(v, data, at) { if (at < 0 || at + 2 > data.length) throw apkError(); return v.getUint16(at, true); }
function u32(v, data, at) { if (at < 0 || at + 4 > data.length) throw apkError(); return v.getUint32(at, true); }
function parseManifest(data) {
  const v = new DataView(data.buffer, data.byteOffset, data.byteLength);
  if (data.length > 2 * 1024 * 1024 || u16(v, data, 0) !== 3 || u16(v, data, 2) !== 8 || u32(v, data, 4) !== data.length) throw apkError();
  let pos = 8, pool = null;
  const length8 = (at) => { const a=data[at]; if(a===undefined)throw apkError(); return a&128 ? (((a&127)<<8)|(data[at+1]??(()=>{throw apkError()})())) : a; };
  while (pos < data.length) {
    const kind=u16(v,data,pos), header=u16(v,data,pos+2), size=u32(v,data,pos+4), end=pos+size;
    if(header<8||size<header||end>data.length)throw apkError();
    if(kind===1){
      if(pool)throw apkError(); const count=u32(v,data,pos+8), flags=u32(v,data,pos+16), stringsStart=u32(v,data,pos+20);
      if(header<28||count>65536||header+count*4>size||stringsStart<header+count*4||stringsStart>size)throw apkError();
      pool=[];
      for(let i=0;i<count;i++){let at=pos+stringsStart+u32(v,data,pos+header+i*4);let text;
        if(flags&0x100){length8(at);const len=length8(at+1);at+=2;if(at+len>=end||data[at+len]!==0)throw apkError();text=new TextDecoder("utf-8",{fatal:true}).decode(data.subarray(at,at+len));}
        else{let len=u16(v,data,at);at+=2;if(len&0x8000){len=((len&0x7fff)<<16)|u16(v,data,at);at+=2;}if(len>1048576||at+len*2+2>end||u16(v,data,at+len*2)!==0)throw apkError();let chars="";for(let j=0;j<len;j++)chars+=String.fromCharCode(u16(v,data,at+j*2));text=chars;}
        pool.push(text);
      }
    }
    if(kind===0x0102){
      if(header!==16||!pool)throw apkError();const str=i=>{if(i>=pool.length)throw apkError();return pool[i];};
      if(str(u32(v,data,pos+20))!=="manifest")throw apkError();const attr=pos+16+u16(v,data,pos+24),stride=u16(v,data,pos+26),count=u16(v,data,pos+28);
      if(attr<pos+36||stride<20||count>1024||attr+stride*count>end)throw apkError();let pkg,code,name;
      for(let i=0;i<count;i++){const a=attr+i*stride,ns=u32(v,data,a),key=str(u32(v,data,a+4)),raw=u32(v,data,a+8),type=data[a+15],value=u32(v,data,a+16);const literal=()=>raw!==0xffffffff?str(raw):type===3?str(value):(()=>{throw apkError("APK manifest metadata must use literal strings")})();
        if(key==="package"&&ns===0xffffffff){if(pkg!==undefined)throw apkError();pkg=literal();}
        if(ns!==0xffffffff&&str(ns)==="http://schemas.android.com/apk/res/android"){
          if(key==="versionCode"){if(code!==undefined||![0x10,0x11].includes(type))throw apkError();code=value;}
          if(key==="versionCodeMajor"&&value!==0)throw apkError("APK versionCodeMajor is unsupported");
          if(key==="versionName"){if(name!==undefined)throw apkError();name=literal();}
        }
      }
      if(pkg===undefined||code===undefined||name===undefined)throw apkError();return{packageName:pkg,versionCode:code,versionName:name};
    }
    pos=end;
  }
  throw apkError();
}
function inspectApk(bytes, expected) {
  const v=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength),start=Math.max(0,bytes.length-65557);let eocd=-1;
  for(let i=bytes.length-22;i>=start;i--)if(u32(v,bytes,i)===0x06054b50&&i+22+u16(v,bytes,i+20)===bytes.length){eocd=i;break;}
  if(eocd<0)throw apkError("Invalid or unsupported APK ZIP archive");
  const count=u16(v,bytes,eocd+10),dirSize=u32(v,bytes,eocd+12),dirOffset=u32(v,bytes,eocd+16);
  if(u16(v,bytes,eocd+4)!==0||u16(v,bytes,eocd+6)!==0||u16(v,bytes,eocd+8)!==count||!count||count>20000||dirSize>8*1024*1024||dirOffset+dirSize!==eocd)throw apkError("Unsupported APK ZIP archive");
  let p=dirOffset,manifest=null,dex=false,v1Manifest=false,v1Signature=false,signingBlock=false;const names=new Set();
  for(let n=0;n<count;n++){
    if(p+46>bytes.length||u32(v,bytes,p)!==0x02014b50)throw apkError("Invalid APK ZIP directory");
    const method=u16(v,bytes,p+10),size=u32(v,bytes,p+24),nl=u16(v,bytes,p+28),xl=u16(v,bytes,p+30),cl=u16(v,bytes,p+32),off=u32(v,bytes,p+42),endName=p+46+nl;
    if(endName+xl+cl>bytes.length)throw apkError();const name=new TextDecoder("utf-8",{fatal:true}).decode(bytes.subarray(p+46,endName));
    if(!name||name.length>1024||name.startsWith("/")||name.includes("\\")||name.split("/").includes("..")||names.has(name)||off+30>bytes.length)throw apkError("Unsafe or duplicate APK entry");names.add(name);
    if(name==="classes.dex"&&size)dex=true;if(name==="META-INF/MANIFEST.MF")v1Manifest=true;if(/^META-INF\/.+\.(RSA|DSA|EC)$/i.test(name)&&size)v1Signature=true;
    if(name==="AndroidManifest.xml"){
      if(method!==0||size>2*1024*1024||u32(v,bytes,off)!==0x04034b50)throw apkError("APK manifest must be stored and no larger than 2 MiB");
      const dataStart=off+30+u16(v,bytes,off+26)+u16(v,bytes,off+28);if(dataStart+size>bytes.length)throw apkError();manifest=parseManifest(bytes.subarray(dataStart,dataStart+size));
    }
    p=endName+xl+cl;
  }
  if(p!==dirOffset+dirSize||!manifest||!dex)throw apkError("APK must contain AndroidManifest.xml and classes.dex");
  if(dirOffset>=24&&new TextDecoder().decode(bytes.subarray(dirOffset-16,dirOffset))==="APK Sig Block 42")signingBlock=true;
  if(!signingBlock&&!(v1Manifest&&v1Signature))throw apkError("APK must contain signing artifacts; this check does not verify the cryptographic signature");
  if(manifest.packageName!=="org.imdtv.iptvtv.managed")throw apkError("APK package must be org.imdtv.iptvtv.managed");
  if(manifest.versionCode!==expected.versionCode||manifest.versionName!==expected.versionName)throw apkError(`APK manifest reports versionCode ${manifest.versionCode} and versionName ${manifest.versionName}; these must match the form`);
  return manifest.packageName;
}
export async function route(request, env, path) {
  const method = request.method;
  try {
    if (!env.DB) return fail("D1 binding DB is not configured", 503);
    if (path === "api/health" && method === "GET") return new Response("ok", { headers: { "cache-control": "no-store" } });
    if (path === "api/admin/bootstrap" && method === "POST") {
      if (!env.BOOTSTRAP_SECRET) return fail("Bootstrap is disabled; configure BOOTSTRAP_SECRET temporarily", 403);
      if (!env.BOOTSTRAP_SECRET || request.headers.get("authorization") !== `Bearer ${env.BOOTSTRAP_SECRET}`) return fail("Forbidden", 403);
      const count = await env.DB.prepare("SELECT COUNT(*) n FROM admins").first(); if (count.n) return fail("An administrator already exists; bootstrap cannot overwrite credentials", 409);
      const b = await bodyJson(request); if (typeof b.username !== "string" || b.username.trim() !== b.username || !b.username || b.username.length > 80 || typeof b.password !== "string" || [...b.password].length < 12 || b.password.length > 1024) return fail("Username or password does not meet requirements");
      await env.DB.prepare("INSERT INTO admins(id,username,password_hash) VALUES(1,?,?)").bind(b.username, await hashPassword(b.password)).run(); return json({ created: true }, 201);
    }
    if (path === "api/admin/login" && method === "POST") {
      if (!originOk(request, env)) return fail("Origin or CSRF token is invalid", 403);
      await rateLimitLogin(request, env);
      const b = await bodyJson(request); if (typeof b.username !== "string" || typeof b.password !== "string" || b.username.length > 80 || b.password.length > 1024) return fail("Invalid username or password", 401);
      const admin = await env.DB.prepare("SELECT id,username,password_hash FROM admins WHERE username=?").bind(b.username).first();
      if (!admin || !(await verifyPassword(b.password, admin.password_hash))) return fail("Invalid username or password", 401);
      const token = random(), csrf = random(), t = now(); await env.DB.prepare("DELETE FROM sessions WHERE expires_at<=?").bind(t).run();
      await env.DB.prepare("INSERT INTO sessions(token_hash,csrf,expires_at,admin_id) VALUES(?,?,?,?)").bind(await digest(token), csrf, t + 28800, admin.id).run();
      return json({ csrfToken: csrf }, 200, { "set-cookie": sessionCookie(token, 28800) });
    }
    if (path === "api/admin/session" && method === "GET") { const s = await auth(request, env); return json({ username: s.username, csrfToken: s.csrf }); }
    if (path.startsWith("api/admin/")) {
      const part = path.slice("api/admin/".length), write = method !== "GET" && method !== "HEAD";
      const s = await auth(request, env, write);
      if (part === "logout" && method === "POST") { await env.DB.prepare("DELETE FROM sessions WHERE token_hash=?").bind(await digest(s.token)).run(); return new Response(null, { status: 204, headers: { "set-cookie": sessionCookie("", 0), "cache-control": "no-store" } }); }
      if (part === "config" && method === "GET") return json(await getConfig(env));
      if (part === "config" && method === "PUT") {
        const c = validateConfig(await bodyJson(request)); const row = await env.DB.prepare("UPDATE config SET revision=revision+1,default_server_id=?,servers_json=? WHERE id=1 AND revision=? RETURNING revision").bind(c.defaultServerId, JSON.stringify(c.servers), c.revision).first();
        if (!row) return fail("Server configuration changed. Reload before saving.", 409); return json({ ...c, revision: row.revision });
      }
      if (part === "messages" && method === "GET") { const { results } = await env.DB.prepare("SELECT id,title,body,active,created_at,expires_at FROM messages ORDER BY created_at DESC,id DESC LIMIT 500").all(); return json({ messages: results.map(m => ({ id:m.id,title:m.title,body:m.body,active:!!m.active,createdAt:m.created_at,expiresAt:m.expires_at })) }); }
      if (part === "messages" && method === "POST") {
        const b = await bodyJson(request), title = typeof b.title === "string" ? b.title.trim() : "", text = typeof b.body === "string" ? b.body.trim() : "", exp = b.expiresAt ?? null;
        if (!title || [...title].length > 120 || !text || [...text].length > 4000) return fail("Title must contain 1–120 characters; body must contain 1–4000 characters");
        if (exp !== null && (!Number.isInteger(exp) || exp <= now() || exp > now() + 366*86400)) return fail("Expiration must be in the future and within one year");
        const count = await env.DB.prepare("SELECT COUNT(*) n FROM messages WHERE active=1 AND (expires_at IS NULL OR expires_at>?)").bind(now()).first(); if (count.n >= 20) return fail("At most 20 messages may be active; deactivate an existing message first",409);
        const id=random(); await env.DB.prepare("INSERT INTO messages(id,title,body,active,created_at,expires_at) VALUES(?,?,?,1,?,?)").bind(id,title,text,now(),exp).run(); return json({id},201);
      }
      const msg = part.match(/^messages\/([a-f0-9]{64})$/);
      if (msg && method === "PUT") { const b=await bodyJson(request),exp=b.expiresAt??null; if(typeof b.active!=="boolean")return fail("Invalid message state"); if(b.active && exp!==null && (!Number.isInteger(exp)||exp<=now()||exp>now()+366*86400))return fail("Expiration must be in the future and within one year"); if(b.active){const c=await env.DB.prepare("SELECT COUNT(*) n FROM messages WHERE active=1 AND (expires_at IS NULL OR expires_at>?) AND id<>?").bind(now(),msg[1]).first();if(c.n>=20)return fail("At most 20 messages may be active; deactivate an existing message first",409);} const r=await env.DB.prepare("UPDATE messages SET active=?,expires_at=? WHERE id=?").bind(b.active?1:0,exp,msg[1]).run();return r.meta.changes?new Response(null,{status:204}):fail("Message not found",404); }
      if (part === "releases" && method === "GET") { const {results}=await env.DB.prepare("SELECT * FROM releases ORDER BY version_code DESC LIMIT 500").all(); const budget=await env.DB.prepare("SELECT used_bytes FROM r2_storage_budget WHERE id=1").first(); return json({releases:results.map(r=>releaseFromRow(r)),storage:{usedBytes:budget?.used_bytes||0,limitBytes:MAX_APK_STORAGE_BYTES}}); }
      if (part === "diagnostics" && method === "GET") {
        const { results } = await env.DB.prepare("SELECT received_at,version_name,device,android,channels_json FROM diagnostics ORDER BY received_at DESC,id DESC LIMIT 2000").all();
        const channels = new Map(), devices = new Map();
        for (const row of results) {
          let list; try { list = JSON.parse(row.channels_json); } catch { continue; }
          for (const ch of list) {
            const agg = channels.get(ch.name) || { name: ch.name, seconds: 0, stalls: 0, underruns: 0, droppedFrames: 0, errors: 0, reports: 0 };
            agg.seconds += ch.seconds; agg.stalls += ch.stalls; agg.underruns += ch.underruns; agg.droppedFrames += ch.droppedFrames; agg.errors += ch.errors; agg.reports += 1;
            channels.set(ch.name, agg);
          }
          const key = `${row.device} (Android ${row.android})`;
          const dev = devices.get(key) || { device: row.device, android: row.android, reports: 0, lastSeenAt: 0, versionName: row.version_name };
          dev.reports += 1; dev.lastSeenAt = Math.max(dev.lastSeenAt, row.received_at); devices.set(key, dev);
        }
        return json({
          reports: results.length, lastReceivedAt: results[0]?.received_at || null,
          channels: [...channels.values()].sort((a, b) => (b.stalls + b.errors) - (a.stalls + a.errors) || b.seconds - a.seconds).slice(0, 500),
          devices: [...devices.values()].sort((a, b) => b.lastSeenAt - a.lastSeenAt).slice(0, 100),
        });
      }
      if (part === "crashes" && method === "GET") {
        const { results } = await env.DB.prepare("SELECT received_at,occurred_at,version_name,device,android,type,fingerprint,thread,stack FROM crashes ORDER BY occurred_at DESC,id DESC LIMIT 2000").all();
        const groups = new Map();
        for (const row of results) {
          const g = groups.get(row.fingerprint) || { fingerprint: row.fingerprint, type: row.type, count: 0, firstAt: row.occurred_at, lastAt: 0, versions: new Set(), devices: new Set(), thread: row.thread, stack: row.stack, android: row.android };
          g.count += 1; g.firstAt = Math.min(g.firstAt, row.occurred_at); g.lastAt = Math.max(g.lastAt, row.occurred_at);
          g.versions.add(row.version_name); g.devices.add(`${row.device} (API ${row.android})`);
          if (row.occurred_at >= g.lastAt) { g.stack = row.stack; g.thread = row.thread; }
          groups.set(row.fingerprint, g);
        }
        return json({
          reports: results.length, lastReceivedAt: results[0]?.received_at || null,
          crashes: [...groups.values()].sort((a, b) => b.lastAt - a.lastAt).slice(0, 200).map(g => ({
            fingerprint: g.fingerprint, type: g.type, count: g.count, firstAt: g.firstAt, lastAt: g.lastAt,
            versions: [...g.versions].slice(0, 20), devices: [...g.devices].slice(0, 20), thread: g.thread, stack: g.stack,
          })),
        });
      }
      if (part === "releases" && method === "POST") return await upload(request,env);
      const action=part.match(/^releases\/(\d+)\/(publish|withdraw)$/);
      if(action&&method==="POST") { const code=Number(action[1]); if(action[2]==="withdraw"){const r=await env.DB.prepare("UPDATE releases SET status='withdrawn' WHERE version_code=? AND status IN ('draft','published')").bind(code).run();return r.meta.changes?new Response(null,{status:204}):fail("Release does not exist or is already withdrawn",409);} const rel=await env.DB.prepare("SELECT status FROM releases WHERE version_code=?").bind(code).first();if(!rel)return fail("Release not found",404);if(rel.status!=="draft")return fail("Only a draft can be published; withdrawn releases cannot be republished",409);const highest=await env.DB.prepare("SELECT COALESCE(MAX(version_code),0) n FROM releases WHERE published_at IS NOT NULL").first();if(code<=highest.n)return fail("Publishing must increase the highest ever published version code",409);await env.DB.prepare("UPDATE releases SET status='published',published_at=? WHERE version_code=?").bind(now(),code).run();return new Response(null,{status:204}); }
      return fail("Route not found",404);
    }
    if (path === "api/app/diagnostics" && method === "POST") {
      // Opt-in anonymous playback totals from managed apps. Light per-IP limit; rows are pruned to the newest 5000.
      const ip = request.headers.get("cf-connecting-ip") || "unknown";
      const bucket = await digest(`diag:${ip}`);
      const lim = await env.DB.prepare("INSERT INTO login_limits(bucket,window_start,attempts) VALUES(?,?,1) ON CONFLICT(bucket) DO UPDATE SET attempts=CASE WHEN window_start<=? THEN 1 ELSE attempts+1 END,window_start=CASE WHEN window_start<=? THEN excluded.window_start ELSE window_start END RETURNING attempts").bind(bucket, now(), now() - 3600, now() - 3600).first();
      if (lim.attempts > 60) return fail("Too many reports; try again later", 429);
      const b = await bodyJson(request);
      const okInt = (v, max) => Number.isSafeInteger(v) && v >= 0 && v <= max;
      if (!okInt(b.versionCode, 2100000000) || b.versionCode < 1 || typeof b.versionName !== "string" || !b.versionName || b.versionName.length > 100 || typeof b.device !== "string" || b.device.length > 80 || !okInt(b.android, 100) || !Array.isArray(b.channels) || b.channels.length < 1 || b.channels.length > 200) return fail("Invalid diagnostics payload");
      for (const ch of b.channels) {
        if (!ch || typeof ch.name !== "string" || !ch.name.trim() || ch.name.length > 120 || /[\x00-\x1f\x7f]/.test(ch.name) || !okInt(ch.seconds, 31536000) || !okInt(ch.stalls, 1000000) || !okInt(ch.underruns, 10000000) || !okInt(ch.droppedFrames, 100000000) || !okInt(ch.errors, 1000000)) return fail("Invalid channel totals");
        ch.name = ch.name.trim();
      }
      const channels = b.channels.map(ch => ({ name: ch.name, seconds: ch.seconds, stalls: ch.stalls, underruns: ch.underruns, droppedFrames: ch.droppedFrames, errors: ch.errors }));
      await env.DB.prepare("INSERT INTO diagnostics(received_at,version_code,version_name,device,android,channels_json) VALUES(?,?,?,?,?,?)").bind(now(), b.versionCode, b.versionName, b.device, b.android, JSON.stringify(channels)).run();
      await env.DB.prepare("DELETE FROM diagnostics WHERE id NOT IN (SELECT id FROM diagnostics ORDER BY id DESC LIMIT 5000)").run();
      return new Response(null, { status: 204 });
    }
    if (path === "api/app/crashes" && method === "POST") {
      // Opt-in crash reports from managed apps: exception type, this app's frames and a fingerprint. Same per-IP
      // budget as playback diagnostics, and rows are pruned to the newest 5000.
      const ip = request.headers.get("cf-connecting-ip") || "unknown";
      const bucket = await digest(`crash:${ip}`);
      const lim = await env.DB.prepare("INSERT INTO login_limits(bucket,window_start,attempts) VALUES(?,?,1) ON CONFLICT(bucket) DO UPDATE SET attempts=CASE WHEN window_start<=? THEN 1 ELSE attempts+1 END,window_start=CASE WHEN window_start<=? THEN excluded.window_start ELSE window_start END RETURNING attempts").bind(bucket, now(), now() - 3600, now() - 3600).first();
      if (lim.attempts > 30) return fail("Too many reports; try again later", 429);
      const b = await bodyJson(request);
      const okInt = (v, max) => Number.isSafeInteger(v) && v >= 0 && v <= max;
      const text = (v, max) => typeof v === "string" && v.length > 0 && v.length <= max && !/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(v);
      if (!okInt(b.versionCode, 2100000000) || b.versionCode < 1 || !text(b.versionName, 100) || typeof b.device !== "string" || b.device.length > 80 || !okInt(b.android, 100)) return fail("Invalid crash report");
      if (!text(b.type, 200) || !text(b.thread, 80) || typeof b.fingerprint !== "string" || !/^[a-f0-9]{8,64}$/.test(b.fingerprint) || typeof b.stack !== "string" || b.stack.length > 4000) return fail("Invalid crash report");
      // Occurred-at is epoch milliseconds from the box; allow a day of clock skew either way, else use arrival time.
      const at = okInt(b.at, Date.now() + 86400000) ? Math.floor(b.at / 1000) : now();
      await env.DB.prepare("INSERT INTO crashes(received_at,occurred_at,version_code,version_name,device,android,type,fingerprint,thread,stack) VALUES(?,?,?,?,?,?,?,?,?,?)")
        .bind(now(), at, b.versionCode, b.versionName, b.device, b.android, b.type, b.fingerprint, b.thread, b.stack).run();
      await env.DB.prepare("DELETE FROM crashes WHERE id NOT IN (SELECT id FROM crashes ORDER BY id DESC LIMIT 5000)").run();
      return new Response(null, { status: 204 });
    }
    if (path === "api/app/config" && method === "GET") return json(await getConfig(env),200,{"cache-control":"public, max-age=60, stale-while-revalidate=300"});
    if (path === "api/app/messages" && method === "GET") { const {results}=await env.DB.prepare("SELECT id,title,body FROM messages WHERE active=1 AND (expires_at IS NULL OR expires_at>?) ORDER BY created_at DESC,id DESC LIMIT 20").bind(now()).all();return json({messages:results},200,{"cache-control":"public, max-age=30, stale-while-revalidate=60"}); }
    const releasePath=path.match(/^api\/app\/releases\/(latest|[1-9][0-9]*)(\/source)?$/);
    if(releasePath&&method==="GET"){const r=releasePath[1]==="latest"?await env.DB.prepare("SELECT * FROM releases WHERE status='published' ORDER BY version_code DESC LIMIT 1").first():await env.DB.prepare("SELECT * FROM releases WHERE status='published' AND version_code=?").bind(Number(releasePath[1])).first();if(!r)return releasePath[1]==="latest"?new Response(null,{status:204}):fail("Published release not found",404);return json(releasePath[2]?{sourceUrl:r.source_url}:{versionCode:r.version_code,versionName:r.version_name,apkUrl:`${new URL(request.url).origin}/api/app/apks/${r.filename}`,sha256:r.sha256,notes:r.notes},200,{"cache-control":releasePath[2]?"public, max-age=300":"public, max-age=60"});}
    const apkPath=path.match(/^api\/app\/apks\/([a-f0-9]{64}\.apk)$/);
    if(apkPath&&method==="GET"){const r=await env.DB.prepare("SELECT filename,size_bytes,sha256 FROM releases WHERE filename=? AND status='published'").bind(apkPath[1]).first();if(!r)return fail("APK not found",404);const object=await env.APK_BUCKET?.get(`releases/${r.filename}`);if(!object)return fail("APK not found",404);return new Response(object.body,{headers:{"content-type":"application/vnd.android.package-archive","content-disposition":`attachment; filename=\"${r.filename}\"`,"content-length":String(r.size_bytes),etag:`\"${r.sha256}\"`,"cache-control":"public, max-age=86400, s-maxage=86400, stale-while-revalidate=604800, immutable","x-content-type-options":"nosniff"}});}
    return fail("Route not found",404);
  } catch (e) { return fail(e?.message || "Internal error", e?.status || 500); }
}
async function upload(request, env) {
  if (!env.APK_BUCKET) return fail("R2 binding APK_BUCKET is not configured",503);
  const form=await request.formData(); const metadataRaw=form.get("metadata"), file=form.get("apk");
  if(typeof metadataRaw!=="string"||!(file instanceof File))return fail("Send metadata first and an APK file"); if(file.size<1||file.size>MAX_APK_BYTES)return fail("APK must be between 1 byte and 100 MiB on Pages");
  let input;try{input=JSON.parse(metadataRaw)}catch{return fail("Invalid release metadata JSON");}validateRelease(input);
  const max=await env.DB.prepare("SELECT COALESCE(MAX(version_code),0) n FROM releases").first();if(input.versionCode<=max.n)return fail(`Version code must be greater than ${max.n}; used version codes cannot be reused`,409);
  const bytes=new Uint8Array(await file.arrayBuffer()); const packageName=inspectApk(bytes,input); const sha256=bytesToHex(await crypto.subtle.digest("SHA-256",bytes));
  // The conditional update is atomic in D1, so concurrent administrator uploads
  // cannot reserve more than the application budget.
  const reservation=await env.DB.prepare("UPDATE r2_storage_budget SET used_bytes=used_bytes+? WHERE id=1 AND used_bytes+?<=? RETURNING used_bytes").bind(file.size,file.size,MAX_APK_STORAGE_BYTES).first();
  if(!reservation)return fail("Application R2 storage budget reached; uploads are paused to protect the free tier",413);
  const filename=`${random()}.apk`, key=`releases/${filename}`; let uploaded=false;
  try {
    await env.APK_BUCKET.put(key,bytes,{httpMetadata:{contentType:"application/vnd.android.package-archive"}}); uploaded=true;
    await env.DB.prepare("INSERT INTO releases(version_code,version_name,filename,sha256,notes,source_url,package_name,size_bytes,status,created_at) VALUES(?,?,?,?,?,?,?,?,'draft',?)").bind(input.versionCode,input.versionName.trim(),filename,sha256,input.notes,input.sourceUrl,packageName,file.size,now()).run();
  } catch(e) {
    if(uploaded) await env.APK_BUCKET.delete(key);
    await env.DB.prepare("UPDATE r2_storage_budget SET used_bytes=used_bytes-? WHERE id=1").bind(file.size).run();
    if(String(e).includes("UNIQUE"))return fail("Version code already exists",409); throw e;
  }
  return json({versionCode:input.versionCode,sha256,status:"draft"},201);
}
