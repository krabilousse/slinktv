# Twitch-box versus SlinkTV: playback, features, and feasibility

Checked 2026-11-08 against Twitch-box commit [`23bcb074a546c1c891d652d21ac4f6e0960894c0`](https://github.com/w1lli4m666-droid/Twitch-box/tree/23bcb074a546c1c891d652d21ac4f6e0960894c0) and SlinkTV's current 0.3.0 source. This is source research plus two host-side playlist probes. Twitch-box was not installed or run. No SlinkTV app code or APK changed.

## Main conclusions

- Both use the Media3/ExoPlayer family for their main video path. Twitch-box resolves Twitch URLs directly in Kotlin; SlinkTV delegates resolution to embedded Streamlink/Python.
- Twitch-box is an Xtra-derived application with substantially more browsing, chat, saved-content, and player features. Its Android TV adjustments retain a phone UI; SlinkTV is a smaller TV-only live-stream wall.
- Its held-arrow seeking does not implement a rolling DVR. Normal live playback seeks within the available HLS timeline; a separate download feature must not be confused with automatic recording.
- Its follow-change refresh mechanism is local app state invalidation. It is practical to adopt in SlinkTV, but it does not discover changes made on another device or automatically track stream status.
- Public VOD playback can retain SlinkTV's existing engine: installed Streamlink 8.6.3 successfully resolved a public Linkus7 VOD in this research. The native input, catalog, timeline UI, and resume behavior still need implementation.

See the [detailed UI/follow-refresh report](twitch-box-ui-refresh-research.md) for the 47 pinned source citations behind those areas.

## 1. How playback works

| Stage | Twitch-box | SlinkTV 0.3.0 |
| --- | --- | --- |
| Resolve a channel | Kotlin `PlayerRepository` calls Twitch web GraphQL for a playback signature/token, then constructs a Twitch Usher HLS URL. | `resolver.py` calls Streamlink's Twitch plugin inside Chaquopy. Streamlink internally performs Twitch-specific access-token and playlist work. |
| Playlist passed to player | Usually the multivariant/master playlist, containing the available qualities. | An individual quality's media playlist URL, extracted and sorted by Streamlink. |
| Play video | Default path is `ExoPlayerFragment` with `ExoPlayerService`; both use AndroidX Media3 classes. An alternative MediaSession service and platform MediaPlayer path also exist. | Embedded Media3 ExoPlayer in `PlayerActivity`, with foreground-only lifetime. |
| Quality | Auto plus manual track selection, dedicated audio-only/chat-only modes, remembered settings. | Highest rendition initially; explicit rendition switching. An audio rendition may appear in the generic quality list if Streamlink returns it; there is no dedicated audio/chat player mode. |
| Twitch HLS behavior | Custom parser/tracker/data sources handle Twitch prefetch tags, playlist ad markers, configurable live offset, and optional proxy routing. | Standard Media3 HLS path. Streamlink resolves URLs but does not download the playback segments, so its own segment filtering/low-latency reader is not active. |

Sources: [live URL construction][tb-resolve], [default player selection][tb-player-select], [quality selection][tb-quality], [custom live source][tb-live-source], [Twitch HLS parser][tb-parser], local [resolver](../app/src/main/python/resolver.py), [native player](../app/src/main/java/tv/slink/app/PlayerActivity.java), and [Streamlink 8.6.3 Twitch implementation][sl-twitch]. No Streamlink dependency or invocation was found in Twitch-box's app/build source searches.

Twitch-box requests H.264/H.265/AV1 capabilities and configures a 2,000 ms target live offset by default in its ExoPlayer path. Its parser recognizes Twitch-specific prefetch entries. These are source-level capabilities, **not a measured latency advantage** or a guarantee that a particular TV can decode every requested codec. Media3's support for Apple low-latency HLS does not by itself prove support for Twitch's distinct prefetch extension. [Live settings][tb-live-source], [parser][tb-parser], [Media3 HLS support](https://developer.android.com/media/media3/exoplayer/hls).

Neither implementation is simply playing a Twitch iframe. Both depend on Twitch's playback infrastructure outside a documented Helix stream-URL API. Replacing Streamlink with direct Kotlin would transfer maintenance of extraction and integrity-related changes into SlinkTV; it would not make the upstream endpoint official. Keeping Streamlink while improving the native player is a viable approach.

## 2. UI/UX and major feature differences

| Area | Twitch-box | SlinkTV today |
| --- | --- | --- |
| Navigation | Browse, Popular, Following, Saved; category and channel drill-down; Following includes Games, Live, Videos, Channels. | Home/Search/Settings; Following or Popular in a three-column wall. |
| Loading | Paged grids with progress indicators and initial focus selection. | Fixed grey card placeholders; existing cards retained during refresh. |
| Content | Live streams, channel/category videos, clips, bookmarks, downloads, offline playback paths. | Public live streams; account diagnostics; independent player test. |
| Favorites | Local channel/category follows without an account, plus optional Twitch-account follows. | Twitch-account followed streams; no local favorite membership store or follow/unfollow button. |
| Ranking | Multiple catalog filters/sorts; followed live loader sorts by viewers. | Viewer count or optional account-scoped local watch-time relevance. No equivalent relevance reranker was identified in the inspected Twitch-box followed-live path. |
| Chat | Native chat and Twitch/7TV/BTTV/FFZ emote loading, animation lifecycle, overlay emote metadata. | Research and HTML prototype only. |
| Player | Seeking, playback speed, more audio/subtitle controls, configurable buttons, optional mini-player and download actions. | Pause, Go live, Quality, Retry, Channels; no timeline scrubber, playback speed UI, or mini-player. |
| Back in player | Hides visible controls first; subsequent Back closes or minimizes according to settings. | Back closes the quality dialog if open, otherwise immediately returns to the wall. |
| Platform scope | Xtra phone UI retained with TV defaults and focus/navigation adaptations; legacy Android compatibility is an explicit project goal. | Android TV 14+ only; touch-first compatibility is not a UI goal. |

These rows summarize the [source-linked feature matrix](twitch-box-ui-refresh-research.md). See also [Android TV design notes][tb-tv] and [player Back implementation][tb-back]. Feature breadth does not establish comparative stability, performance, accessibility, or polish; those need device testing. The default mini-player is disabled on TV.

## 3. What “proxy-aware network access” means

A proxy is an intermediary network endpoint. The phrase covers multiple behaviors in this repository, rather than a required companion server:

1. **Honor the Android system proxy.** TV defaults choose OkHttp. The source explains that Cronet/HttpEngine may take a direct/QUIC path that bypasses some TV-box HTTP proxies. The default OkHttp builder does not install a competing proxy selector. [TV network default][tb-proxy-default], [OkHttp construction][tb-http-client].
2. **Allow separately configured HTTP proxies for playback stages.** Host/port and optional proxy credentials can be supplied. Options select the playback-access-token request, master playlist, and media-playlist routing. Media playlists can switch to the configured proxy after ad markers are detected. Routing is host-selective; the relevant custom selectors can fall back to direct connections. It is not equivalent to a VPN or a guarantee that every segment/request is proxied. [Token request][tb-resolve], [playlist selectors][tb-proxy-routing], [ad-triggered switching][tb-proxy-trigger], [request dispatch][tb-proxy-dispatch].
3. **Accept a custom playlist provider URL.** An optional URL template substitutes the channel login and supplies a playlist instead of the regular resolver path. It defaults off, and no provider URL is bundled as a requirement. [Settings][tb-proxy-settings], [custom URL handling][tb-custom-url].

The top-level connectivity helper also accepts an Internet-capable/connected network without requiring Android's validation flag. However, some player error/retry branches still test that flag, so this is not proof of universal proxy/VPN reliability. [Connectivity helper][tb-connectivity], [player retry checks][tb-retry-network].

SlinkTV has no equivalent proxy settings or deliberate cross-stack policy. Its Python requests, Java Helix HTTP calls, and Media3 HTTP data source are separate. If proxy support is wanted, it should configure and test all three consistently; merely adding a UI field would not do that. A standalone app can use the TV's existing network proxy, but a user-selected remote playlist provider would introduce a service dependency.

## 4. Event-driven refreshes

**Yes, this can be implemented in SlinkTV without a backend.** Twitch-box increments process-local membership revisions after successful changes. Local channel changes affect Live/Channels; account channel changes also affect account-followed Videos; game changes affect Games. Retained list state invalidates dependent pages instead of fetching everything on each visit. [Channel revisions][tb-follow-revisions], [Live ViewModel][tb-live-refresh].

This is not Twitch EventSub. It does not automatically discover a follow made on the phone, a followed broadcaster going live, viewer-count changes, or a new VOD. The Live tab retains its consumed revision in its ViewModel; other tabs have a possible view-recreation gap described in the [detailed lifecycle analysis](twitch-box-ui-refresh-research.md).

For SlinkTV, use a retained catalog state with account/query keys, membership revisions, stale/dirty state, and focus identity. Publish local mutations after persistence; reload only affected lists. Retain cards through loading/errors. Separately reconcile external account state on deliberate foreground/staleness/manual refresh boundaries, and refresh live metadata while the wall is visible. Keep token-validation scheduling separate from catalog freshness. This is a proposed design, not a code change.

Local favorites would be straightforward to add. Twitch-account follow/unfollow parity requires separate consideration: official follow-write endpoints were removed, while Twitch-box uses web GraphQL mutations. The existing SlinkTV read-follows token does not add those writes. Followed-game browsing and aggregated followed-channel VODs are also new content features, not outcomes of adding refresh signals. [Official endpoint removal](https://discuss.dev.twitch.com/t/deprecation-of-create-and-delete-follows-api-endpoints/32351), [account mutation source][tb-follow-write].

Official online/offline EventSub can supplement this later, but it still needs user-token WebSocket subscriptions, reconnect/resync logic, and cost budgeting. Current limits include total subscription cost 10 per client/user tuple; following a broadcaster does not mean that broadcaster authorized the application. `channel.follow` is a broadcaster/moderator event, not a viewer-wide feed of changes to their followed list. [EventSub types](https://dev.twitch.tv/docs/eventsub/eventsub-subscription-types/#stream-online), [cost model](https://dev.twitch.tv/docs/eventsub/manage-subscriptions/#subscription-limits), [WebSocket lifecycle](https://dev.twitch.tv/docs/eventsub/handling-websocket-events/).

## 5. Twitch VODs

**Catalog and playback are separate.** Official `GET /helix/videos` returns published-video metadata by broadcaster, video ID, or game, with app/user-token authorization. Its `url` is a Twitch video page, not the HLS media URL. It can support SlinkTV's signed-in channel-video list without a new scope. A followed-video feed would aggregate videos from followed broadcasters, cache them, and merge by publication time; there is no equivalent single official followed-VOD feed in the inspected reference. [Twitch Get Videos](https://dev.twitch.tv/docs/api/reference/#get-videos).

Twitch-box's channel catalog supports GraphQL/persisted-query paths and authenticated Helix fallback. Playback requests a video-specific access token/signature, constructs a Usher VOD playlist URL, and passes HLS to ExoPlayer. It saves/resumes positions and supports quality, seek, speed, and downloaded playback through additional app code. [Channel catalog][tb-video-catalog], [VOD resolver][tb-vod-resolve], [VOD playback/resume][tb-vod-play], [position checkpointing][tb-position-save].

Streamlink 8.6.3 already supports Twitch VOD URLs. SlinkTV currently blocks them in `channel_name()` and then constructs a live-channel URL; this is an application restriction. [Local resolver](../app/src/main/python/resolver.py), [Streamlink Twitch plugin][sl-twitch], [plugin capabilities](https://streamlink.github.io/plugins.html#twitch).

**Host proof:** on 2026-11-08, installed Streamlink 8.6.3 resolved public Linkus7 video `2920342167`. It returned audio-only and 160p30/360p30/480p30/720p60/1080p60. The selected media playlist returned HTTP 200, an end marker, 9,041 segments, and approximately 90,402.551 seconds of listed media. No video segments were downloaded and no native playback/seek was exercised. [Recorded VOD probe](evidence/twitch-box-vod-observation.json).

Suggested first VOD increment: accept validated Twitch video IDs/URLs; add channel Videos using Helix for signed-in browsing; reuse Streamlink extraction; add a true VOD media type so LIVE/Go live/watch-history behavior is correct; add timeline seek, resume storage, and position-preserving quality changes. A currently growing archive needs deliberate start/resume/live-edge behavior. Subscriber-only, deleted, expired, integrity-gated, or unavailable VODs must remain explicit errors; today's Helix follows token is not automatically a playback entitlement.

## 6. Held-arrow seeking and live caching

The source implements the advertised timing: repeated Android key-down events start producing seek deltas at 600 ms, no faster than once per 600 ms; deltas are 10 seconds until the hold reaches five seconds, then 60 seconds. The handler passes the updated position to the player's regular seek method. There is no separate video-buffering pipeline in this shortcut. [Repeat timing][tb-seek-repeat], [seek dispatch][tb-seek-dispatch], [ExoPlayer calls][tb-seek-player].

Two interaction details matter: with controls hidden, the initial Left/Right press already calls a normal seek and shows controls; the 600 ms threshold applies to repeated steps. When the progress bar has focus, it handles arrows itself. The custom absolute seek clamps the lower bound to zero but does not implement an upper bound or special live-DVR logic. [Remote handler][tb-remote], [seek dispatch][tb-seek-dispatch].

**Normal live viewing is not automatically recorded to disk.** The inspected playback paths do not configure `SimpleCache`, `CacheDataSource`, an OkHttp disk cache, or `setBackBuffer`. They configure normal forward buffering (defaults: 15 s minimum / 50 s maximum targets, 2 s startup/rebuffer) and load HLS through network data sources. Those targets do not enlarge the server playlist or describe retained rewind history. Default Media3 back-buffer duration is zero. [Load control][tb-load-control], [network HLS source][tb-live-source], [Media3 default](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl#DEFAULT_BACK_BUFFER_DURATION_MS).

The custom playlist tracker adopts the latest playlist while preserving time/discontinuity alignment; it does not append all old segments into a growing archive. Its custom parser's Twitch prefetch entries add near-live data, not historical DVR. [Tracker][tb-tracker], [parser][tb-parser]. Twitch-box separately implements explicit live-stream downloads; that is a saved-content workflow and is not invoked by the remote seek shortcut. [Stream download service][tb-download-stream].

For HLS live playback, `seekTo` operates within the current moving live window. A segment may be reused if buffered, or fetched again from the CDN if available. Once old media leaves the exposed timeline, a normal seek cannot recover an hour of prior broadcast. Forward seeking can only catch up to available content. [Media3 live seeking](https://developer.android.com/media/media3/exoplayer/live-streaming#seeking-in-live-streams).

**Observed example, not a Twitch guarantee:** a Linkus7 1080p60 playlist fetched through SlinkTV's resolver contained 14 completed segments totaling 28 seconds, two separate prefetch entries, and no end marker. This was one host-side playlist observation; it was not a measured rewind test in either Android app. Other channels/times/playlist configurations may expose different windows. [Live playlist evidence](evidence/twitch-box-live-window-observation.json).

For SlinkTV there are three different scopes:

| Capability | Required work | Limit |
| --- | --- | --- |
| Seek within the available live window | Remote hold handling, timeline/seekability checks, bounded seeking, live-offset display, Go live. | Cannot exceed the window Twitch exposes. No disk cache required. |
| Resume earlier in a broadcaster's growing VOD | Resolve the archive, map timestamps, expose an archive timeline. | Only if an accessible archive exists; archive publication can lag live. |
| Guaranteed rewind of locally watched content | A bounded segment store plus retained/rebuilt manifest and timeline, quota/eviction/discontinuity/quality handling. | Only captures content while recording; simple HTTP caching alone does not create a DVR timeline. |

The first option fits the current Media3 player. The third is a separate DVR feature and should not be implied by adding a 600 ms key gesture. At a hypothetical constant 6 Mbit/s, retaining 30 minutes of compressed media costs about 1.35 GB before overhead; that is sizing arithmetic, not a measured Twitch bitrate.

## Suggested SlinkTV priorities

Keep the chosen Stream Wall and existing engine. Add retained catalog freshness and targeted refresh first, then public VOD browsing/playback with resume and TV seeking. Native chat is the largest visible gap relative to Twitch-box and already has a separate SlinkTV research/prototype track. Add optional adaptive quality by passing a master playlist and using track selection. Treat small live-window rewind, full DVR, local favorites, and proxy settings as distinct product features with separate acceptance checks.

These are recommendations; this research makes no native implementation changes. The repository is AGPL-3.0, and no upstream application code was copied into SlinkTV.

[tb-resolve]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/repository/PlayerRepository.kt#L85-L113
[tb-player-select]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/main/MainActivity.kt#L1032-L1059
[tb-quality]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/ExoPlayerService.kt#L1252-L1367
[tb-live-source]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/ExoPlayerService.kt#L971-L1008
[tb-parser]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/player/lowlatency/HlsPlaylistParser.java#L1129-L1153
[sl-twitch]: https://github.com/streamlink/streamlink/blob/8.6.3/src/streamlink/plugins/twitch.py
[tb-tv]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/ANDROID_TV.md#L1-L55
[tb-back]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlayerFragment.kt#L138-L151
[tb-proxy-default]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/main/MainActivity.kt#L638-L681
[tb-http-client]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/XtraModule.kt#L89-L100
[tb-proxy-routing]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/ExoPlayerService.kt#L920-L969
[tb-proxy-trigger]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/ExoPlayerService.kt#L232-L273
[tb-proxy-dispatch]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/player/lowlatency/OkHttpDataSource.java#L275-L282
[tb-proxy-settings]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/res/xml/root_preferences.xml#L79-L104
[tb-custom-url]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/ExoPlayerService.kt#L736-L768
[tb-connectivity]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/util/ContextExtensions.kt#L41-L48
[tb-retry-network]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/ExoPlayerService.kt#L287-L331
[tb-follow-revisions]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/repository/LocalChannelFollowsRepository.kt#L20-L56
[tb-live-refresh]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/following/streams/FollowedStreamsViewModel.kt#L26-L66
[tb-follow-write]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/channel/ChannelPagerViewModel.kt#L274-L359
[tb-video-catalog]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/repository/datasource/ChannelVideosDataSource.kt#L35-L94
[tb-vod-resolve]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/repository/PlayerRepository.kt#L400-L462
[tb-vod-play]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/ExoPlayerService.kt#L1015-L1072
[tb-position-save]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlaybackService.kt#L107-L130
[tb-seek-repeat]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/TvPlayerControls.kt#L133-L166
[tb-seek-dispatch]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlayerFragment.kt#L277-L284
[tb-seek-player]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/ExoPlayerFragment.kt#L314-L339
[tb-remote]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlayerFragment.kt#L182-L265
[tb-load-control]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/ExoPlayerService.kt#L559-L574
[tb-tracker]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/player/lowlatency/DefaultHlsPlaylistTracker.java#L452-L487
[tb-download-stream]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/download/StreamDownloadService.kt#L200-L380
