# Publishing Android releases

A release is built and attached to GitHub automatically when a version tag beginning with `v` is pushed (for example, `v0.6.1`). The workflow builds the signed `standardRelease` APK, names it `imd-tv.apk`, creates a SHA-256 checksum, and publishes both files as assets on a GitHub Release. It does not upload the managed flavor to Cloudflare R2.

## One-time signing setup

Android requires updates to be signed with the same key as the installed app. If this app has already been distributed under the same Android package name, use its existing signing key; a different key will prevent in-place updates. Keep a protected backup of the keystore and its passwords. Never commit or send them in chat.

In the GitHub repository, open **Settings → Secrets and variables → Actions → New repository secret**, then add these four secrets:

- `ANDROID_KEYSTORE_BASE64`: base64 encoding of the `.jks` or `.keystore` signing file.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: alias for the signing key.
- `ANDROID_KEY_PASSWORD`: password for that key.

If no release key exists yet, create one locally with Java's `keytool` and store it somewhere backed up and private. For example:

```sh
keytool -genkeypair -v -keystore imd-tv-release.jks -alias imd-tv -keyalg RSA -keysize 2048 -validity 10000
```

On Windows PowerShell, encode the file without printing the encoded key to the terminal:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\imd-tv-release.jks")) | Set-Clipboard
```

Paste the clipboard contents directly into the `ANDROID_KEYSTORE_BASE64` secret. Enter the other three values into their respective GitHub secrets. Do not paste any signing secret into an issue, pull request, repository file, or chat.

## Publish a release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`. Increase `versionCode` for every published APK; Android rejects updates whose code is not higher than the installed version.
2. Commit and push the change to `main`.
3. Create and push a version tag pointing at that commit, for example:

   ```sh
   git tag v0.6.1
   git push origin v0.6.1
   ```

4. Open the repository's **Actions** tab and confirm the **Android release** workflow succeeds. It creates a GitHub Release with `imd-tv.apk` and `imd-tv.apk.sha256`.

The tag should be created only after the signing secrets are configured. The workflow intentionally fails instead of publishing an APK signed with a temporary debug key when a secret is missing.

## Cloudflare managed APK distribution

This workflow publishes the standard APK to GitHub Releases. Managed APK distribution is separate: it needs the managed flavor, the production panel URL, a signing key, an R2 bucket binding, and a release metadata update in Cloudflare Pages. The GitHub Release workflow does not upload APKs to R2 or update Cloudflare's `APP_RELEASES_JSON`.
