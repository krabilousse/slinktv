# Self-updates and long-VOD seeking

Checked **2026-11-08**. Research only; no application changes, credentials, authenticated private-repo requests, APK execution, commits, pushes, or upstream changes. Builds and TV behavior were not tested. Source observations below are pinned; official documentation and live feeds are dated observations. Builds inspected locally declare `tv.slink.app`, minimum API 34, target API 36, and Media3 1.9.3 in [app/build.gradle](../app/build.gradle). This supplements [VOD implementation research](vod-implementation-research.md); the faster seeking proposal below supersedes its initial 600-ms/60-second recommendation.

**Recommendation:** support two configurable update sources: GitHub releases (default repository `krabilousse/slinktv`, user-supplied private access) and an HTTPS delivery feed. For this owner's private deployment, a manually provisioned, expiring, single-repository **Contents: read** fine-grained PAT is feasible today. For distribution to other TVs, prefer a separate public APK/feed host if public binaries are acceptable; keep the source repository private. Never bundle a developer token. Automatically check/download; initially let the user launch Android's installer. For 25-hour recordings, use pending scrubbing with bounded acceleration, explicit OK/Back, and a duration-aware “Go to time” editor.

## SmartTube: observed update machinery

Inspected SmartTube [`a945135eee6387cd6549b87604ad673944c06b5d`][st] and its actual SharedModules gitlink [`5b6b5d1d3ba670e3f89edef1d1394a7562251f2c`][sm], not the dependency's moving HEAD.

- **Feed/hosting:** flavor resources select public GitHub release assets named `smarttube_beta2.json` or `smarttube_stable2.json`, under the literal release tag `latest`. The URLs retain the old `SmartTubeNext` repository name. Both feeds resolved during inspection. They contain generic `downloadUrlList` plus ABI-specific `downloadUrlList_arm64-v8a` and `downloadUrlList_x86`, pointing to APK assets on the same release. These mutable URLs are deployment endpoints, not immutable evidence. [Beta configuration][st-beta], [stable configuration][st-stable], [live beta][beta-feed], [live stable][stable-feed].
- **Format:** a top-level `package` object supplies download locations; other keys are display version names, each containing integer `versionCode` and changelog arrays. Parser prefers `downloadUrlList_<primary ABI>`, then `downloadUrlList`, then `downloadUrl`. It sorts numerically by `versionCode`, accumulates changes newer than installed, and supports localized `changelog_<language>`. A higher display version alone does not trigger an update. [Parser][st-parser].
- **Checking/download:** normal checking is preference- and staleness-gated; force-check bypasses that gate. Default check interval is 60 seconds, not a nightly scheduler. A found update is downloaded before notifying the UI. Downloader tries candidate URLs, writes cached `update.apk`, and rejects files at or below its default 1 MB minimum. Checker requires a fresh file (15-minute window), a parseable APK, and matching package name. **No SHA-256 or signing-certificate comparison appears in these inspected checker/downloader paths.** Do not treat their package-name check as publisher authentication. [Checker][st-checker], [settings][st-settings], [downloader][st-download].
- **Install/signing:** presenter exposes an Install action; helper opens `ACTION_VIEW` with APK MIME type and a read URI grant. The updater manifest declares FileProvider and `REQUEST_INSTALL_PACKAGES`. Gradle loads release signing configuration from an external properties file; workflow signing material comes from Actions secrets. This establishes the mechanism, not the identity or safety of an unexamined release APK. [Presenter][st-presenter], [installer helper][st-install], [manifest][st-manifest], [build][st-build].
- **Nightly is distinct:** checked `CI.yml` runs on `master` pushes/manual dispatch, appends `-nightly-<run number>` to `versionName`, deliberately preserves `versionCode`, and uploads workflow artifacts. It does **not** publish an updater feed or release assets. Equal-code nightlies therefore are not discovered as newer by this checker. No scheduled nightly release-publishing pipeline is established by this workflow. SlinkTV should publish each nightly with a strictly increasing code and explicit channel metadata. [Pinned CI][st-ci].

