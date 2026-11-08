# Releases and in-app updates

The private source repository is [krabilousse/slinktv](https://github.com/krabilousse/slinktv). The app defaults to this repository under **Settings → App updates**. Source can be changed to another GitHub repository or an HTTPS `update.json` feed. Source changes forget the saved credential and pending UI selection. Stable uses GitHub's latest stable release; Preview selects the newest published release among the first 100 releases, including prereleases. Version codes must increase across both channels.

## Private-repository access

Private release assets cannot be downloaded anonymously. On GitHub, create a fine-grained personal access token for **only this repository**, with **Contents: read**, and an expiry. Enter it once in **Settings → App updates → GitHub credential** using the TV/phone keyboard. The app encrypts it using Android Keystore, excludes application data from backups, masks entry, and provides Replace/Forget. This permission also permits reading repository source; it is not release-only access. No developer token is bundled in the APK. Twitch credentials are unrelated and are never used for updates.

The credential is sent only to the configured repository's exact GitHub API origin/path. Release metadata selects exact `update.json` and APK asset names. Both assets use GitHub's authenticated binary asset endpoint. Redirects must remain HTTPS and receive no authorization header. Custom HTTPS feeds receive no GitHub credentials.

For a credential-free distribution later, use a public APK/feed host while keeping source private. That makes the APKs public and requires a separate publishing decision. See [SmartTube and update research](update-seeking-research.md) for primary sources and device-flow alternatives.

## TV update behavior

- Automatic checks run on opening the app, at most every six hours, when the source is accessible. They never interrupt playback. Manual Check now bypasses the interval.
- The user chooses Download, then Install. Android may first require enabling **Install unknown apps** for SlinkTV. Return to the app and select Install again. Android asks for installation confirmation; silent installation is not implemented.
- Downloads are bounded to 300 MB, time-limited, written to app-private storage, and checked for exact byte count and SHA-256. Package name, version code, and signing certificates must match the update policy before the file is handed to Android. Only newer versions are accepted.
- Cancelling the installer keeps the verified download available while the update screen remains open. Reopening the screen can download again. Each download has a separate partial file so cancelling/reopening cannot overwrite another worker's file. Partial files are never offered for installation. Verified filenames include version and checksum so an installer URI keeps identifying the selected build.
- FileProvider exposes only the update directory. Launching Android's installer does not mean installation succeeded; the installed version is displayed on reopening the app.

## Signing and publishing

Version 0.6.0 uses a non-debuggable release build signed with the same key as earlier prototype installations, allowing an in-place upgrade. The original key has a backup outside this repository at `%USERPROFILE%/.android/slinktv-signing.keystore`. Keep that private file backed up; signing identity cannot be replaced arbitrarily. The existing key's alias/password are the Android debug defaults, but the key material is never committed. Repository Actions secrets hold its encrypted build copy: `ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`.

1. Increase both `versionName` and `versionCode` in `app/build.gradle`; use a unique display version such as `0.7.0` or `0.7.0-preview.1`.
2. Add the matching `## [version] - date` entry to `CHANGELOG.md`.
3. Build/test locally with `scripts/build-release.ps1`. The script reuses the installed prototype key; specify `-SigningKey` if using its backup.
4. Commit using the owner's Git identity, then create an annotated `v<version>` tag. Push the commit and tag. The tag must match the Gradle version.
5. The tagged-release workflow runs Android host tests, Python tests and lint, builds/signs the APK, verifies signing and ZIP alignment, and checks version-code monotonicity against published manifests. It creates a draft release, uploads the universal APK, `update.json`, and `SHA256SUMS.txt`, then publishes it. Preview tags containing `-` become prereleases.

The first release draft can be created through the owner's authenticated `gh` CLI before pushing the tag, preserving that user's release authorship; the workflow fills and publishes the draft. Published releases are immutable to the workflow: reruns refuse to replace them. To fix a published build, use a new version and tag. A failed draft can be retried.

Local packaging uses:

```powershell
python scripts/release.py --apk app/build/outputs/apk/release/app-release.apk --tag v0.6.0 --check-history
```

`scripts/release.py` validates APK identity/version, reads release notes from the changelog, and writes the universal APK, notes, checksum, and manifest to ignored `dist/`. The LAN `app.apk` alias points to those same packaged bytes. Build dependencies are pinned in Gradle/Python files; GitHub Actions are pinned to verified commit IDs.

Custom HTTPS manifest example (replace the sample size/hash with real values):

```json
{
  "schemaVersion": 1,
  "packageName": "tv.slink.app",
  "versionCode": 9,
  "versionName": "0.7.0",
  "minSdk": 34,
  "apkName": "SlinkTV-0.7.0.apk",
  "apkUrl": "https://updates.example/SlinkTV-0.7.0.apk",
  "size": 86000000,
  "sha256": "replace-with-the-64-character-sha256",
  "notes": "Release changes"
}
```
