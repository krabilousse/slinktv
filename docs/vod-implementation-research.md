# Videos implementation research

Checked **2026-11-08**, after SlinkTV **0.4.0**. Research only: no application edits, upstream execution, playback requests, credentials, or signed media URLs. Read the existing [comparison](twitch-box-comparison.md) and [UI research](twitch-box-ui-refresh-research.md) first; their SlinkTV 0.3.0/chat observations are historical.

**Recommendation:** add **Home · Videos · Search · Settings**, with an account-followed archive feed, a prominent channel filter, and a “Channels offline now” filter. Keep Helix catalog requests separate from anonymous Streamlink playback resolution. Reuse the existing Java Media3 player with explicit VOD identity, position preservation, and remote scrubbing. This is feasible on the TV without a server; full chronological coverage of hundreds of follows is a separate fetching problem.

## Evidence and versions

- Local `.tools/research/twitch-box` is clean at [`23bcb074a546c1c891d652d21ac4f6e0960894c0`](https://github.com/w1lli4m666-droid/Twitch-box/tree/23bcb074a546c1c891d652d21ac4f6e0960894c0). Read-only `git ls-remote` returned the same upstream HEAD; no fetch, checkout, or worktree changes.
- No separate Xtra checkout was found under `.tools/research`. Read current Xtra source remotely at the HEAD returned by `ls-remote`: [`0ab697de04799365f590f91e2dfbefc092c3ad5a`](https://github.com/crackededed/Xtra/tree/0ab697de04799365f590f91e2dfbefc092c3ad5a). Its player structure has diverged from the fork; TV-specific claims below refer to Twitch-box.
- Playback dependency inspected: Streamlink **8.6.3**, [`a685512a746a99a429b2678e2718c1ee85ba9f6c`][sl]. SlinkTV declares Media3 **1.9.3** in [app/build.gradle](../app/build.gradle); Twitch-box declares **1.11.0**. Both time-bar versions were checked at their release commits. Official API documentation links are living pages, checked on the date above.
- Previous [host VOD probe](evidence/twitch-box-vod-observation.json) demonstrated playlist resolution, not Android playback or remote seeking. No new runtime parity claim is made here.

## What upstream actually does

### Followed videos and channels

**Followed videos are server-aggregated GraphQL, not Helix fan-out.** Twitch-box calls `user.followedVideos` with sort, broadcast types, page size, and cursor. Paging uses 30 items initially/per page, prefetch distance 3, the last edge cursor, and `hasNextPage`. It preserves server order rather than merging per-channel lists. The loader first tries its normal query; the persisted `FollowedVideos_CurrentUser` fallback is available only for **ARCHIVE + TIME**, and both paths require a GraphQL credential. There is no Helix or local-follow fallback in this datasource. Current Xtra has the same aggregation/fallback structure. [Query][tb-query], [loader][tb-feed], [ViewModel][tb-feed-vm], [Xtra loader][x-feed].

Following/Videos defaults to **time + all types** unless saved settings override it; options map to time/views and all/archive/highlight/upload. Period and language controls are hidden here; selecting a filter applies immediately. Thus default “all” cannot use the archive-only persisted fallback. Normal-query cards use `createdAt`; persisted-query cards map `publishedAt` into the local creation-date property. Do not assume these timestamps are interchangeable. [Defaults][tb-feed-vm], [dialog][tb-filter], [mapping][tb-feed].

**Following/Channels is different.** Its default is last broadcast descending; alternatives are follow date and login, ascending/descending. It combines local follows with the first account page, enriches missing last-broadcast/avatar data via GraphQL `users` in groups of 100, and sorts that initial combined list. Subsequent pages are enriched and appended without globally re-sorting all loaded channels. The normal account query requests 100 follows; fallback paths are persisted GraphQL then Helix. “Last broadcast” comes from `lastBroadcast.startedAt`, not the latest available archive. A broadcast need not have a retained public VOD. This is not evidence of a globally sorted offline-VOD feed. [Datasource][tb-channels], [sort defaults][tb-channel-sort].

### Resolution, archives, and growing recordings

Twitch-box's `loadVideoPlaylistUrl` obtains `videoPlaybackAccessToken` through GraphQL, falling back between persisted and normal query forms. It constructs an Usher `/vod/v2/{id}.m3u8` master URL with `sig`/`token`, source/audio/unavailable flags and codec choices. Its Media3 service loads that HLS source and explicitly seeks to a stored position or zero. Current Xtra uses the same basic route, with `platform=web` / `playerType=site` in its token requests. [Twitch-box resolver][tb-resolver], [service][tb-play], [Xtra resolver][x-resolver].

Streamlink 8.6.3 also uses GraphQL playback access and Usher `/vod/v2/{id}.m3u8`, passing `nauthsig`/`nauth`. It parses variants, checks their accessibility, and sets `force_restart=True` for VODs, including recordings still growing; a URL time offset becomes an HLS stream option. **SlinkTV uses `stream.to_url()`, not Streamlink's segment reader: those start/restart options do not configure Media3.** Java must explicitly select the initial position. This inference follows the plugin and local resolver paths. [Streamlink][sl], [SlinkTV resolver](../app/src/main/python/resolver.py).

Twitch-box also has a fallback that derives rendition paths from an animated-preview/storyboard URL. It is undocumented CDN-path inference, not an official entitlement mechanism or seek-preview implementation. Do not port it for this increment. Keep restricted/deleted/unavailable/integrity-gated playback as explicit failures. [Fallback helper][tb-fallback].

### Remote/timeline behavior, precisely

| Interaction | Observed Twitch-box behavior |
| --- | --- |
| Hidden controls, initial Left/Right | Immediately calls rewind/fast-forward and reveals controls. This first seek does **not** wait 600 ms. |
| Controls visible, focus outside timeline | Initial arrows navigate controls. Repeated Left/Right can seek: first eligible repeat after 600 ms, at most one step per 600 ms; 10-second deltas until the hold reaches five seconds, then 60-second deltas. These seek immediately. Key-up ends repetition. |
| Timeline focused | Left/Right bypass the custom repeater and go to `DefaultTimeBar`. Preview changes the displayed timestamp; the app calls `seek(position)` only on an uncanceled scrub stop. |
| Timeline defaults | Media3's default key increment is duration/20; no override was found in the fork. Key scrubbing commits on Center/Enter, one second without another increment, or loss of focus. Touch release commits; touch cancel cancels. These defaults were verified for both Media3 versions. |
| Back | Hides controls and clears focus first; subsequent Back closes the TV player by default. Clearing a scrubbing time bar's focus can commit it: **Back is not a reliable cancel operation** in this implementation. |
| Bounds | The custom immediate-seek helper clamps only to zero before dispatch; do not reproduce the missing explicit upper bound. |

Sources: [remote dispatch][tb-remote], [repeat timing][tb-repeat], [time-bar listener][tb-scrub], [layout][tb-layout], [Media3 1.11.0 time bar][m3-new], [Media3 1.9.3 time bar][m3-old], [Back][tb-back]. These are source observations, not measurements on a TV remote. Current Xtra also has timestamp-only scrub callbacks with commit on uncanceled stop. [Xtra player][x-player].

No timestamp-indexed thumbnail renderer was found in the inspected Twitch-box player/layout paths. Its scrub callback changes text; card images and animated previews are separate. A phone double-tap can toggle chat, not universally seek. Do not advertise seek thumbnails or assume touch gestures are TV requirements. [Scrub callback][tb-scrub], [double tap][tb-touch].

Bookmarks are local video references with metadata/thumbnail, not timestamp bookmarks or downloads. Resume is a separate `video_positions` table keyed by numeric video ID. The Media3 service saves roughly every 30 seconds while playing non-stream media, and on teardown; video-position use defaults enabled. Cards can display saved progress. [Bookmark entity][tb-bookmark], [position entity][tb-position], [timer][tb-timer], [persistence][tb-save], [restore][tb-play], [card updates][tb-cards].

## Official catalog boundary

- **Get Followed Channels:** matching user token with `user:read:follows`; cursor pages up to 100; order is follow date, not activity. No last-broadcast field. [Reference][helix-follows].
- **Get Videos:** app/user token; no additional scope. One broadcaster `user_id` per query; up to 100 **video IDs** is a different mode. User-video queries support `type=archive`, `sort=time` (creation descending), period filters and cursor pages up to 100. Metadata includes creation/publication dates, duration, thumbnail, page URL and archive `stream_id`; no HLS URL or completion flag. [Reference][helix-videos].
- **Get Streams:** accepts up to 100 broadcaster IDs, returns live streams and their IDs. Set `first=100`; missing channels are offline only relative to a successful current lookup. It supplies no offline broadcast history. [Reference][helix-streams].

**SlinkTV rule:** its own Public OAuth client and follows/chat consent remain unchanged. Never send that session token to GraphQL, Streamlink, a media CDN, or a third-party resolver. Public video metadata still requires Helix authorization; it is not an unauthenticated API. Anonymous playback extraction is a separate existing dependency, not an official Twitch playback API.

## Proposed bounded feed strategy

The following values are initial implementation choices, not upstream defaults or a performance benchmark.

1. **Membership:** reuse `TwitchApi.Snapshot.followed()` and account ID. Reconcile follows on explicit Refresh or after 15 minutes when foregrounded; preserve prior membership if pagination fails. Do not call full `AuthViewModel.refreshData()` per VOD batch. Keep catalog generation separate from credential validation/rotation.
2. **Channel discovery:** request `videos?user_id={id}&type=archive&sort=time&period=all&first=5`. Using all time keeps infrequent broadcasters discoverable. Merge by video ID, order by parsed `created_at` descending with video-ID tie-breaking. Store `published_at` separately. Do not infer category from what the channel is streaming now.
3. **Bound each scan:** first wave 24 channels; at most 60 channel-video requests per tab visit, including retries. Four workers maximum; start at most four video requests/second. Publish after the first eight completions, then coalesce updates. Pause background scan work when Videos is not visible. “Check more channels” explicitly grants another 60-request batch.
4. **Fairness:** allocate at most half the batch to recently watched/recently-offline channels; reserve at least half for a persisted round-robin queue of the remaining follows. Advance the queue after an attempt, including empty results and failures; back off failed channels instead of repeatedly blocking the queue. Refreshes and tab revisits must not restart at the same favored IDs. Unscanned channels take precedence over rescanning fresh entries. Persist the queue position per account and membership revision.
5. **Find one offline streamer immediately:** channel picker searches the entire cached follow membership, including unscanned channels. Selecting a channel bypasses discovery order and fetches its first 20 archives; subsequent explicit pages use that channel's cursor. This is the escape hatch when the broad feed is incomplete. Default feed includes all followed channels; the optional offline filter means “Channels offline now,” not “completed recordings.”
6. **Live status:** reuse a complete, recently fetched followed-live snapshot if available. Otherwise refresh relevant visible/queued IDs in groups of at most 100, at most once/minute while browsing. Unknown/stale/failed lookups remain unknown, not offline. Matching a video's nonempty `stream_id` to the current channel stream ID is a useful **recording-in-progress inference**, not a completeness guarantee. A live channel can also have older completed archives.
7. **Cache:** account-scoped metadata, timestamps, successful/empty/error status and per-channel cursors. Start with 15-minute freshness for successful pages, 30 minutes for empty pages, 60 seconds for known growing recordings, and retain stale metadata up to seven days. Bound storage to 5,000 video records with LRU eviction; keep per-channel discovery status separately and distinguish “evicted” from “checked and empty.” Keep bookmarks/resume independent of catalog eviction. Cache no signed playback URLs.
8. **Older pages:** feed “Check more channels” and “Older videos” are separate operations. After first-page coverage, older-page work gets a 20-request budget and the same fair rotation; merge the results locally. Keep cursors per broadcaster/filter, reset them on refresh/filter change, detect repeated cursors and deduplicate IDs. A channel-specific page is the reliable way to explore its full available history.

**Completeness contract:** display “Recent videos found · 60 of 500 channels checked,” with stale/failed counts as needed. A sorted partial union is not the newest videos across every follow. Even checking five per channel can omit a sixth video that is newer than another channel's first. Pages can also change during a scan. Never label this “all latest broadcasts,” and do not globally re-order cards beneath the focused remote selection; offer “New videos found” to apply a newly sorted snapshot.

If strict top-N is later required, first obtain a head page from **every** channel, then merge per-channel ordered streams, fetching another page whenever its exhausted frontier could precede the next output item. That removes the simple truncation error under a stable snapshot assumption, but cannot make Twitch's concurrent pagination an atomic snapshot. It costs at least one video request per followed broadcaster and is not the initial feature.

Share a Helix request budget with existing account/catalog/chat work. Preserve `Ratelimit-Limit`, `Remaining`, `Reset` and retry information in `TwitchApi.Reply` (currently only status/body/retry-after). Pause scanning near exhaustion; on 429 honor server reset/retry timing, on transient errors back off with jitter, and coalesce authentication recovery. Do not hard-code a guaranteed 800-request allowance. [Official rate-limit behavior][rate].

On logout/account change, invalidate in-flight generations and remove account-specific feed/resume visibility. A failure must retain known cards with Retry; zero follows, no archives found so far, incomplete scan, and network failure are different states.

## Implementation choices for the existing Java player

**Small, explicit seams:** add a video metadata model and a retained VOD catalog owner beside existing live catalog state. Extend [TwitchApi.java](../app/src/main/java/tv/slink/app/TwitchApi.java) through the existing account request boundary. Add an explicit video resolver entry accepting a validated numeric video ID and constructing the canonical Twitch video URL internally. Do not weaken `channel_name()` into accepting arbitrary URLs. [MainActivity.java](../app/src/main/java/tv/slink/app/MainActivity.java) currently launches only stream cards; [PlayerActivity.java](../app/src/main/java/tv/slink/app/PlayerActivity.java) currently recreates a media item and starts playing on quality changes.

Pass a playback kind (`LIVE`, `VOD`, existing sample), video ID, broadcaster ID, title and account identity independently of the temporary resolved variants. Use long millisecond positions. Helix duration is a display hint; the loaded Media3 timeline owns actual seek bounds.

- **Completed VOD:** prepare at zero or saved position; label controls Replay/Restart rather than Go live. Preserve position, selected quality, and play/pause intention across quality changes, retry and Activity recreation. Use `setMediaItem(item, positionMs)` plus restored `playWhenReady`, rather than the current unconditional `play()` path.
- **Growing VOD:** explicitly start at zero/resume, never Media3's implicit live edge. Observe timeline changes and update the available end; preserve the user's chosen offset while the archive grows. Seek only within the exposed window. If its origin moves, map through the window start timestamp when available; otherwise report the unavailable earlier position instead of silently resuming at the wrong content. Do not infer recording completion solely from channel offline status or `isCurrentMediaItemLive()` becoming false. [Media3 window semantics][m3-live].
- **Seek bounds:** require available seek command, a nonempty seekable timeline, and a known positive duration; handle `C.TIME_UNSET` explicitly. Clamp candidate positions on every step/commit after timeline changes. All player access stays on its application thread. [Player contract][m3-player].
- **Recommended TV interaction:** hidden Left/Right reveals the timeline and starts a **pending** target with a 10-second step. Hold threshold/repeat cadence 600 ms; after five seconds use 60-second steps. Repeated input changes only the pending target. Center commits once; Back cancels the target, then hides controls on the next press, then leaves the player. Key-up stops repetition without committing; Up/Down cancel pending scrubbing before moving focus. Preserve prior play/pause intention; pause during the pending interaction and restore it on commit/cancel. Display “OK to seek · Back to cancel.” This deliberately improves on upstream's immediate repeated seeks and prevents unnecessary network seek churn.
- **Implementation caution:** the standard `DefaultTimeBar` commits on timeout/focus loss. An explicit OK/Back design needs SlinkTV-owned pending state and key handling; do not let its default key callback also seek. A smaller alternative is standard time-bar behavior with `setKeyTimeIncrement(10_000)`, but then describe and test auto-commit honestly. Never use duration/20 for long archives. [1.9.3 implementation][m3-old].
- **Resume:** store account + video ID → position, update time and completion state. Save every 15 seconds while playing and on pause/stop/committed seek/quality replacement; never persist a pending target. Offer Resume/Start over. Mark completion only for a known ended archive, e.g. within the final 30 seconds; never mark a growing recording finished at its temporary edge. Clear/reset on explicit Start over.
- **Bookmarks/thumbnails:** defer bookmarks if needed; resume is the more useful first increment. A later bookmark can save metadata by video ID independently of resume. Ship a timestamp scrub preview first. Helix's cover thumbnail is not a seek image; adding real previews needs an independently verified timestamp-to-image source and bounded image cache, not fabricated CDN paths or extra video decoding during every key repeat.
- **Chat:** do not attach the existing live EventSub chat to a historical VOD as though it were replay chat. Disable it for VODs with a clear explanation; synchronized historical chat is a separate feature and must not reuse the Helix token on GraphQL.

No local DVR or segment cache is needed for ordinary archive seeking. Live rewind remains bounded by the server's moving timeline; retaining HTTP responses alone does not create a rewindable manifest. [Media3 live seeking][m3-live].

## UI and acceptance boundary

Videos remains visible when signed out and shows “Sign in to see videos from channels you follow,” with a focused Sign in action and no previous-account cards. Signed in: archive cards show channel, title, recorded date, duration and resume progress; channel filter and Refresh are reachable with the remote. Retain focus by video ID when returning from playback. Give offline channels equal discoverability even when Home contains only live streams.

Before claiming parity, verify on the TV: completed and growing public VOD start/resume; >10-hour timestamps; tap/599-ms/600-ms/five-second hold and key-up; target bounds; OK/Back/focus-loss behavior; paused quality changes; retry after expired media URL; background/recreation; unavailable/subscriber-only VOD; account switch mid-scan; 500-follow fairness, partial failures, pagination and 429; no live chat presented as replay. Existing host resolution evidence does not substitute for these checks. No such device tests were run for this document.

## Behavior reuse and copying

Implement these interaction/data-flow choices in SlinkTV's own Java code. This report contains no copied application implementation. Twitch-box and current Xtra carry **AGPL-3.0** licenses. If actual source is copied or adapted, retain notices, mark modifications, and satisfy the applicable whole-work licensing and Corresponding Source obligations when distributing the APK; moving Kotlin logic into Java does not remove those obligations. A standalone app is not exempt merely because it has no server. Check file-specific notices and bundled assets separately. [Twitch-box license][tb-license], [Xtra license and sections 4–6][x-license].

[tb-query]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/graphql/UserFollowedVideos.graphql
[tb-feed]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/repository/datasource/FollowedVideosDataSource.kt#L22-L126
[tb-feed-vm]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/following/videos/FollowedVideosViewModel.kt#L62-L110
[tb-filter]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/common/VideosSortDialog.kt#L83-L111
[tb-channels]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/repository/datasource/FollowedChannelsDataSource.kt#L30-L299
[tb-channel-sort]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/following/channels/FollowedChannelsSortDialog.kt#L62-L88
[tb-resolver]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/repository/PlayerRepository.kt#L400-L462
[tb-play]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/ExoPlayerService.kt#L1014-L1073
[tb-fallback]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/util/TwitchApiHelper.kt#L297-L315
[tb-remote]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/Media3PlayerFragment.kt#L189-L290
[tb-repeat]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/TvPlayerControls.kt#L133-L166
[tb-scrub]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/Media3PlayerFragment.kt#L746-L766
[tb-layout]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/res/layout/player_layout.xml#L475-L481
[tb-back]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/Media3PlayerFragment.kt#L143-L156
[tb-touch]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/Media3PlayerFragment.kt#L436-L446
[tb-bookmark]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/model/ui/Bookmark.kt
[tb-position]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/model/VideoPosition.kt
[tb-save]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/ExoPlayerService.kt#L2123-L2164
[tb-timer]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/ExoPlayerService.kt#L426-L436
[tb-cards]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/app/src/main/java/com/github/andreyasadchy/xtra/ui/following/videos/FollowedVideosFragment.kt#L128-L135
[tb-license]: https://github.com/w1lli4m666-droid/Twitch-box/blob/23bcb074a546c1c891d652d21ac4f6e0960894c0/LICENSE
[x-feed]: https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/repository/datasource/FollowedVideosDataSource.kt
[x-player]: https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/ui/player/PlayerFragment.kt#L602-L622
[x-resolver]: https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/repository/PlayerRepository.kt#L550-L625
[x-license]: https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/LICENSE
[sl]: https://github.com/streamlink/streamlink/blob/a685512a746a99a429b2678e2718c1ee85ba9f6c/src/streamlink/plugins/twitch.py
[m3-old]: https://github.com/androidx/media/blob/9b8b13d177cf9a3ce997773ca51337acf5acc639/libraries/ui/src/main/java/androidx/media3/ui/DefaultTimeBar.java
[m3-new]: https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/ui/src/main/java/androidx/media3/ui/DefaultTimeBar.java
[helix-follows]: https://dev.twitch.tv/docs/api/reference/#get-followed-channels
[helix-videos]: https://dev.twitch.tv/docs/api/reference/#get-videos
[helix-streams]: https://dev.twitch.tv/docs/api/reference/#get-streams
[rate]: https://dev.twitch.tv/docs/api/guide/#twitch-rate-limits
[m3-live]: https://developer.android.com/media/media3/exoplayer/live-streaming#seeking-in-live-streams
[m3-player]: https://developer.android.com/reference/androidx/media3/common/Player