Minimal illustration of SmartTube's parser format (not SlinkTV's proposed schema):

```json
{
  "package": {"downloadUrlList": ["https://updates.example/app.apk"]},
  "1.2": {"versionCode": 12, "changelog": ["Player improvements"]}
}
```

## Private GitHub delivery without a bundled secret

**Owner's TV now:** create a fine-grained PAT outside the app, select only `krabilousse/slinktv`, grant **Contents: read**, set expiry, and enter it in a masked TV setting. The release/asset APIs accept that permission. It also grants repository content access: GitHub does not make this a release-only credential. Do not distribute the owner's token to other viewers. Fine-grained PAT availability depends on ownership/membership and organization policy; do not assume an outside collaborator can use the owner's setup unchanged. [Release APIs][gh-releases], [asset API][gh-assets], [PAT restrictions][gh-pat].

**Public delivery feed:** keep development private, publish only intended signed APKs and metadata to a separate public release repository or static HTTPS host. The TV needs no GitHub token. This makes binaries public, including their extractable contents. Publishing credentials and APK signing keys stay in controlled build infrastructure, never in the APK. This is the recommended distribution design if public binaries are acceptable. It does not make private repository release URLs anonymously accessible.

**Device authorization:** GitHub device flow can obtain a user's token without an embedded client secret. It requires a registered OAuth/GitHub App, its public client ID, device flow enabled, a displayed verification URL/code, and interval-respecting token polling. The authenticating user still needs access to the private repo. A conventional OAuth App's `repo` scope is broad read/write, not single-repo read-only; prefer a fine-grained GitHub App design if investing in this onboarding. Do not invent a client ID or reuse another application's registration. Installation tokens require trusted server-side app credentials; do not place an app private key in the TV. [Device flow][gh-device], [scope definitions][gh-scopes].

For private binaries delivered to users who must not receive source-repo access, use a separate private distribution repo or an authenticated delivery service. A standalone anonymous TV cannot securely read private GitHub assets using only a public repository name/client ID.

**Credential boundary (proposed):** encrypt the entered token with an Android Keystore-backed key, keep ciphertext in app-private storage excluded from backups, never display it again or log headers, and provide Forget/Replace. Keystore protects encryption keys; a running app still handles plaintext tokens. Bind authorization to the chosen GitHub API origin/repository, not to arbitrary configured feed or APK hosts. Clear pending downloads on source change. [Keystore][android-keystore].

### Verified REST flow

1. Fetch `GET https://api.github.com/repos/krabilousse/slinktv/releases/latest` for stable, or `/releases/tags/{configured-tag}` for an explicit channel pointer. Use `Authorization: Bearer <user-entered credential>`, `Accept: application/vnd.github+json`, and an explicit supported `X-GitHub-Api-Version` (current docs show `2026-03-10`). `/latest` excludes drafts/prereleases, so it is unsuitable for automatically selecting nightly prereleases. An alternative is a paginated release list with explicit channel filtering. [Releases][gh-releases].
2. Select an exact expected manifest/APK asset name and compatible ABI, not the first `.apk`. Download each private asset through `GET /repos/{owner}/{repo}/releases/assets/{asset_id}` with **`Accept: application/octet-stream`** and authorization. Handle **200 binary response or 302 redirect**. `browser_download_url` is a browser download address, not a credential-free private distribution mechanism. [Assets][gh-assets].
3. Follow the server-provided HTTPS redirect with **no GitHub Authorization header on a different origin**; do not log/persist the signed URL. Bound redirects and bytes; reject scheme downgrade. Re-request the API asset endpoint when a signed URL expires. This is the proposed client security policy, not an extra GitHub API parameter.
4. Metadata includes asset size and a `digest` field such as `sha256:…`. Verify it when present; publish a required SHA-256 in SlinkTV's manifest so missing legacy digests are handled explicitly. Treat 401/403/404 as access/availability errors, never “already current”; retain the installed app and offer retry/credential replacement. [Asset response][gh-assets].

The official contract was verified; an authenticated download from the user's private repo was intentionally not attempted.

## Proposed SlinkTV feed, integrity, and Android handoff

