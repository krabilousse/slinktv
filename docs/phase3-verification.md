# Stream Wall 0.3.0 verification

Built and checked on 2026-11-08. Package `tv.slink.app`, version name `0.3.0`, version code `4`, minimum API 34, target API 36. This is a debug-signed prototype update, with the existing debug signing identity.

## Delivered behavior

- Native three-column Stream Wall, two complete rows at the tested 960×540 dp TV layout. Thumbnail, profile image, streamer name, game, viewer count, and red live indicator. Loading uses fixed grey thumbnail/avatar/text placeholders with exactly the same card dimensions; image arrival never resizes cards. Refresh retains existing cards until replacement data arrives.
- Guests see real popular streams, filtered by system language by default, with language selection and an all-languages option. The guest integration is experimental Twitch web GraphQL, using the public web identifier already in the pinned Streamlink engine. It sends no user access token or client secret.
- Signed-in viewers see all fetched live follows, most viewers first. Browse switches between Following and Popular. Search uses Helix's live channel/category matching plus stream/avatar enrichment. Guest search supports exact channel logins; manual channel entry remains available.
- Optional Relevant ordering uses actual playback duration on this TV, per Twitch account. Daily aggregates expire after 90 days, contribution is capped at 120 minutes per channel/day, and recency has a 14-day half-life. Pause, buffering, stopped/background playback, and screen-off time do not count. Settings allows disabling/clearing history; sign-out clears it and invalidates pending tracker writes. Ad time is not separately identified by this Media3 integration.
- Selecting a card resolves with embedded Streamlink and opens embedded Media3 at the highest available rendition. Player controls include Pause, Go live, Quality, Retry, and Channels. The first D-pad/OK press reveals controls and focuses Pause without pausing; media play/pause keys act immediately. Back closes a quality dialog first, otherwise returns to the wall. The selected channel is used to restore wall focus after layout.
- QR login, logout, and raw account diagnostics remain available in Settings. Main, login, and player share one process-owned account controller to serialize token refresh and authenticated requests. A catalog HTTP 400 does not discard the login; rejected refresh credentials do.
- Native UI labels and subtitles are functional. No promotional headings were added. UI language is English; the system language affects stream filtering.

## Actual checks

| Check | Result and scope |
| --- | --- |
| `scripts/build.ps1` | `testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest`: BUILD SUCCESSFUL. |
| Java/Robolectric | **55 tests, 0 failures, 0 errors**, covering account lifecycle/rotation, API paging, player remote/Back behavior, history accounting/reset, card geometry, two complete TV rows, guest integrity-error retention/sign-in action, and search enrichment. Activity suites exercise API 34 and 35. [Summary](evidence/jvm-tests-0.3.0.json). |
| Python | **20 passed** with `.venv/Scripts/python.exe -m pytest tests -q`; resolver plus guest normalization, language/cursor forwarding, ordering, exact lookup, and integrity-error classification. |
| Android lint | **0 errors, 31 warnings**. Warnings remain, including localization/string-resource work. This is not a warning-free production build. |
| Live guest first pages | English 28, French 28, German 30 real streams with viewer counts and assets. [Evidence](evidence/guest-directory-0.3.0.json). |
| Live guest continuation and exact lookup | Another English first page returned 28; continuation returned `IntegrityCheckFailed`. Exact Linkus7 lookup returned its live stream. The app preserves loaded cards, stops automatic paging, and offers Sign in for this response. [Evidence](evidence/guest-pagination-0.3.0.json). |
| Chat HTML | Final offline HTML: 212,093 characters. Previewed at 728 px, content height 669, no console errors. A separate temporary assertion preview passed **18 checks**, including dock/overlay geometry, opacity, hide/restore, focus, emote composition, bounded messages, and moderation simulation. |
| APK signature/archive | APK signature v2 verified, `zipalign -c -P 16 4` passed, ZIP CRC audit passed. Embedded Python archives, notices, three ABIs, and app DEX present. [Inspection](evidence/apk-0.3.0-inspection.json), [manifest badging](evidence/apk-0.3.0-badging.txt). |
| LAN download | Fetched the entire served APK and compared SHA-256 with the packaged artifact: identical. [Download evidence](evidence/download-0.3.0.json). |

The host tests use synthetic authenticated responses and Android simulation. They do not establish successful fresh QR consent, token refresh against Twitch, decoded video/audio, hardware remote navigation, image decode performance, or native chat rendering on this new APK.

## APK

- `dist/SlinkTV-0.3.0-debug.apk`, also `dist/app.apk`.
- 84,232,304 bytes, approximately 80.3 MiB.
- SHA-256: `08f8a8be79875496343bd6407bd9629221d0ab1fe543358f0e9571800f58637f`.
- Downloader URL: `http://192.168.1.218:8765/app.apk?v=0.3.0`.
- The PC download server is only for installation; the app runs standalone afterward.
- `dist/SlinkTV-0.3.0-tests.apk` is the instrumentation companion, not the app to install normally. Instrumentation was built but not run against a device in this phase.

## Remaining limits and TV acceptance

Guest directory access is unofficial. Twitch may reject even the first page in another environment. Current evidence supports a first-page guest experience, with sign-in for continued browsing when required. Signed-in Helix pagination is implemented and host-tested, but not tested with the owner's live token in this phase. Neither sorting mode claims Twitch recommendation parity.

The prior native-library constraint remains: some bundled XML dependencies have 4 KB ELF alignment. Successful APK ZIP alignment does not establish native 16 KB page support. This build targets the previously working Mi Box; broad compatibility with newer 16 KB Android devices remains unverified and requires replacing/rebuilding those dependencies.

The owner verified earlier playback and QR-login versions. For this update, install over 0.2.0 and check:

1. Saved login restores; Following shows images, avatars, live counts, and correct viewer order. D-pad reaches cards, navigation, and actions with no clipped second row.
2. Selecting Linkus7 opens the highest rendition. Revealing controls does not pause; Quality changes rendition; Back returns to the same channel on the wall. Home/background stops playback as before.
3. Relevant can be enabled; watching one followed channel changes its local ranking. Clear and sign-out reset it. Most viewers remains available.
4. Guest Popular uses the selected/system language, keeps grey geometry while loading, and retains cards if More requires sign-in. Search finds an exact live login as guest and supports broader matching when signed in.
5. QR sign-in from the wall and logout work with the shared account controller, including cancellation and returning from the phone flow.

Chat remains a research/UI prototype: [research](chat-research.md), [interactive HTML](prototypes/chat-ui-prototype.html). Native Media3 overlays and Android animated WebP/GIF are feasible; actual TV performance, EventSub consent/reconnect behavior, animated AVIF, and asset distribution rights remain open. Official chat reading needs new `user:read:chat` consent. The current follows-only token does not grant it.
