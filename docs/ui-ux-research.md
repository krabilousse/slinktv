# SlinkTV TV prototype: discovery, following, and local relevance

Checked 2026-11-08. This document separates documented API behavior and inspected source from proposed UX. The original research accompanied the HTML design prototype. Stream Wall is now implemented in version 0.3.0; the implementation decision below supersedes the original official-API-only guest proposal. Authentication and playback context remains in [phase2-research.md](phase2-research.md).

## Implementation update: real guest discovery in 0.3.0

Following the owner's request to inspect actual guest behavior, Xtra's pinned [TopStreams query](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/graphql/TopStreams.graphql) and [GraphQL repository](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/repository/GraphQLRepository.kt) show web directory requests with `VIEWER_COUNT` ordering and `broadcasterLanguages`. This is a separate interface from official Helix. Frosty's [getDefaultToken](https://github.com/tommyxchow/frosty/blob/3ea6ccd43508fd162b045c7b0a4760716d6bcfd1/lib/apis/twitch_api.dart#L133) uses `client_credentials` with a client secret before querying Helix streams; SlinkTV does not embed that secret-based path.

**Observed:** a direct directory request using SlinkTV's own Public Client ID returned `invalidClientID`. The same anonymous query with the public web identifier already included in Streamlink's Twitch plugin succeeded. `streams(first:60)` was rejected; this interface accepts at most 30. The implemented `directory.py` requests 30, follows its cursor, filters by language, and sorts the returned candidates by viewers. Live host probes returned 28 English, 28 French, and 30 German streams with names, counts, previews, avatars, and next-page cursors. See [recorded observations](evidence/guest-directory-0.3.0.json); these counts are observations at that time, not ongoing guarantees.

**Decision:** use this experimental anonymous web directory for guests, with `TwitchAPI.CLIENT_ID` imported from the pinned Streamlink engine. No user token, private client secret, or companion backend is involved. This does not turn the web interface into a supported Twitch API or establish approval under Twitch's developer terms; reliability and distribution suitability remain release considerations. Errors display Retry/Sign in rather than fixture streams. Guest search looks up an exact channel login through the same interface. Signed-in discovery/search stays on Helix with SlinkTV's own Client ID.

**Pagination limit observed during verification:** the first English page succeeded, but its continuation returned `IntegrityCheckFailed`. The app now handles this response by retaining loaded cards, stopping automatic continuation, and offering Sign in. It does not retry an integrity rejection indefinitely or attempt a browser challenge. [Live pagination evidence](evidence/guest-pagination-0.3.0.json) also confirms exact Linkus7 lookup returned the live channel. Signed-in browsing uses Helix pagination. Guest discovery should currently be treated as a first-page experience, even when the server advertises another page.

Popular defaults to the TV's system language; the viewer may choose another language or all languages. Following is unfiltered by language and defaults to viewer count. Optional relevance uses local account-scoped actual playback time, with explicit enable/disable/clear controls. The app does not call Xtra's recommendation endpoint. Cards reserve the same thumbnail, avatar, and text dimensions during loading and after binding; image loading never changes layout.

## 1. Guest discovery: the authentication boundary

**Conclusion from Twitch's documented contracts:** no official endpoint can provide a fresh popular/recommended live-channel directory to a genuinely logged-out, standalone SlinkTV installation using only its own Public client ID, with no server or client secret. A public client ID identifies the application; it is not an access token. The relevant discovery endpoints require an app or user token. App-token acquisition through `client_credentials` requires a secret and is intended for server-to-server use. Public clients instead obtain user tokens through device authorization, which requires user consent. [OAuth flows and device authorization](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/), [Get Streams authorization](https://dev.twitch.tv/docs/api/reference/#get-streams).

Once signed in, the existing device-flow user token can also call general discovery/search endpoints; those endpoints require no additional named scope. `user:read:follows` remains necessary for the account's follows. “No additional scope” does **not** mean anonymous access. Every Helix request needs a bearer token and its matching `Client-Id`. [Streams](https://dev.twitch.tv/docs/api/reference/#get-streams), [Search Channels](https://dev.twitch.tv/docs/api/reference/#search-channels), [Followed Streams](https://dev.twitch.tv/docs/api/reference/#get-followed-streams).

Distinguish these four products:

