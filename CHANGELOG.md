# Changelog

## [0.10.1] - 2026-11-11

- When videos arrive above the focused card during a refresh, a button in the Videos header shows how many, for example "↑ 11 new · press Back". Press Back, or select the button, to jump to the first card.
- While the button is showing, the first Back jumps to the top and the second leaves Videos. The button goes away once you move above the new videos.

## [0.10.0] - 2026-11-11

- Videos now checks every offline followed channel, eight at a time, instead of one at a time for the first ten. "Load more" is gone from the main view; "Older videos" remains inside a channel.
- Cards no longer move while you navigate them. Once a card is focused, new results are added in one step when the refresh is complete. The status line shows the progress.
- After a Twitch rate limit, the refresh waits and resumes by itself.

## [0.9.0] - 2026-11-09

- The ad-free proxy now handles ads that Twitch still inserts into a live stream. During the break, the player shows a backup stream until the break ends, as Xtra does. It tries the same playlist through Xtra's HTTP proxy first, then a fresh Android session at up to 360p.
- If no backup stream is ad-free, the player hides and mutes the ad until live video returns.
- The ad-free proxy dialog explains what happens when an ad still gets through.

## [0.8.0] - 2026-11-08

- Add an optional ad-free proxy (Settings → Ad-free proxy), off by default. It fetches live stream playlists through the same community servers as Xtra; video still comes from Twitch.
- A dead server is skipped after 4 seconds and the last working server is tried first. If no server answers within 12 seconds, playback starts directly from Twitch with a notice.
- Edit the server list in the same dialog, one per line, or restore the defaults.

## [0.7.0] - 2026-11-08

- Shorten the README to a feature summary and install guide, with the app logo.
- Remove research notes and HTML design prototypes from the repository.
- Each release also publishes `SlinkTV.apk`, so `releases/latest/download/SlinkTV.apk` always downloads the newest stable version.

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