Use a small versioned JSON record: `schemaVersion`, `packageName`, `channel`, monotonically increasing `versionCode`, display `versionName`, `minSdk`, release notes, and artifacts containing ABI, byte size, SHA-256 and either HTTPS URL or GitHub asset ID. Use a universal APK initially if size permits. Publish/version APKs first, verify them, then publish the channel pointer last. Avoid replacing an APK behind an already-published version. Compare APK `getLongVersionCode()` with the manifest and installed version; reject mismatches, wrong package/channel, incompatible ABI/SDK, and downgrades. A channel switch must still obey version monotonicity.

Download to an app-private `.part` file with bounded size, timeout/cancellation, free-space check and streamed SHA-256; rename only after validation. SHA-256 detects mismatches but cannot authenticate an attacker-controlled feed. **The APK signing identity is the independent trust anchor.** Use one protected production signing identity across updates; reject unknown signers even for a custom source. Check archive signing metadata against the installed/pinned identity, and let Android perform final signature verification. Any key rotation needs an explicit supported signing-lineage policy. CI should run `apksigner verify --verbose --print-certs` on the exact published bytes. A debug-signed installed build cannot seamlessly accept an unrelated production signing key. [Signing][android-sign], [PackageManager][android-pm], [apksigner][android-apksigner].

Automatic checking can run on foreground entry with a persisted cooldown and a manual Check now action; background daily checking is optional and must not promise an exact overnight execution time. Keep update prompts out of playback. Source selection must never change the trusted package/signer. No storage permission is needed for app-private files. [Internal storage][android-storage].

**Simple installer flow for API 34+:**

1. Declare `android.permission.REQUEST_INSTALL_PACKAGES`. Before installation, call `PackageManager.canRequestPackageInstalls()`; this is per-source trust, not a runtime permission dialog. If false, open `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:tv.slink.app`; recheck on return. Handle unavailable settings/installer activities and user refusal. [PackageManager][android-pm], [Settings][android-settings].
2. Expose only the update directory through a small FileProvider subclass, `${applicationId}.updates`, `exported=false`, `grantUriPermissions=true`, and e.g. `<files-path name="updates" path="updates/"/>`. Generate a `content://` URI, launch the foreground installer with APK MIME type and `FLAG_GRANT_READ_URI_PERMISSION`. Never expose the whole files root or send `file://`. Preserve the file until the installer finishes reading it. [FileProvider][android-fileprovider], [SmartTube handoff example][st-install].
3. Installation may replace/kill the app. Persist pending version, and confirm success from the actually installed package version on next launch; merely launching an intent is not success. Keep cancellation/retry distinct from failed download.

**Unattended updates are conditional, not impossible:** `PackageInstaller.SessionParams.setRequireUserAction(USER_ACTION_NOT_REQUIRED)` can support self-updates when the documented conditions hold, including `UPDATE_PACKAGES_WITHOUT_USER_ACTION` and target-SDK requirements (API 31+ on Android 14; API 33+ on Android 15; API 34+ on Android 16). Always handle `STATUS_PENDING_USER_ACTION`. A session-based installer also gives explicit result callbacks and needs no FileProvider handoff. Recommend the user-confirmed flow first; do not promise silent installation merely because “Install unknown apps” is enabled. [Official session conditions][android-session].

## Seeking: source observations and proposed interaction

