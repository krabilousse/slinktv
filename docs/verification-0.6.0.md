# SlinkTV 0.6.0 verification

Version 0.6.0 / code 8 implements the requested chat simplification, compact placement controls, VOD navigation and discovery changes, and GitHub-backed self-updates. [Changelog](../CHANGELOG.md), [release operation](releases.md), and [primary-source research](update-seeking-research.md) describe the resulting behavior and decisions.

## Tests and captures

The local Java/Robolectric suite exercises chat placement on both sides, compact half-height overlays, settings persistence, Up hiding controls while paused, accelerated seek thresholds, long timecode parsing, offline/relevance selection, ten-channel batches, and existing playback/auth/chat behavior. Updater tests cover private asset redirects, credential scope, errors, custom feeds, checksum/size mismatch, package/signing/version rejection and provider configuration.

The FileProvider URI containment test requires POSIX separators. AndroidX appends `/` when checking canonical file paths; Windows host paths use backslashes. This one test is skipped on Windows and runs in the Linux release workflow. The provider's private manifest/path configuration is still checked on Windows. No production path exception or bypass was introduced for testing.

Native UI captures use synthetic metadata and chat fixtures; they do not establish decoded video, real-time animation performance, or physical remote behavior. They cover compact chat, icon settings, docked controls, Videos layout and the VOD timeline. The existing public Linkus7 host probe in [0.5.0 evidence](evidence/vod-0.5.0-host.json) established archive playlist and distant-segment access; no new playback-engine change was required for this release.

Local release validation passed: 124 Android host tests, two deliberate skips (POSIX FileProvider behavior and the opt-in private-release network probe), 29 Python tests, APK signing and ZIP alignment, and lint with zero errors / 85 warnings. [JUnit evidence](evidence/jvm-tests-0.6.0.json), [lint](evidence/lint-0.6.0.json), [signing compatibility](evidence/apk-0.6.0-signature.txt), and [LAN checksum](evidence/download-0.6.0.json) record these results. The published CI artifact may have different bytes from the local build; the final delivery verification uses the published artifact.

Native captures: [chat settings](evidence/chat-settings-0.6.0.png), [compact chat](evidence/chat-messages-0.6.0.png), [VOD seek tooltip](evidence/vod-seek-tooltip-0.6.0.png), [Videos wall](evidence/videos-wall-0.6.0.png), and [docked controls](evidence/player-docked-0.6.0.png).

## Installation and remaining device checks

The release build retains the prototype's signing identity so the update can replace the installed app without clearing Twitch login/settings. APK downloads are hash-checked; Android still confirms and validates installation. The release pipeline must finish successfully before the tagged release is reported as verified.

No ADB device is connected. On the Xiaomi, verify corner placement with the menu hidden/visible, remote hold acceleration and corrective taps, the timecode keyboard, signed-in ten-channel listings, and the Android installer permission/confirmation flow. The private update source requires a user-configured repository read token. No token has been provisioned on the TV. The existing 4 KB native-page compatibility limitation remains unchanged.