| Experience | What it actually means | Feasibility under current constraints |
| --- | --- | --- |
| Popular live channels | `/helix/streams`, descending current viewers; optional category/language filters. | Available with the signed-in user token. No client-ID-only guest access. |
| Popular categories | `/helix/games/top`; then `/helix/streams?game_id=…`. | Same token requirement; popularity is not personal relevance. |
| Curated guest seeds | A small, bundled list of channel identities chosen by SlinkTV. | Possible without an API call. Seeds alone cannot prove current live status, title, viewers, or ranking. |
| Twitch personalized recommendations | Twitch's own recommendation system, potentially using engagement/history and context. | No documented Helix recommendation or personal watch-history endpoint found in the current API catalog. Do not promise parity with Twitch's homepage/sidebar. |

Sources: [Get Streams](https://dev.twitch.tv/docs/api/reference/#get-streams), [Get Top Games](https://dev.twitch.tv/docs/api/reference/#get-top-games), [API catalog](https://dev.twitch.tv/docs/api/reference/). The seed approach is a **product proposal**, not a Twitch API feature. Twitch describes its own curated and algorithmic surfaces, including history, language, login state, device, and region as inputs; this description does not expose a developer API. [Twitch's explanation of recommendations](https://legal.twitch.com/en/legal/recommendations-on-twitch/).

**Private GraphQL is a separate, unsupported integration surface for this design.** The inspected Xtra code uses persisted GraphQL operations, including `SideNav`; that is evidence of Xtra's implementation, not an official API contract for SlinkTV's Public client. The official API catalog supplies no corresponding contract. Do not borrow Twitch web/Xtra/Frosty client IDs: Twitch explicitly requires each application to use its own ID and secrets to remain confidential. [Xtra GraphQL implementation](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/repository/GraphQLRepository.kt#L974), [API catalog](https://dev.twitch.tv/docs/api/reference/), [registration rules](https://dev.twitch.tv/docs/authentication/register-app/).

**Suggested guest UX:** show “Channels to explore” using labeled sample/curated content, with a clear sign-in entry point. In the HTML prototype, live badges and counts may be fixtures but must be identified as demo data. In an eventual app, do not portray bundled seeds or cached snapshots as verified “Live now.” A real anonymous directory would require revisiting the no-server constraint; a backend holding a confidential application's secret could obtain app tokens and serve public discovery data. That is an architecture change, not part of this prototype. CDN image availability does not establish live status or bypass Helix authentication.

## 2. API facts that affect the UI

Paths below are relative to `https://api.twitch.tv/helix`. **A/U** means app or user token; **U+follows** means a user token with `user:read:follows`, with request `user_id` equal to the token owner.

| Endpoint | Auth | Ordering and pagination |
| --- | --- | --- |
| `GET /streams` | A/U | Viewers descending. `first=20` default, 1–100; `after`/`before`. Filters include `game_id`, `language`, `user_id`, `user_login`, `type`. No relevance/ascending sort parameter. |
| `GET /streams/followed?user_id=…` | U+follows | **Live follows only, viewers descending.** `first=100` default, 1–100; `after`. No server-side alternative sort. |
| `GET /channels/followed?user_id=…` | U+follows | All follows, newest `followed_at` first. `first=20` default, 1–100; `after`. This is not the live list. |
| `GET /games/top` | A/U | Categories by viewers descending. `first=20` default, 1–100; `after`/`before`. Category records do not include viewer totals. |
| `GET /search/channels?query=…` | A/U | `live_only=false` default; use `true` for live-only results. `first=20` default, 1–100; `after`. No documented viewer/relevance sort control. |
| `GET /search/categories?query=…` | A/U | `first=20` default, 1–100; `after`. No `live_only` or live-stream results. Select a category, then query `/streams?game_id=…`. |

Endpoint contracts: [Streams](https://dev.twitch.tv/docs/api/reference/#get-streams), [Followed Streams](https://dev.twitch.tv/docs/api/reference/#get-followed-streams), [Followed Channels](https://dev.twitch.tv/docs/api/reference/#get-followed-channels), [Top Games](https://dev.twitch.tv/docs/api/reference/#get-top-games), [Search Channels](https://dev.twitch.tv/docs/api/reference/#search-channels), [Search Categories](https://dev.twitch.tv/docs/api/reference/#search-categories).

### Pagination and client sorting

**Documented:** Twitch warns that changing viewer counts can cause duplicated or omitted streams while paging through both stream endpoints. Empty `pagination` ends traversal. A traversal is not an atomic snapshot. [Streams](https://dev.twitch.tv/docs/api/reference/#get-streams), [Followed Streams](https://dev.twitch.tv/docs/api/reference/#get-followed-streams).

**Design consequence:** sorting a loaded page locally only ranks that page. A high-affinity, low-viewer follow might be on a later page. For “Relevant,” request `first=100`, follow all cursors, deduplicate by broadcaster ID, then rank the assembled set. If loading fails or a practical limit is imposed, label the list partial; do not claim a complete personal ranking. Even full cursor traversal retains Twitch's moving-list limitation. Keep loaded cards usable while finishing the load, and preserve the focused broadcaster when committing a new order. For a global directory, label any local ranking as applying to loaded results; do not scan all Twitch streams to manufacture a global relevance sort.

### Search semantics

**Documented:** channel search covers channels that streamed within the previous six months. With `live_only=false`, it matches the broadcaster login; with `true`, it matches broadcaster name and category name. The description specifies case-insensitive prefix matching and normalization of phrases. Category search is case-insensitive and requires all query parts within the category name. Neither endpoint documents a personalized ranking guarantee. Channel-search results include `is_live`, but no `viewer_count`. [Search Channels](https://dev.twitch.tv/docs/api/reference/#search-channels), [Search Categories](https://dev.twitch.tv/docs/api/reference/#search-categories).

**Suggested UX:** separate Channels and Categories results, default the channel view to live-only, and offer an explicit “Include offline” option. Do not describe that switch as only a visibility filter: Twitch also changes its matching behavior. URL-encode the query, reset cursors when query/type/filter changes, and discard obsolete responses. For live search cards needing viewer counts and stream previews, enrich returned broadcaster IDs through `/streams` in batches of at most 100; absence of a live record can mean the channel just went offline. A category search hit need not currently have any live channels. Show “No live channels” after opening an empty category.

### Stream previews, avatars, and refresh

**Documented field differences:**

- Stream and followed-stream `thumbnail_url` is a broadcast-frame image with `{width}`/`{height}` placeholders. Twitch describes it as a frame from the last five minutes, not a continuously updating video preview. Neither stream response supplies a channel avatar. [Streams](https://dev.twitch.tv/docs/api/reference/#get-streams), [Followed Streams](https://dev.twitch.tv/docs/api/reference/#get-followed-streams).
- Channel-search `thumbnail_url` is the **profile image**, despite sharing the field name. Do not stretch it into a landscape stream preview. [Search Channels](https://dev.twitch.tv/docs/api/reference/#search-channels).
- `/users?id=…` supplies `profile_image_url` and `offline_image_url`; these are distinct assets. Batch at most 100 combined IDs/logins and join by ID. Profile images need no email scope. An unfiltered call with a user token returns that token's owner, so skip empty avatar batches. [Get Users](https://dev.twitch.tv/docs/api/reference/#get-users).
- Category `box_art_url` is category artwork; `/games/top` documents size placeholders. Search may return an already sized artwork URL. [Top Games](https://dev.twitch.tv/docs/api/reference/#get-top-games), [Search Categories](https://dev.twitch.tv/docs/api/reference/#search-categories).

**Suggested refresh policy:** refresh visible list metadata on entry/manual refresh and roughly every 60 seconds while foregrounded; pause this work in the background. Revalidate visible stream images about every five minutes, cache avatars longer, and keep placeholders for failures. These intervals are proposed defaults, not Twitch freshness guarantees. A changing client cache key or query suffix cannot force Twitch's CDN to capture a newer frame; do not claim it does. Update counts without continually moving focused TV cards. Respect rate-limit responses and retain a visible stale/error state rather than declaring channels offline on request failure.

## 3. What Xtra and Frosty implement

Pinned revisions, matching the existing research:

- Xtra: `0ab697de04799365f590f91e2dfbefc092c3ad5a`.
- Frosty: `3ea6ccd43508fd162b045c7b0a4760716d6bcfd1`.

### Xtra: popularity for live follows; separate contextual suggestions

**Proven in source:** `FollowedStreamsDataSource.load` merges local follows with the initial remote page, removes matching channels, then calls `list.sortByDescending { it.viewerCount }` at line 78. Subsequent pages use the selected API's paging path. Helix requests use `limit=100` and cursor continuation; avatars are joined from `/users`. The view model supplies no watch-history or relevance-sort input. [Loader, lines 24–91 and 170–210](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/repository/datasource/FollowedStreamsDataSource.kt#L24), [view model, lines 29–46](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/ui/following/streams/FollowedStreamsViewModel.kt#L29).

Its separate all-followed-channels dialog offers follow date, login alphabetic order, and last broadcast, with ascending/descending direction. These are not personal watch-time sorts. The general stream dialog offers viewer counts high/low and `RECENT`; its existence does not imply those options apply to live follows or are supported by Helix. The misleadingly promising `ChannelSort` entity only stores video/clip sorting preferences. [Followed-channel options](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/ui/following/channels/FollowedChannelsSortDialog.kt#L20), [general stream options](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/ui/common/StreamsSortDialog.kt#L27), [ChannelSort fields](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/model/ui/ChannelSort.kt#L6).

**Important positive finding:** Xtra really implements a separate suggestions tab. `GraphQLRepository.loadChannelSuggestions` sends the private persisted `SideNav` operation with the current channel in `recommendationContext`, web-platform context, and a login flag. `ChannelSuggestionsDataSource` selects the section `provider-side-nav-similar-streamer-currently-watching-1`, maps its streams, and returns a non-paginated result. This is contextual upstream recommendation consumption, **not** a local watch-time formula or a personalized reorder of followed streams. The client code does not reveal Twitch's server-side ranking algorithm. [GraphQL request, lines 974–1003](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/repository/GraphQLRepository.kt#L974), [suggestion mapping, lines 17–46](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/repository/datasource/ChannelSuggestionsDataSource.kt#L17), [channel-tab wiring](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/ui/channel/ChannelPagerAdapter.kt#L18).

### Frosty: API order plus explicit pins

**Proven in source:** `TwitchApi.getFollowedStreams` calls `/streams/followed` with the account ID and optional cursor, with no sort parameter. Its “top 20” comment is stale relative to the currently documented default of 100. `ListStoreBase.getStreams` replaces the first page or appends subsequent pages; the `streams` getter filters blocked users while preserving order. [API method, lines 188–202](https://github.com/tommyxchow/frosty/blob/3ea6ccd43508fd162b045c7b0a4760716d6bcfd1/lib/apis/twitch_api.dart#L188), [store getter](https://github.com/tommyxchow/frosty/blob/3ea6ccd43508fd162b045c7b0a4760716d6bcfd1/lib/screens/home/stream_list/stream_list_store.dart#L97), [page assembly, lines 203–231](https://github.com/tommyxchow/frosty/blob/3ea6ccd43508fd162b045c7b0a4760716d6bcfd1/lib/screens/home/stream_list/stream_list_store.dart#L203).

Frosty fetches explicitly pinned IDs separately, shows live pinned streams in a “Pinned” section, and removes them from the ordinary followed list. Pins are user selection, not inferred relevance. Its guest-token method uses `client_credentials` with `client_secret`, so it is not a secret-free Public-client solution. [Pinned fetch](https://github.com/tommyxchow/frosty/blob/3ea6ccd43508fd162b045c7b0a4760716d6bcfd1/lib/screens/home/stream_list/stream_list_store.dart#L248), [list presentation](https://github.com/tommyxchow/frosty/blob/3ea6ccd43508fd162b045c7b0a4760716d6bcfd1/lib/screens/home/stream_list/streams_list.dart#L228), [guest-token acquisition](https://github.com/tommyxchow/frosty/blob/3ea6ccd43508fd162b045c7b0a4760716d6bcfd1/lib/apis/twitch_api.dart#L133).

**Limited absence finding:** no personalized followed-stream reranker or local watch-duration ranking was found in the inspected live-follow loader/view-model/UI paths, related sort models/dialogs, or Frosty's API/list/settings paths at these revisions. Supporting searches covered `.kt`, `.dart` excluding generated `.g.dart`, `.graphql`, and `.xml` source for recommendation, personalization, watch-time/history/duration, and most-watched terms. Those searches led to Xtra's separate suggestions implementation above. This is not proof that every feature, branch, historical release, or upstream service lacks personalization. Neither app was run for this research.

## 4. Proposed local ranking for SlinkTV

Everything in this section is a **design recommendation**, not a Twitch feature or a claim about Xtra/Frosty.

### Sorting contract

- Default to **Most viewers**, descending `viewer_count`, for live follows and general live browsing.
- Offer **Relevant** as an optional sort of **currently live followed channels**. Explain it as “Based on what you watch on this device.” It should not silently add unfollowed recommendations.
- Keep offline channels out of both live sorts, regardless of past watch time. Offer a separate “All followed channels” view if wanted; a partial live page cannot establish that all other follows are offline.
- With no history, disabled tracking, or all-zero scores, use Most viewers. Resolve equal relevance scores by descending viewer count, then stable broadcaster ID. Recompute at list refresh/sort change, not on every playback tick, and preserve TV focus by ID.

### Formula

Store qualifying playback **minutes per UTC day**, keyed by `(Twitch account ID, broadcaster ID, UTC date)` on this device. For each currently live followed channel `c`:

```text
score(c, today) = sum over daily buckets with ageDays in [0, 89]:
                 min(qualifyingMinutes(c, day), 120) * 2^(-ageDays / 14)

ageDays = UTC calendar-day difference between today and the bucket date
```

This combines duration and recency with a **14-day half-life**, a **90-day retention window**, and a **two-hour daily contribution cap per channel**. The cap reduces the influence of one unattended marathon; it is a tunable product choice, not a reliable detector of attention. Day buckets deliberately give day-level recency rather than session-level precision. No extra “last clicked” boost: focus, opening a card, or a failed playback attempt is not viewing. Do not add viewer counts into the score; use them only for fallback/ties so “Relevant” has a distinct, explainable meaning.

For a simple fixture, 120 qualifying minutes from 14 days ago contributes 60 points; a 30-minute bucket today contributes 30. An offline channel remains excluded even with a higher score. These are arithmetic examples, not measured engagement results.

### What counts as playback

For an eventual Media3 implementation, accumulate monotonic elapsed time only while the selected live channel is actually playing and the app's viewing surface is visible. `Player.isPlaying` reflects ready state, play intent, and absence of playback suppression; `playWhenReady` alone would count buffering incorrectly. [Media3 player events](https://developer.android.com/media/media3/exoplayer/listening-to-player-events).

Proposed accounting rules:

- Close the active interval on pause, buffering, error, ended state, channel change, background/screen-off, logout, or account switch; start a new interval only when eligible again.
- Count elapsed viewing time, not media-position jumps, seeks, connection time, or app-open duration. Muting alone does not mean playback stopped. Exclude detectable ad intervals; do not infer ad boundaries when the playback integration cannot expose them.
- Split intervals across UTC dates. Checkpoint short completed deltas, for example every 15 seconds, and flush on state changes; never reconstruct unobserved time after process death or resume. Attribute each delta once to the account/channel that owned it, even if a switch occurs before persistence.
- Use monotonic time for durations and wall-clock dates only for bucket assignment/retention. Ignore invalid future-dated buckets in ranking and avoid inventing viewing time after a clock change.

The **HTML prototype** should exercise this ordering using deterministic fixture histories and simulated play/pause/buffering states if those controls are present. It should not collect real Twitch watch history, request new credentials, or imply that native telemetry is already implemented.

### Account scope and privacy

Enable local collection only when the viewer opts into history-based relevance; leave Most viewers as the initial default. Use stable Twitch account IDs, not display names. Never merge guest history into an account automatically, and never expose another signed-in account's history or sort preference. For this prototype, guest mode needs no persistent viewing history.

Keep only daily per-channel aggregates, retain at most 90 days, and exclude this history from analytics, cloud sync, and automatic backups in the eventual app. Provide “Use viewing history” and “Clear viewing history”; turning collection off stops writes and returns to Most viewers, while Clear removes this account's buckets and resets relevance immediately. For a shared TV, the proposed default is to delete the departing account's local viewing history on explicit sign-out; ordinary app restart retains it. Any reset/sign-out must also invalidate pending playback deltas so they cannot recreate cleared history. Describe this as local ranking privacy, not a claim that Twitch cannot observe playback traffic.

## Material feasibility constraints

1. **Official Helix guest live discovery/search is blocked by the no-server/no-secret/no-user-token combination.** Version 0.3.0 instead uses the experimental web directory described above. The original curated-seed proposal was not implemented in the APK.
2. **Helix supplies popularity and follows, not the inspected Twitch personal recommendation system or personal viewing history.** Relevant ordering requires SlinkTV-owned local data and logic.
3. **Client sorting is bounded by fetched candidates.** Cursor traversal can shift underneath it; keep partial/error states honest and avoid jumping TV focus.
4. **Preview images can lag, and search avatars are not stream previews.** Asset refresh does not provide reliable live verification.
5. **Working QR login/follow retrieval does not establish authenticated playback.** Playback credentials/integration remain a separate concern documented in [phase2-research.md](phase2-research.md); this HTML prototype makes no new playback claim.

The original research task edited only this document. The 0.3.0 implementation and evidence are recorded in [phase3-verification.md](phase3-verification.md).
