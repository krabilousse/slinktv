# SlinkTV

Standalone Twitch player for Android TV 14+ (API 34 minimum, API 36 target). Streamlink 8.6.3 runs inside the APK through Chaquopy; embedded AndroidX Media3 1.9.3 plays the resolved HLS stream. No server, VLC installation, or Python installation is needed on the TV. Public streams and guest discovery work without Twitch login.

## Install and try

The built, signed release APK is `dist/SlinkTV-0.6.0.apk` (also `dist/app.apk` for the local download server). It includes `armeabi-v7a`, `arm64-v8a`, and `x86_64`. Version 0.6.0 adds compact corner chat, icon-based chat settings, immediate Up-to-hide, accelerated VOD seeking and time entry, ten-channel archive batches, and in-app updates. Install over the earlier version to retain the saved login and player settings.

1. On the home network, open `http://192.168.1.218:8765/app.apk?v=0.6.0` in Downloader. The PC must remain awake for this download. USB/file transfer also works.
2. Allow installation from Downloader when Android asks, then install the APK.
3. Open **SlinkTV**. Guests see **Popular** in the system language; signed-in viewers see **Following**, most viewers first. The **Following / Popular** pill switches feeds directly. **Most viewers / Relevant** selects followed-stream order. The separate language button selects another language or all languages. More pages load as you scroll.
4. Select a stream card; an animated indicator appears on that card while it resolves. Back cancels opening. Playback starts at the saved default quality, initially **Best available**. Portrait renditions are excluded. Thumbnails, avatars, names, viewer counts, and live badges share fixed card geometry with the grey loading placeholders.
5. D-pad / OK reveals the player controls with **Pause** focused. The first press only reveals controls; another press acts. **Up** hides the controls immediately, including while paused. The focused icon’s label appears below the controls. **Quality** selects a lower rendition. **Catch up to live**, **Retry**, and **Channels** are available. Back returns to the wall; Back inside the quality dialog closes that dialog first.

**Search** supports live channel/category matching when signed in. Guests can look up an exact channel login, such as `Linkus7`; **Open channel** also accepts direct entry. **Settings → Player test** plays an independent recorded HLS sample. **Retry** reloads the current URL; return to the wall and reopen a stream to obtain a fresh URL.

**Following → Relevant** offers optional ranking by time watched on this TV, weighted toward recent viewing. History is account-specific, disabled until enabled, and excludes paused/buffering/background time. **Settings** provides history on/off, clearing, language, and sign-out. Signing out clears that account's local history. This does not read Twitch's viewing history or reproduce its recommendation algorithm.

**Settings → Default quality** selects Best available or a resolution preference. The player uses the highest resolution at or below that preference, preferring higher frame rates. If none is available below it, the lowest available video resolution is used. Changing quality inside the player affects only that playback session. Quality labels include resolution, frame rate when supplied, and `(best)` on the highest video quality.

For an already authorized ADB connection:

```powershell
adb install -r dist/SlinkTV-0.6.0.apk
adb shell am start -n tv.slink.app/.MainActivity
```

## Phone login

1. Choose **Sign in** in the top bar or **Settings → Sign in with QR**. SlinkTV uses the owner's Public Client ID; no secret is needed.
2. Scan the TV's QR code with a phone, sign in on Twitch, and approve **read followed channels and chat**. Alternatively open `twitch.tv/activate` on the phone and enter the displayed code. Existing follow-only logins remain valid; choose **Enable chat** inside the player to grant the additional permission.
3. The TV polls automatically and returns to the wall with live followed channels. Followed-channel and followed-stream lists fetch every page before reporting completion.
4. **Settings → Account diagnostics → Raw JSON** retains the prototype's validation/scopes, profile, followed-channel, and live-followed responses. Access tokens, refresh tokens, and token-exchange responses are excluded.
5. **Refresh** reloads the data. **Settings → Sign out** clears local credentials, history, and account data, then attempts to revoke the token at Twitch.

