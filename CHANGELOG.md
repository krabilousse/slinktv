# Changelog

## [0.6.1] - 2026-11-08

- The source repository is now public. Update checks and downloads from the default GitHub source work without a credential, including automatic checks on app opening.
- The GitHub credential is now optional and only needed for private custom repositories.
- The update screen describes the selected channel: Stable installs stable releases only, Preview also includes prereleases.

## [0.6.0] - 2026-11-08

- Remove persistent chat headings, channel/status labels and the 7TV footer. Login and actionable errors remain visible when needed.
- Add compact chat width, half/full overlay height, four corners, left/right docking and an icon-based settings panel.
- Rename Go live to Catch up to live. Up immediately hides player controls, including while paused.
- Accelerate held VOD seeking, add a moving timestamp bubble, a Seek action and exact MM:SS / HH:MM:SS entry, including recordings longer than 24 hours.
- Load the latest archive from ten offline followed channels, prioritizing observed offline transitions and enabled local viewing relevance. Load more adds ten channels; the channel picker still reaches every follow.
- Add configurable GitHub/HTTPS update sources, encrypted private-repository credentials, stable/preview channels and foreground update checks.
- Verify APK size, SHA-256, package, version and installed signing identity before Android's installation confirmation.
- Add tagged release builds, release notes, checksums and update manifests.

## [0.5.0] - 2026-11-08

- Add followed-channel VOD browsing, offline filtering, channel archives, resume positions and remote seeking.
- Replace player text buttons with compact icon controls.

## [0.4.0] - 2026-11-08

- Add read-only Twitch chat, animated Twitch/7TV emotes, docked and transparent overlay modes.

## [0.3.1] - 2026-11-08

- Add on-card stream loading, landscape-only quality selection, readable quality names and a default-quality setting.
- Refine TV navigation, feed/sort pills and button spacing.

## [0.3.0] - 2026-11-08

- Add the Stream Wall, guest discovery, followed streams, search and local relevance ordering.

## [0.2.0] - 2026-11-08

- Add phone QR login and followed-channel API access.

## [0.1.0] - 2026-11-08

- Establish standalone Streamlink extraction and embedded Media3 playback on Android TV.