- **SmartTube:** the default step is 10 seconds. Fast/single-preview paths calculate increasing steps; the implementation multiplies by 1.5 when the elapsed whole-second bucket is exactly one, then resets its timestamp. It is event-driven, without an explicit maximum step in that method; it is not an exact periodic guarantee. Slow carousel mode instead walks storyboard positions. Key-up usually commits; configurable seek-confirm modes retain pending scrubbing for OK. Back cancels except for the accessibility-specific branch. [Defaults][st-prefs], [seek implementation][st-seek].
- **SmartTube previews/tooltips:** storyboard code maps times to image groups/tiles and maintains thumbnail previews. Control tooltips contain action labels; those are separate from timeline timestamps. This YouTube storyboard integration does not establish a Twitch thumbnail source. No elapsed-time input was identified in the inspected presenter/seek paths. [Storyboard][st-storyboard], [tooltips][st-tooltips].
- **Twitch-box:** hidden-control arrows seek immediately and reveal controls. With controls visible but timeline unfocused, its repeat handler starts at 600 ms, permits steps every 600 ms, and changes from 10 seconds to 60 seconds after five seconds held. Focused timeline bypasses that handler. [Remote dispatch][tb-remote], [repeater][tb-repeat].
- **Xtra/Twitch-box timeline:** scrub listeners update the position text and seek on uncanceled scrub stop. No moving timestamp bubble or timestamp thumbnail renderer was found in these inspected paths. Their `MaterialTimePicker` is a **sleep timer**, not a VOD jump dialog. Do not copy it for 25-hour input. [Xtra controls][x-player], [Twitch-box controls][tb-remote].
- **Media3 1.9.3:** default keyboard increments are `duration / 20`; on a 25-hour recording that is **75 minutes per press**. It commits after one second of inactivity, on Enter/Center, or focus loss. An explicit OK/Back interaction must own pending state and consume keys; changing the increment alone does not disable those commits. [Pinned DefaultTimeBar][m3].

**Proposed SlinkTV behavior, to tune on the real remote:**

1. Tap Left/Right: enter a pending seek and move 10 seconds. Hold: begin repeats after 400 ms, at most every 200 ms; step 10 seconds before two seconds held, 30 seconds at 2–4 seconds, 2 minutes at 4–7 seconds, 5 minutes at 7–10 seconds, then cap at 15 minutes. Use monotonic time and a single repeat owner; do not combine OS-repeat and timer increments. Reset acceleration on release/direction change so corrective taps remain precise.
2. Key-up stops movement and keeps the target. OK commits **one** seek; Back cancels it. Pause while choosing and restore the original play/pause intention on either result. Cancel pending state on focus loss/background/media replacement. Clamp against the current seekable window on every update and commit; disable for unknown/unseekable duration. Keep live/growing archive bounds separate from catalog duration.
3. Show a persistent timestamp bubble above the target marker, clamped inside the screen: e.g. `18:42:10 / 25:00:00`, signed delta, current step, and “OK to seek · Back to cancel.” Update without decoding/rebuffering on every repeat. Use elapsed arithmetic and `long` milliseconds; never a time-of-day formatter that wraps at 24 hours. Thumbnail previews can follow later after verifying an actual timestamp-image source.
4. Add a remote-focusable **Go to time** action: hours/minutes/seconds fields plus Go/Cancel; Left/Right changes fields, Up/Down adjusts them, numeric input is optional. Hours range to duration's hours, minutes/seconds to 0–59; support `24:30:00` and display validation for out-of-range input. This provides exact long-distance placement; held seeking provides browsing. Neither relies on a 24-hour clock picker.

**Acceptance checks for implementation:** verify expired/no token, 200/302 asset downloads, cross-origin header stripping, missing digest, interrupted/corrupt APK, signer/package/version mismatch, permission denied/returned, install canceled/successful, and source changes during download. On the TV exercise a 25-hour timeline, all acceleration boundaries, reversal/key-up/lost focus, both ends, OK vs Back, paused playback, and `24:30:00` input. These are required follow-up checks, not results of this research.

