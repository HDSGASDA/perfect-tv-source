# Managed TV — Rust management panel

A local-first Axum + SQLite service with a responsive, plain JavaScript/CSS browser interface. **No Node runtime, package manager or frontend build is required.** All files for this implementation live in `rust-panel/`; the separate Node prototype is not used.

## Prerequisites

- A current stable Rust toolchain with Cargo. On Windows, use the MSVC toolchain and Visual Studio C++ Build Tools / Windows SDK. SQLite is compiled from bundled source; a separate SQLite installation is unnecessary.
- An NTFS/local filesystem for `DATA_DIR` (the uploader atomically creates immutable APK artifacts using hard links). Keep this directory private to the service account. Do not put it on a network share or in a public web directory.
- A signed APK built for `org.imdtv.iptvtv.managed`. Initial app metadata: version code `11`, version name `0.6.0-managed.1`.

## PowerShell: first run

```powershell
Set-Location 'C:\Users\User\Documents\potential app for tv\rust-panel'

# If Rust is installed under your user account but not yet on PATH:
$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"
cargo --version
rustc --version

Copy-Item .env.example .env
$env:ADMIN_USERNAME = 'your-admin-name'
cargo run -- bootstrap-admin
# Enter a strong, unique password at the hidden prompt (minimum 12 characters).
# The bootstrap command refuses to overwrite an existing administrator.

cargo run -- serve
```

Open **http://localhost:3000**. The default listener is `127.0.0.1:3000`. Use `localhost`, not `127.0.0.1`, in your browser unless you change `ADMIN_ORIGIN`; administrator writes require the exact configured origin.

For unattended bootstrap, `ADMIN_PASSWORD` can be supplied as a temporary process environment variable instead of the password prompt. Avoid putting passwords in shell command history, arguments, checked-in files or `.env`. Remove it after bootstrap:

```powershell
Remove-Item Env:ADMIN_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:ADMIN_USERNAME -ErrorAction SilentlyContinue
```

Startup is explicit: `serve` refuses to run until `bootstrap-admin` has created an account. The account uses Argon2id password hashing with a random salt; there are no default credentials. Bootstrap is a one-time operation, not a password-reset command. This version has one administrator, no account-management/password-reset UI, and no automatic credential rotation.

## Configuration

`.env` is loaded from the working directory with `dotenvy`; explicit environment variables take precedence. `.env`, the data directory, logs, artifacts and build output are ignored by the included `.gitignore`.

| Variable | Default | Meaning |
|---|---|---|
| `HOST` | `127.0.0.1` | Literal listener IP; binding to another interface is explicit |
| `PORT` | `3000` | Listener port |
| `PUBLIC_BASE_URL` | `http://localhost:3000` | Absolute HTTP(S) origin used for public APK URLs; never inferred from `Host` or forwarded headers |
| `ADMIN_ORIGIN` | `PUBLIC_BASE_URL` | Exact browser origin allowed for all administrator writes, including login |
| `APP_ENV` | `development` | `production` requires HTTPS for both configured origins and enables Secure cookies/HSTS |
| `DATA_DIR` | `data` | Persistent SQLite database, temporary uploads and immutable APK storage |
| `ADMIN_USERNAME` | none | Only read by `bootstrap-admin` |
| `ADMIN_PASSWORD` | none | Optional bootstrap-only password; otherwise prompted invisibly |
| `RUST_LOG` | service/HTTP info | Rust tracing filter; request bodies, passwords and tokens are not logged |

Origins cannot contain credentials, non-root paths, queries or fragments. A terminal slash is accepted and normalized away. The service is mounted at the origin root, not a URL subpath.

### Android emulator

The Android app's panel base URL must be `http://10.0.2.2:3000`; `localhost` inside the emulator means the emulator itself. The APK URL advertised by the service must also be reachable from the emulator:

```powershell
$env:PUBLIC_BASE_URL = 'http://10.0.2.2:3000'
$env:ADMIN_ORIGIN = 'http://localhost:3000'
cargo run -- serve
```

The standard Android emulator forwards `10.0.2.2` to the host loopback. If emulator networking differs on your machine, explicitly configure a reachable host interface. Plain HTTP is for isolated development only and requires the Android development build's cleartext-network allowance.

### LAN / physical TV development

```powershell
$env:HOST = '0.0.0.0'
$env:PUBLIC_BASE_URL = 'http://192.168.1.50:3000' # Replace with your actual LAN IP
$env:ADMIN_ORIGIN = 'http://192.168.1.50:3000'
$env:APP_ENV = 'development'
cargo run -- serve
```

Use the same LAN origin in the browser and Android app. Add a narrow Windows Firewall rule only if needed, restricted to your trusted test subnet. Development HTTP exposes credentials/session traffic to anyone able to inspect that network. Do not expose development mode to the public internet.

