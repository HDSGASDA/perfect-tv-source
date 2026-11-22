# Cloudflare Pages deployment

The Rust dashboard is implemented as Cloudflare Pages Functions. Pages serves the dashboard UI and API from the same origin; D1 replaces the Rust process's SQLite database, and R2 stores uploaded managed APKs.

Production URL: `https://imd-tv.pages.dev`

## Cloudflare resources

The repository is configured for:

- Pages project: `imd-tv`
- D1 database: `imd-tv-panel` (binding `DB`)
- R2 bucket: `imd-tv-apks` (binding `APK_BUCKET`), required for APK uploads and download

The R2 bucket is configured in `wrangler.toml` as the `APK_BUCKET` binding. The account must have R2 enabled and the `imd-tv-apks` bucket must exist before deploying this configuration.

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

The upload path enforces an 8,000,000,000-byte (8 GB decimal) application budget and reserves 2 GB of the 10 GB-month R2 Standard free-storage allowance for other account usage. Usage reservations are stored in D1 and acquired atomically, so overlapping uploads cannot both consume the same remaining budget. Draft, published, and withdrawn APKs all count because their R2 objects are retained. Each upload is limited to 100 MiB. R2's free allowance is shared account-wide, so the 2 GB reserve must cover any other buckets, legacy objects, or temporary use in the account; check the Cloudflare R2 dashboard before deployment and periodically thereafter. This application guard cannot constrain writes made outside this upload endpoint or cap Class A/Class B request counts. Standard R2 egress is free; operation allowances are 1 million Class A and 10 million Class B requests per month. See [Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/) for current limits. The admin release view displays application-accounted usage.