[st]: https://github.com/yuliskov/SmartTube/tree/a945135eee6387cd6549b87604ad673944c06b5d
[sm]: https://github.com/yuliskov/SharedModules/tree/5b6b5d1d3ba670e3f89edef1d1394a7562251f2c
[st-beta]: https://github.com/yuliskov/SmartTube/blob/a945135eee6387cd6549b87604ad673944c06b5d/common/src/stbeta/res/values/update_urls.xml
[st-stable]: https://github.com/yuliskov/SmartTube/blob/a945135eee6387cd6549b87604ad673944c06b5d/common/src/ststable/res/values/update_urls.xml
[beta-feed]: https://github.com/yuliskov/SmartTubeNext/releases/download/latest/smarttube_beta2.json
[stable-feed]: https://github.com/yuliskov/SmartTubeNext/releases/download/latest/smarttube_stable2.json
[st-parser]: https://github.com/yuliskov/SharedModules/blob/5b6b5d1d3ba670e3f89edef1d1394a7562251f2c/appupdatechecker2/src/main/java/com/liskovsoft/appupdatechecker2/core/AppVersionChecker.java
[st-checker]: https://github.com/yuliskov/SharedModules/blob/5b6b5d1d3ba670e3f89edef1d1394a7562251f2c/appupdatechecker2/src/main/java/com/liskovsoft/appupdatechecker2/AppUpdateChecker.java
[st-settings]: https://github.com/yuliskov/SharedModules/blob/5b6b5d1d3ba670e3f89edef1d1394a7562251f2c/appupdatechecker2/src/main/java/com/liskovsoft/appupdatechecker2/other/SettingsManager.java
[st-download]: https://github.com/yuliskov/SharedModules/blob/5b6b5d1d3ba670e3f89edef1d1394a7562251f2c/appupdatechecker2/src/main/java/com/liskovsoft/appupdatechecker2/core/AppDownloader.java
[st-presenter]: https://github.com/yuliskov/SmartTube/blob/a945135eee6387cd6549b87604ad673944c06b5d/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/dialogs/AppUpdatePresenter.java
[st-install]: https://github.com/yuliskov/SharedModules/blob/5b6b5d1d3ba670e3f89edef1d1394a7562251f2c/sharedutils/src/main/java/com/liskovsoft/sharedutils/helpers/Helpers.java#L919-L940
[st-manifest]: https://github.com/yuliskov/SharedModules/blob/5b6b5d1d3ba670e3f89edef1d1394a7562251f2c/appupdatechecker2/src/main/AndroidManifest.xml
[st-build]: https://github.com/yuliskov/SmartTube/blob/a945135eee6387cd6549b87604ad673944c06b5d/smarttubetv/build.gradle
[st-ci]: https://github.com/yuliskov/SmartTube/blob/a945135eee6387cd6549b87604ad673944c06b5d/.github/workflows/CI.yml
[gh-releases]: https://docs.github.com/en/rest/releases/releases
[gh-assets]: https://docs.github.com/en/rest/releases/assets#get-a-release-asset
[gh-pat]: https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens
[gh-device]: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow
[gh-scopes]: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps
[android-keystore]: https://developer.android.com/privacy-and-security/keystore
[android-sign]: https://developer.android.com/studio/publish/app-signing
[android-pm]: https://developer.android.com/reference/android/content/pm/PackageManager
[android-apksigner]: https://developer.android.com/tools/apksigner
[android-storage]: https://developer.android.com/training/data-storage/app-specific
[android-settings]: https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES
[android-fileprovider]: https://developer.android.com/reference/androidx/core/content/FileProvider
[android-session]: https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)
[st-prefs]: https://github.com/yuliskov/SmartTube/blob/a945135eee6387cd6549b87604ad673944c06b5d/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/prefs/PlayerData.java#L824
[st-seek]: https://github.com/yuliskov/SmartTube/blob/a945135eee6387cd6549b87604ad673944c06b5d/smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/mod/leanback/playerglue/tweaks/PlaybackTransportRowPresenter.java
[st-storyboard]: https://github.com/yuliskov/SmartTube/blob/a945135eee6387cd6549b87604ad673944c06b5d/smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/previewtimebar/StoryboardManager.java
[st-tooltips]: https://github.com/yuliskov/SmartTube/blob/a945135eee6387cd6549b87604ad673944c06b5d/smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/mod/leanback/playerglue/tooltips/ControlButtonPresenterSelector.java
[tb-remote]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/Media3PlayerFragment.kt
[tb-repeat]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/TvPlayerControls.kt#L133-L166
[x-player]: https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlayerFragment.kt
[m3]: https://github.com/androidx/media/blob/9b8b13d177cf9a3ce997773ca51337acf5acc639/libraries/ui/src/main/java/androidx/media3/ui/DefaultTimeBar.java