### Production

```powershell
$env:HOST = '127.0.0.1'
$env:PUBLIC_BASE_URL = 'https://tv-admin.example.com'
$env:ADMIN_ORIGIN = 'https://tv-admin.example.com'
$env:APP_ENV = 'production'
cargo build --release
.\target\release\managed-tv-panel.exe serve
```

Terminate TLS at a maintained reverse proxy and forward to the private loopback listener. Axum itself does not terminate TLS. Redirect external HTTP to HTTPS, retain the browser's `Origin` header, permit requests up to **256 MiB + 64 KiB**, and configure upload/proxy timeouts appropriately (the backend upload deadline is ten minutes). The externally accessible service must use HTTPS. Secure `__Host-` cookies have `HttpOnly`, `SameSite=Strict`, `Path=/` and no `Domain`. Session lifetime is eight hours, with at most ten retained sessions.

The backend intentionally uses socket IPs, not untrusted forwarding headers, for rate limits. Behind one proxy, clients share that proxy's backend limit. Apply appropriate client-IP rate limits at the proxy; do not weaken origin checks. Public requests are limited to 1,200/minute/socket IP, logins to 10/five minutes/IP plus 60/five minutes globally, with four concurrent password verifications and two concurrent uploads. Rate-limit counters are in memory and reset on restart. CORS is not enabled: public APIs are for the native app, and administration is same-origin.

## Browser workflows

### Releases

1. Upload a signed APK, its exact version code/name, release notes and an HTTPS **GPL corresponding source URL**. Initial form values are `11` and `0.6.0-managed.1`; use higher codes for subsequent releases.
2. The server streams the upload to disk, enforces the 256 MiB APK limit, computes SHA-256 itself, inspects the ZIP structure and reads the binary `AndroidManifest.xml`. Package/version must match the submitted metadata and expected package. APK filenames are server-generated opaque 256-bit identifiers; user filenames are ignored.
3. Review the saved **draft**, then publish. Only published releases are returned to the app or downloadable. Both package and APK contents are immutable once uploaded.
4. Withdrawal immediately removes metadata/download availability for that release. It does not uninstall an app or undo an installed update. Withdrawal is permanent; upload a new higher version to replace it. If an older published release remains, `latest` returns that release; Android must still reject downgrades.

Version codes are unique and strictly increase across all uploads, including drafts and withdrawn releases. Publishing must increase the highest ever published version. A higher draft does not by itself make a lower draft ineligible for publication, provided it is newer than every release ever published. Release history is retained; the dashboard shows the latest 500 records.

**Signing boundary:** the server checks for v1 signing artifacts or an APK Signing Block envelope, but does not verify their cryptographic validity, identify the signing certificate, validate DEX bytecode, or prove that the APK is safe. Android must check package, SHA-256, increasing version code and the existing app's signing certificate **before invoking the installer**; Android's package installer also verifies the APK. Preserve the original signing key. An upload passing this panel's checks is not a security certification.

The parser accepts conventional, single-disk, non-ZIP64 APK ZIPs, up to 20,000 entries, an 8 MiB central directory and a 2 MiB manifest. It supports UTF-8/UTF-16 binary XML string pools and literal package/version names; resource-referenced version names and nonzero `versionCodeMajor` are intentionally rejected. Only the manifest is decompressed, with a hard bound; archive paths are never extracted to the filesystem. Malformed/duplicate archive entries are rejected. This is basic structural validation, not a complete Android resource parser.

The source URL should identify the exact corresponding source, build scripts and modifications needed to satisfy the app's GPL obligations. The panel records and publishes the link but cannot determine whether its contents are complete or legally sufficient. Source archives are hosted by the operator, not uploaded here.

### Messages

Broadcasts are public: anyone can fetch them without signing in. Titles contain 1–120 characters, bodies 1–4,000 characters. At most 20 active, unexpired messages are allowed. A message can have no expiration or an expiration in the next year. Administrators can update expiration, deactivate or reactivate a message. Expired messages disappear from the public API without a scheduled worker. The dashboard shows the latest 500 records. No targeted enrollment, customer/device records, remote commands or background push service is implemented.

The Android integration should poll messages every **60 seconds while foregrounded** and check for updates **hourly**. This repository implements the server and browser UI, not the Android polling/installer code.

### Provider servers

The public configuration initially contains:

```json
{"revision":1,"servers":[{"id":"primary","name":"Primary server","url":"https://protect.securemy.network:443"}],"defaultServerId":"primary"}
```

The administrator edits the entire list in one atomic operation. Each successful save increments the persisted revision; a stale submitted revision receives HTTP 409 instead of overwriting another edit. Lists must contain 1–10 entries, unique safe IDs (`[a-zA-Z0-9_-]{1,64}`), names of 1–80 characters, unique normalized HTTPS origins and a default ID that exists. Default ports and host case are normalized on save (e.g. `https://EXAMPLE.com:443/` becomes `https://example.com`). URLs cannot contain credentials, paths, queries or fragments.

