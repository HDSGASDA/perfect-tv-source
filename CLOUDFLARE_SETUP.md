# Cloudflare free-tier hosting

This project can host its static website and its small managed-app API on Cloudflare Pages. The release APK can live in Cloudflare R2 and be streamed from the same site origin. The ordinary Android app continues to download public releases from GitHub and connects directly to each user's IPTV provider; this project deliberately does not proxy video streams.

The Pages and Workers free plan has usage limits. R2 includes a free monthly allowance (including storage, operations and internet egress), but usage beyond the free allowance can be billed. Review the current [Pages limits](https://developers.cloudflare.com/pages/platform/limits/), [Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/) and [R2 pricing](https://developers.cloudflare.com/r2/pricing/) before launch, and set account billing alerts. No paid product is required by this repository's setup.

## One-time Cloudflare setup

1. The Cloudflare Pages project `imd-tv` has been created in the connected account; its default hostname is `imd-tv.pages.dev`. In Cloudflare, set its production branch to `main` and use the root `site` directory as the build output. The workflow below deploys via Wrangler direct upload.
2. Enable R2 in the Cloudflare dashboard if it is not already enabled, then create an R2 bucket named `imd-tv-apks`. Upload the signed managed APK as `managed/latest.apk` when publishing a managed release. Keep an independent copy of the APK and signing key; R2 is delivery, not the only backup.

The R2 binding is intentionally not included in `wrangler.toml` yet because R2 must first be enabled on the account. Once the bucket exists, add the following Pages production and preview binding in the Cloudflare dashboard, or add this block to the Wrangler configuration before deploying:

```toml
[[r2_buckets]]
binding = "APK_BUCKET"
bucket_name = "imd-tv-apks"
```

3. GitHub is the source repository and CI system; Cloudflare Pages hosts the website. In GitHub repository settings, add Actions secrets `CLOUDFLARE_API_TOKEN` (an account-scoped token with `Pages Write` for the account containing `imd-tv`) and `CLOUDFLARE_ACCOUNT_ID`. The workflow in `.github/workflows/cloudflare-pages.yml` deploys the site and Pages Functions on pushes to `main` that touch those files. The repository does not use GitHub Pages. The current workflow does not upload objects to R2, so R2 permissions are not needed for this deployment until an R2 upload step is added.
4. The Pages project already has `APP_CONFIG_JSON` and `APP_MESSAGES_JSON` configured for both production and preview. The initial config points at the server origin already present in the managed Gradle build, and the message feed starts empty. Update either variable in the dashboard whenever the public server list or notice feed changes. Add `APP_RELEASES_JSON` for each managed release after a signed managed APK and its SHA-256 are ready. These are public responses; never put credentials, API tokens, IPTV provider details or private signing material in them.

`APP_CONFIG_JSON` example:

```json
{"revision":1,"servers":[{"id":"primary","name":"Primary server","url":"https://stream.example.com"}],"defaultServerId":"primary"}
```

`APP_MESSAGES_JSON` example:

```json
{"messages":[]}
```

`APP_RELEASES_JSON` example (use the actual package version and SHA-256, and ensure the APK at the R2 key is the same signed file):

```json
{"latest":{"versionCode":11,"versionName":"0.6.0-managed.1","apkUrl":"https://imd-tv.pages.dev/downloads/managed.apk","sha256":"REPLACE_WITH_64_HEX_CHARACTERS","notes":"Release notes"},"versions":{"11":{"versionCode":11,"versionName":"0.6.0-managed.1","apkUrl":"https://imd-tv.pages.dev/downloads/managed.apk","sha256":"REPLACE_WITH_64_HEX_CHARACTERS","notes":"Release notes"}}}
```

The app accepts APK URLs only from the same HTTPS origin as its panel. If you use a custom domain, put that exact origin in each `apkUrl` and set the Gradle property `panelBaseUrl` to it when building the managed flavor. The APK endpoint is a same-origin R2-backed download; Cloudflare does not store or handle users' IPTV passwords.

## Local development

Use Node.js and Wrangler: `npx wrangler pages dev site`. Pages Functions use the local R2 binding only after it has been added to the configuration; otherwise the download endpoint returns 503. The app API can be checked at `/api/app/config`, `/api/app/messages`, and `/api/app/releases/latest` after adding local `.dev.vars` values. Keep `.dev.vars` untracked and never commit secrets. For a quick static preview, serve `site/` with any local HTTP server.

## Free-first design choices

- Pages serves the existing static site and compressible assets; browser caching is set in `site/_headers`.
- Pages Functions serve only public JSON configuration/messages/release metadata. The app requires no database for these read-only values, so this avoids adding a paid dependency.
- R2 serves the optional managed APK from the same origin, which meets the app's download-origin validation and avoids using Workers as an APK proxy.
- No Analytics, Workers AI, Stream, Images, D1 or paid add-ons are enabled. They are unnecessary for the current app; app usage analytics would also conflict with its privacy promise.
