export async function onRequestGet({ env }) {
  if (!env.APK_BUCKET) return new Response("APK delivery is not configured", { status: 503 });
  const object = await env.APK_BUCKET.get("managed/latest.apk");
  if (!object) return new Response("APK not found", { status: 404 });
  const headers = new Headers({
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Disposition": 'attachment; filename="imd-tv-managed.apk"',
    "Cache-Control": "public, max-age=300, s-maxage=3600, stale-while-revalidate=86400",
    "X-Content-Type-Options": "nosniff",
  });
  if (object.size != null) headers.set("Content-Length", String(object.size));
  if (object.httpEtag) headers.set("ETag", object.httpEtag);
  return new Response(object.body, { headers });
}

export async function onRequest({ request }) {
  if (request.method === "HEAD") return new Response(null, { status: 405, headers: { Allow: "GET" } });
  return new Response(null, { status: 405, headers: { Allow: "GET" } });
}
