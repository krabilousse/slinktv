# SlinkTV 0.6.0 verification

Version 0.6.0 / code 8 implements the requested chat simplification, compact placement controls, VOD navigation and discovery changes, and GitHub-backed self-updates. [Changelog](../CHANGELOG.md), [release operation](releases.md), and [primary-source research](update-seeking-research.md) describe the resulting behavior and decisions.

## Tests and captures

The local Java/Robolectric suite exercises chat placement on both sides, compact half-height overlays, settings persistence, Up hiding controls while paused, accelerated seek thresholds, long timecode parsing, offline/relevance selection, ten-channel batches, and existing playback/auth/chat behavior. Updater tests cover private asset redirects, credential scope, errors, custom feeds, checksum/size mismatch, package/signing/version rejection and provider configuration.

The FileProvider URI containment test requires POSIX separators. AndroidX appends `/` when checking canonical file paths; Windows host paths use backslashes. This one test is skipped on Windows and runs in the Linux release workflow. The provider's private manifest/path configuration is still checked on Windows. No production path exception or bypass was introduced for testing.

Native UI captures use synthetic metadata and chat fixtures; they do not establish decoded video, real-time animation performance, or physical remote behavior. They cover compact chat, icon settings, docked controls, Videos layout and the VOD timeline. The existing public Linkus7 host probe in [0.5.0 evidence](evidence/vod-0.5.0-host.json) established archive playlist and distant-segment access; no new playback-engine change was required for this release.

Local release validation passed: 124 Android host tests, two deliberate skips (POSIX FileProvider behavior and the opt-in private-release network probe), 29 Python tests, APK signing and ZIP alignment, and lint with zero errors / 85 warnings. [JUnit evidence](evidence/jvm-tests-0.6.0.json), [lint](evidence/lint-0.6.0.json), [signing compatibility](evidence/apk-0.6.0-signature.txt), and [LAN checksum](evidence/download-0.6.0.json) record these results. The final artifact and download receipts refer to the published CI APK.

The [tagged GitHub workflow](https://github.com/krabilousse/slinktv/actions/runs/42487571515) completed successfully at commit `4215b4ce337083e1873014e73a9ee9836dec797a`: 125 Android host tests passed on Linux (including FileProvider containment); only the opt-in private network probe was skipped in that run. Python's 29 tests, release assembly, lint, signature checks and publication also passed. [CI evidence](evidence/ci-0.6.0.json).

After publication, the opt-in probe ran separately with a temporary process-scoped GitHub credential. The **actual Java UpdateClient** fetched the private release manifest and APK through GitHub's authenticated asset API and verified the complete download against the manifest. It recorded no credential or signed URL. [Private-download evidence](evidence/private-update-0.6.0.json). This validates the updater's network/download path, not Android's installer UI or Keystore on the Xiaomi.

The [published release](https://github.com/krabilousse/slinktv/releases/tag/v0.6.0) and all commits are authored by `krabilousse`, with no AI attribution. The universal APK is 82,474,179 bytes; SHA-256 is `1b85aa48e651ad57f874c0adca8efd41c2e214cd5be21c948304763cf6abb732`. The release manifest, downloaded GitHub asset and LAN `app.apk` have matching bytes. [Release evidence](evidence/release-0.6.0.json). The initial CI setup failure and cancelled pre-publication run published no APK; the v0.6.0 tag was finalized before successful publication.

Native captures: [chat settings](evidence/chat-settings-0.6.0.png), [compact chat](evidence/chat-messages-0.6.0.png), [VOD seek tooltip](evidence/vod-seek-tooltip-0.6.0.png), [Videos wall](evidence/videos-wall-0.6.0.png), and [docked controls](evidence/player-docked-0.6.0.png).

## Installation and remaining device checks

The release build retains the prototype's signing identity so the update can replace the installed app without clearing Twitch login/settings. APK downloads are hash-checked; Android still confirms and validates installation.

No ADB device is connected. On the Xiaomi, verify corner placement with the menu hidden/visible, remote hold acceleration and corrective taps, the timecode keyboard, signed-in ten-channel listings, and the Android installer permission/confirmation flow. The private update source requires a user-configured repository read token. No token has been provisioned on the TV. The existing 4 KB native-page compatibility limitation remains unchanged.