**Provider server URLs receive customer provider credentials.** Only trusted origins should be added. The Android app must offer manual selection and require login re-entry when changing servers; it must not automatically fail over or silently send saved credentials to a new origin. The server list is public, but the service does not accept or store customer provider credentials.

## Public API contract

All fields use the exact names below; unknown release versions and non-published APKs return 404.

- `GET /api/app/releases/latest`: **204 with an empty body** if no published release exists; otherwise the object below.
- `GET /api/app/releases/{versionCode}`: the same object for a published version, or 404.
- `GET /api/app/apks/{opaqueFilename}`: streams the published immutable APK bytes. URLs use configured `PUBLIC_BASE_URL`, never request `Host`.

```json
{"versionCode":11,"versionName":"0.6.0-managed.1","apkUrl":"http://localhost:3000/api/app/apks/<opaque>.apk","sha256":"<64 lowercase hexadecimal characters>","notes":"Release notes"}
```

- `GET /api/app/releases/{versionCode}/source`: `{"sourceUrl":"https://..."}` for a release that has been published. This separate endpoint preserves the exact five-field release contract.
- `GET /api/app/messages`: `{"messages":[{"id":"opaque string","title":"Notice","body":"Text"}]}`. At most 20 active, unexpired messages, newest first.
- `GET /api/app/config`: `{ "revision": integer, "servers": [{"id":"...","name":"...","url":"https://..."}], "defaultServerId":"..." }`.
- `GET /health`: `ok`; no sensitive information.

## Administrator API / CSRF

- `POST /api/admin/login` with JSON `{username,password}` and the exact configured `Origin`. Returns `{csrfToken}` and a server-session cookie. The browser enforces JSON content type and origin boundaries; there is no credential-bearing GET endpoint.
- `GET /api/admin/session` returns `{username,csrfToken}` with a valid session.
- All other administrator writes require the session cookie, exact `Origin`, and `X-CSRF-Token`. Login is protected by strict origin validation plus JSON-only parsing rather than a pre-login CSRF cookie.
- `POST /api/admin/logout` revokes the server session and clears the cookie.
- `GET /api/admin/releases`; `POST /api/admin/releases` multipart **metadata first, apk second**. Metadata JSON is `{versionCode,versionName,notes,sourceUrl}`; only these two fields are accepted. Metadata is bounded at 48 KiB.
- `POST /api/admin/releases/{versionCode}/publish` or `/withdraw`.
- `GET /api/admin/messages`; `POST /api/admin/messages` JSON `{title,body,expiresAt}`; `PUT /api/admin/messages/{id}` JSON `{active,expiresAt}`. Expiration is UTC Unix seconds or `null`.
- `GET /api/admin/config`; `PUT /api/admin/config` with the full public configuration object and current revision.

General JSON bodies are limited to 64 KiB. Error responses from application handlers are `{error:"message"}` with suitable 4xx/5xx status codes; framework-level extraction failures may be text responses. The UI handles either format. Browser rendering uses `textContent`/DOM construction, never untrusted HTML. CSP, anti-framing, no-sniff, no-referrer and no-store headers are applied.

## Tests and maintenance

```powershell
cargo fmt --check
cargo check --locked
cargo test --locked
cargo clippy --all-targets --locked -- -D warnings
```

Tests exercise authentication, Argon2 storage, no-default bootstrap, login origin checks, write CSRF, logout, production cookies, rate limiting, atomic server revisions and URL validation, message limits/expiry, binary manifest parsing, APK upload and metadata mismatch rejection, server-computed hashes, release publication/withdrawal, hostile Host headers, public JSON shapes and persistence across database reopen. Test APK signing files are deliberately fake to demonstrate the structural-only verification boundary; production acceptance still requires Android-side signature verification.

Back up the entire private data directory **while the service is stopped**, including the database and APKs. SQLite uses WAL mode; copying only the live `.sqlite3` file is not a safe backup strategy. Restore database and artifacts together. Sessions persist across restart until expiration; expired sessions are cleaned on successful login. To revoke all sessions operationally, stop the service and delete rows from the `sessions` table using a trusted SQLite administration tool, then restart.

An interrupted/crashed upload can leave an orphan `.part` file or, in a tiny file/database commit window, an unreferenced APK. Normal validation/error paths clean up temporary files. Inspect and remove orphan artifacts only while stopped and after comparing filenames with the database; never delete published or historical artifacts blindly. There is no automatic retention deletion, audit trail, multi-operator role model, backup scheduler or password reset flow in this initial panel. Restrict filesystem permissions and production access, monitor disk capacity, and maintain the reverse proxy and Rust dependencies.