Sessions are encrypted with an Android Keystore AES-GCM key. One process-owned account worker serializes refresh, token-pair persistence, and authenticated browsing. Validation occurs on restore and at least hourly while the app is foregrounded. Pending QR login pauses when backgrounded. The entire flow runs on the TV and phone without a companion server. The PC HTTP server only transfers the APK.

The Helix login token is not passed to Streamlink or the guest directory. It does not unlock subscriber-only video or ad-free playback.

## Videos

**Videos**, beside Home, lists archived broadcasts from followed channels when signed in. Signed-out viewers get a sign-in action. No additional Twitch permissions are needed beyond the existing login.

- The recent wall starts with the latest archive from **ten offline followed channels**. Recently observed offline transitions come first, then enabled local viewing relevance; otherwise follow order is used. Unknown live status is never treated as offline. Twitch does not supply a last-offline timestamp through this API.
- **Channels** searches the full followed-channel list, including channels not yet scanned. Channels with known recent videos appear first. Select one for its archives, then **Older videos** for additional pages. Hold OK on a video card to open that channel's videos. Back from a channel list returns to the recent feed.
- **Load more** checks the next ten offline channels. There is no delay between channel responses and only the latest archive is requested per channel. **Refresh** returns to ten channels and reconciles follows/current live status. The wall is a selected feed, not an exhaustive Twitch archive listing.
- Cards show channel, title, age, duration, current channel status, and local resume progress. Thumbnail placeholders retain card geometry. Opening uses an animated indicator on the selected card.
- Playback starts at the saved position, or zero. **Start over** resets playback. Quality changes preserve position and paused state. **Retry** resolves a fresh VOD URL before reloading. Video viewing does not count toward live-channel relevance history.
- Left/Right from hidden controls or the **Seek** action focuses the progress bar. A timestamp bubble follows the target. Taps move ten seconds; holds repeat after 600 ms, at most every 120 ms, with larger duration-aware steps after 2, 5 and 8 seconds. **OK confirms**, **Back cancels**, and **Up cancels and hides**. Releasing the arrow leaves the target pending. **Jump to time** accepts MM:SS or HH:MM:SS, including hours above 24. The separate rewind/forward icons move ten seconds immediately.
- Positions are saved per account on this TV, capped at 200 entries, and cleared on logout or **Settings → Video resume positions → Clear**. The live EventSub chat is disabled for VODs; historical chat replay and seek-thumbnail previews are not included.

The catalog uses official Helix with SlinkTV's login. Streamlink resolves public playback separately, without receiving that login token. Restricted, deleted, expired, and integrity-gated videos may not play. See [implementation and verification](docs/videos-player-0.5.0.md) and [Xtra/Twitch-box research](docs/vod-implementation-research.md).

## Stream chat

Open a live stream and choose **Enable chat** if prompted. Approve the new QR authorization on your phone; cancelling retains the existing account. Chat is read-only and requires sign-in. Video playback continues to work without chat authorization.

- **Show chat / Hide chat** restores the last visible placement. Hold **OK** for 600 ms from the video while controls are hidden to toggle chat; **Yellow** or keyboard **C** also toggles. A short OK press still reveals controls without pausing.
- **Chat options** (or Menu) opens a settings panel with icons. Choose **Docked**, **Overlay**, or **Hidden**; left/right docking or any overlay corner; Compact/Wide width; Half/Full overlay height; background opacity; text size; and animated emotes. Overlay defaults to compact half-height. Chat headings and the 7TV footer are removed. Settings persist, and docking keeps the same player.
- Twitch emotes and global/channel 7TV emotes load on demand. Animated WebP/GIF, wide images, aliases, and zero-width layering are supported. At most 12 animations run simultaneously. Large or unavailable assets fall back to static images or names.
- The visible transcript contains up to 100 messages. Deleted messages, timeouts, and chat clears are applied. Hidden/background chat disconnects and clears its transcript; reopening receives new messages. An ordinary reconnect also clears messages because moderation may have been missed.
- 7TV sets refresh on opening and every five minutes while chat is visible. New messages are displayed immediately on receipt, without a video-delay offset. Sending messages, scrollback, full Twitch badge images, BTTV, and FFZ are not included.

