# Cloudflare Pages deployment

The Rust dashboard is implemented as Cloudflare Pages Functions. Pages serves the dashboard UI and API from the same origin; D1 replaces the Rust process's SQLite database, and R2 stores uploaded managed APKs.

Production URL: `https://imd-tv.pages.dev`

## Cloudflare resources

The repository is configured for:

- Pages project: `imd-tv`
- D1 database: `imd-tv-panel` (binding `DB`)
- R2 bucket: `imd-tv-apks` (binding `APK_BUCKET`), required for APK uploads and download

The Cloudflare account currently has R2 disabled. Enable R2 in the Cloudflare dashboard and create the bucket before expecting release uploads to work. Until then, the API, login, server configuration, and messages work; the release upload endpoint returns 503. After enabling R2 and creating the bucket, add the `APK_BUCKET` R2 binding to the Pages project for both production and preview environments. The R2 binding is intentionally not in `wrangler.toml` until the account enables the product.

Create the bucket once R2 is enabled:

```sh
npx wrangler r2 bucket create imd-tv-apks
```

The D1 database ID is recorded in `wrangler.toml`. Do not commit API tokens, passwords, signing keys, `.dev.vars`, or APK backups.

## First deployment

From the repository root, authenticate Wrangler and deploy:

```sh
npx wrangler login
npx wrangler d1 migrations apply imd-tv-panel --remote
npx wrangler pages deploy site --project-name=imd-tv --branch=main
```

The GitHub Actions workflow performs the D1 migration and Pages deployment automatically for changes to `site`, `functions`, `migrations`, or the Wrangler configuration. Add these GitHub Actions secrets:

- `CLOUDFLARE_API_TOKEN`: account token with Pages Edit and D1 Edit permissions.
- `CLOUDFLARE_ACCOUNT_ID`: the account containing the Pages project and bindings.

The Pages production and preview environments must have the `DB` D1 and `APK_BUCKET` R2 bindings. The committed Wrangler configuration supplies those bindings for Wrangler deployments; verify them in the Pages project settings after the first deployment.

## Create the first administrator

Bootstrap is deliberately one-time and requires a temporary secret. Set `BOOTSTRAP_SECRET` as a Pages production secret, deploy, then run:

```sh
curl -X POST https://imd-tv.pages.dev/api/admin/bootstrap \
  -H "Authorization: Bearer YOUR_TEMPORARY_BOOTSTRAP_SECRET" \
  -H "Content-Type: application/json" \
  --data '{"username":"admin","password":"replace-with-a-strong-password"}'
```

Use a password with at least 12 characters. Remove `BOOTSTRAP_SECRET` immediately after the request succeeds. The endpoint cannot overwrite an existing administrator.

Open `https://imd-tv.pages.dev/admin/` to sign in. The panel uses an HttpOnly, Secure, SameSite cookie and a CSRF token for administrator writes. Public app endpoints do not require a login.

## Managed APK release flow

1. Build the managed Android flavor with `panelBaseUrl=https://imd-tv.pages.dev`.
2. Open the production admin panel.
3. Upload the signed APK with its version code, release name, release notes, and GPL corresponding-source URL.
4. The Pages Function checks the ZIP archive, binary Android manifest, managed package name, manifest version code/name, and signing artifacts, then computes SHA-256 and stores the APK in R2 as a draft.
5. Publish the draft from the panel.

The app reads:

```text
GET /api/app/config
GET /api/app/messages
GET /api/app/releases/latest
GET /api/app/releases/{version}
GET /api/app/releases/{version}/source
```

Published APKs are delivered from `/api/app/apks/{opaque-filename}`. Withdrawn releases are no longer returned by the public API or downloadable. R2 is delivery storage, so keep an independent backup of every release APK and the Android signing key.

## Local development

Run the Pages project locally with Wrangler. Local D1 and R2 state are disposable unless you explicitly configure remote resources:

```sh
npx wrangler pages dev site
```

Use an untracked `.dev.vars` file for local bootstrap values. Never put credentials or signing material in GitHub or this repository.

## Security and limits

Pages Functions perform request validation, session authentication, CSRF and same-origin checks, optimistic revision checks for server configuration, release publication monotonicity, message limits, ZIP path checks, and bounded manifest parsing. The validator does not perform cryptographic Android certificate verification; the Android client must verify the signing certificate before installing an update.

Pages request limits apply to browser uploads. The dashboard currently limits an APK upload to 100 MiB in the Pages Function and UI. Cloudflare plan limits and R2 charges can change, so review current Cloudflare limits and configure billing alerts before production use.
