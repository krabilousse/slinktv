# Native chat implementation

The player owns presentation and lifecycle. `ChatPanel` owns the read-only panel, saved placement/opacity, account consent state, and emote resources. `TwitchChat` owns one EventSub connection (temporarily two during a server-directed handoff). All authenticated HTTP calls go through the existing serialized account worker; chat never owns or logs tokens. `ChatLog` applies deduplication and moderation before rendering. `ChatEmotes` loads public 7TV metadata and bounded images; `ChatAdapter` owns row spans and visible animation lifetimes.

The implementation follows the existing [prototype/research](chat-research.md): Docked / Overlay / Hidden, Show/Hide restoring the previous mode, opacity applied only to the panel background, animated WebP/GIF, proportional wide emotes, and zero-width composition. It preserves the player's existing Back-to-wall behavior outside dialogs. No chat messages are sent.

New device authorizations request `user:read:follows user:read:chat`; old follow-only sessions remain valid. Enable chat initiates QR reauthorization without clearing the current login. Cancel or denied authorization preserves the previous session. Chat HTTP 403 errors must not sign the user out of the rest of the app.

Connection interruptions clear the transcript because moderation events may have been missed. A server-directed reconnect transfers subscriptions and deduplicates overlapping notifications. An ordinary disconnect recreates all four subscriptions. Public 7TV global/channel sets refresh on opening and periodically while visible; set updates use full snapshots rather than an assumed EventAPI replay facility.

## Verification — 0.4.0, 2026-11-08

- `scripts/build.ps1`: `testDebugUnitTest`, `assembleDebug`, `lintDebug`, and `assembleDebugAndroidTest` passed. [99 JVM/Robolectric tests](evidence/jvm-tests-0.4.0.json), zero failures or skips. UI behavior runs against Android 14 and 15; native image decoding and captures run against Android 14 graphics. Lint has no errors; [warnings](evidence/lint-0.4.0.json) remain recorded.
- `.venv/Scripts/python -m pytest tests -q`: all 22 existing Python resolver/directory tests passed.
- Chat tests cover duplicate/replayed messages, bounded history, deletion-before-delivery, shared-chat source deletions, out-of-order user/room clears, server-directed handoffs, ordinary disconnects, stale callbacks, missing keepalives, revocation, and stopped connections. Auth tests cover preserving an existing login when extra authorization is cancelled and keeping the account after a chat HTTP 403.
- Rendering tests decode real animated WebP, preserve wide aspect ratios, resolve aliases, compose zero-width layers, and stop animations on detach/hide/recycle. The animation budget test exercises more than 12 drawables. UI tests cover placement without recreating Media3, saved opacity/text/animation preferences, QR-upgrade entry, long OK without pausing, and Back closing chat options first.
- Native captures: [docked controls](evidence/chat-docked-0.4.0.png), [overlay with controls](evidence/chat-overlay-0.4.0.png), and [chat messages/emotes](evidence/chat-messages-0.4.0.png). These are Robolectric renders with offline fixture messages and no decoded video, not photographs from the TV. Fixture emotes are under test resources only; attribution accompanies them.
- [Public live probes](evidence/chat-0.4.0-host.json) received an EventSub welcome with a 30-second keepalive, retrieved 45 global and 384 Linkus7 channel emotes, and fetched animated/wide WebP assets from 7TV's CDN. Run `.venv/Scripts/python scripts/verify-chat-services.py` to reproduce. No OAuth token, subscription session ID, or viewer messages are saved by this probe.
- [Packaged APK inspection](evidence/apk-0.4.0-inspection.json): version 0.4.0/code 6, minimum API 34, target 36, ARM32/ARM64/x86_64. APK signature and ZIP alignment checks passed. The [full LAN download](evidence/download-0.4.0.json) matched the packaged SHA-256 `95ae9faac09449ba1f2f573c44a730d80d8b82f0a55cc6e966de4ae589720579` (85,622,279 bytes).

## Device check still needed

No authorized ADB device was connected. Live subscription creation/receipt using the owner's newly consented token, actual Xiaomi remote behavior, simultaneous playback/animation performance, and real network interruptions have not been exercised on the physical TV. The earlier login/playback approvals do not grant the new chat scope.

Install the update, open Linkus7, select **Enable chat**, and approve the QR consent. Check new chat messages and animated emotes, switch Docked/Overlay/Hidden, change overlay opacity, hold OK from hidden controls, and return to the wall with Back. Guest video and old follow-only sessions should still work before approval. Cancelling the QR prompt should preserve Following.

## Intentional limits

Chat is read-only and requires the official `user:read:chat` authorization. There is no guest IRC fallback, message sending, scrollback, BTTV/FFZ support, or full Twitch badge-image catalog. HOST/MOD/VIP are shown as compact text markers. 7TV metadata refreshes every five minutes while visible rather than subscribing to its EventAPI; new/removed emotes may take that long to appear. Chat is displayed on receipt and is not delayed to match HLS latency.

The transcript is capped at 100 messages, deduplication/moderation indexes at 512 entries, and message emotes at 12 images with at most four layers per composition. Two image workers have a bounded queue; encoded memory cache is 8 MiB and HTTP disk cache 64 MiB. Images have response/dimension limits and unavailable images retain their names. Only 12 visible native animations run simultaneously; other images remain still. Hiding/backgrounding stops the connection and animation work.

OkHttp 5.3.2 is pinned for compatibility with compile SDK 36; newer 5.5.1 requires SDK 37. OkHttp/Okio/Kotlin license texts ship with the existing notices. This release retains the existing 4 KB-native-page limitation in the Python XML dependencies; chat does not change that limitation.

Current contracts checked against [Twitch EventSub WebSockets](https://dev.twitch.tv/docs/eventsub/handling-websocket-events/), [chat event authorization](https://dev.twitch.tv/docs/eventsub/eventsub-subscription-types/#channelchatmessage), [moderation events](https://dev.twitch.tv/docs/chat/moderation/), and [Android ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder). The original [research](chat-research.md) records the 7TV metadata and composition sources.