## App updates

**Settings → App updates** checks for a newer version, downloads it, verifies its checksum and signing identity, and opens Android's installer. Automatic checks run on app opening with a six-hour cooldown. Installation requires the TV's confirmation and may first require allowing SlinkTV to install unknown apps.

The default source is the private [krabilousse/slinktv repository](https://github.com/krabilousse/slinktv). Set a fine-grained GitHub token with **Contents: read** for this repository in the masked **GitHub credential** field; no credential is bundled. It is stored encrypted on the TV. You can choose another GitHub repository or an HTTPS update manifest, and Stable/Preview releases. [Setup and release workflow](docs/releases.md) explains private access, signing, tags, changelogs and feed format. [Changes](CHANGELOG.md) lists releases.

## Verification and limits

See [0.6.0 verification](docs/verification-0.6.0.md), [0.5.0 Videos and player verification](docs/videos-player-0.5.0.md), [0.4.0 chat implementation and verification](docs/chat-implementation.md), [0.3.1 controls and quality verification](docs/ui-controls-0.3.1.md), [Stream Wall verification](docs/phase3-verification.md), [phase 2 verification](docs/phase2-verification.md), and [phase 1 verification](docs/verification.md). The owner confirmed playback and QR login on the Xiaomi box in earlier versions. Chat has host connection, parsing, rendering, lifecycle, and TV-control tests. Live authenticated chat and animation performance on the Xiaomi still need physical-device verification after approving the new chat scope. Authenticated host tests use simulated responses; real-token renewal has not been independently exercised.

This is a prototype, not a Play Store release or a guarantee that every Twitch stream is accessible:

- **4 KB page devices only for this build.** Bundled libxml2/libxslt dependencies are not ready for native 16 KB devices. Android 14+ is the OS requirement; broad newer-device compatibility remains open until these libraries are rebuilt/replaced and tested.
- Guest discovery uses the **unofficial Twitch web GraphQL directory**, following the approach inspected in Xtra and reusing Streamlink's existing public web client identifier. The first page worked in live host probes; loading another page hit Twitch's integrity check. The app retains existing cards and offers Sign in on this response. Signed-in discovery/search uses official Helix with SlinkTV's own Client ID and supports pagination. Guest search currently requires an exact login. The unofficial interface can change or stop working.
- Public live/VOD H.264/AAC playback and native read-only live chat; no clips, subscription-only playback, or HEVC/AV1 enablement. UI labels remain English; system language selects the Popular stream filter.
- Streamlink resolves channels and qualities. Media3 downloads HLS segments directly, so Streamlink's ad filtering and Twitch-specific low-latency reader are **not** active. Ads may play normally.
- Twitch may require a Chromium integrity challenge. The desktop browser path is disabled on Android; these failures are reported rather than silently falling back to an external service.
- Fixed quality per Twitch rendition. Change quality manually if bandwidth is insufficient. Backgrounding stops/relinquishes the player; returning starts at the current live edge. Reopen the channel when a signed URL expires.
- The pinned Android XML dependencies are old upstream builds. Update/rebuild and audit them before production distribution.

## Build

Requirements: JDK 17, Android SDK platform 36 / build-tools 35.0.0, Python 3.11, and network access for dependencies. Gradle Wrapper 8.13 is included with its distribution checksum. Python dependencies are pinned in `app/python-requirements.txt`.

On Windows, the optional project-local bootstrap avoids changing system toolchain settings:

```powershell
python scripts/bootstrap.py
powershell -ExecutionPolicy Bypass -File scripts/setup-sdk.ps1
powershell -ExecutionPolicy Bypass -File scripts/build.ps1
```

The SDK setup script accepts Android SDK licenses. Read them before running it if you have not already accepted them. Downloads/build tools live in ignored `.tools/`. `scripts/build.ps1` runs the activity regression tests, builds the app, runs lint, and builds the instrumentation test APK.

With your own installed tools, set `JAVA_HOME`, `ANDROID_HOME` (or `local.properties`), and optionally `SLINK_BUILD_PYTHON` to the Python 3.11 executable, then run:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest
```

App output: `app/build/outputs/apk/debug/app-debug.apk`.

Host tests (Python 3.11 or newer):

```powershell
python -m venv .venv
.venv/Scripts/python -m pip install streamlink==8.6.3 pytest
.venv/Scripts/python -m pytest tests -q
.venv/Scripts/python scripts/verify-stream.py Linkus7
```

The live check requires the channel to be online. It checks playlists and media containers, not decoded frames. It never persists signed stream URLs. For a connected TV/emulator, run `connectedDebugAndroidTest` to check the embedded Python native imports and a decoded HLS video frame. Then perform the remote/audio/lifecycle checks in the verification document.

## Code and research

The original [HTML design prototype](docs/prototypes/slinktv-ui-prototype.html) compares Cinema, Channel guide, and Stream Wall. The chosen Stream Wall is now implemented natively. [UI/API research](docs/ui-ux-research.md) records discovery, search, Xtra/Frosty, and local relevance decisions.

[Chat research](docs/chat-research.md) and the [offline chat prototype](docs/prototypes/chat-ui-prototype.html) informed the native chat implementation. The HTML remains a simulated design reference; open it with arrows, Enter, Escape, and C. Rebuild with `python docs/prototypes/chat-build.py` (Pillow required).

- `MainActivity.java`, `StreamWallAdapter.java`: TV wall, placeholders, discovery/search/settings, asynchronous extraction, and playback entry.
- `directory.py`: anonymous popular-stream paging and exact channel lookup through the unofficial web directory.
- `resolver.py`: input validation, actual Streamlink Twitch plugin, HLS variants and restricted request headers. No account credentials are stored.
- `PlayerActivity.java`, `PlayerChrome.java`, `VodTimeBar.java`: embedded Media3, compact icon controls, pending VOD seeks, errors, and foreground lifecycle.
- `VideosScreen.java`, `VideoLibrary.java`, `VideoCards.java`, `VideoItem.java`: followed-channel archive browsing, bounded discovery, and pagination.
- `VideoProgress.java`: account-scoped local resume positions.
- `ChatPanel.java`, `ChatAdapter.java`: saved chat placement, consent, bounded transcript rendering, and animation lifetimes.
- `TwitchChat.java`, `ChatLog.java`: EventSub connection/reconnection, deduplication, and moderation.
- `ChatEmotes.java`, `ChatEmote.java`, `ChatText.java`, `ChatEmoteSpan.java`: public emote metadata, caches, native decoding, and proportional composition.
- `LoginActivity.java`: phone QR login, followed/live lists, raw API response dialogs.
- `AuthViewModel.java`: polling, lifecycle, validation, refresh, session ownership, and sign-out.
- `SlinkApplication.java`: shared account controller and app-wide foreground lifecycle.
- `WatchHistory.java`: local, account-scoped viewing aggregates and relevance ranking.
- `TwitchApi.java`: public-client device flow and paginated Helix requests.
- `EncryptedSessionStore.java`: encrypted, atomic token-pair storage.
- `TvUi.java`: shared focus styling and TV sizing.

[Playback research](docs/research.md) covers Streamlink, Stremio core, Xtra, Jellyfin Android TV, SmartTube, and player selection. [Phase 2 research](docs/phase2-research.md) covers Twitch's public-client device flow, pagination, refresh, and the actual Xtra/Frosty implementations.

Upstream projects were studied; Xtra/Stremio application code was not copied. Third-party license texts ship in the APK's **Licenses** screen and `app/src/main/assets/THIRD_PARTY_NOTICES.txt`.
