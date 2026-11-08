# Phase 2: Twitch account login and followed lists

Checked 2026-11-08 against Twitch documentation, RFC 8628, and actual upstream source. This is source research, not a successful consent, refresh, or Android runtime test. Only this document was written.

## Public-client device login

Register SlinkTV's **own client ID**, with client type **Public**. Registration requires a verified Twitch account and 2FA. Client IDs are public; secrets must remain confidential, and IDs must not be shared between applications. Do not copy Xtra, Frosty, or Twitch web-client IDs into SlinkTV. [Registration](https://dev.twitch.tv/docs/authentication/register-app/).

Public clients need no secret, including refresh, and can obtain tokens only through device authorization. Twitch's current contract:

- `POST https://id.twitch.tv/oauth2/device`: `client_id`, `scopes=user:read:follows` (plural `scopes`).
- Response: `device_code`, `user_code`, `verification_uri`, `expires_in`, `interval`. Example: 1800 seconds, interval 5; use returned values. Encode the returned URI in the QR; it already includes the activation code in Twitch's example. Also display `user_code`.
- `POST https://id.twitch.tv/oauth2/token`: `client_id`, `scopes`, `device_code`, `grant_type=urn:ietf:params:oauth:grant-type:device_code`. Twitch lists `scopes` here too. Its examples use multipart forms.
- Success supplies `access_token`, `refresh_token`, `expires_in`, `scope` array, `token_type`. DCF documentation says access tokens last four hours; honor the response.
- Documented error bodies: `{"status":400,"message":"authorization_pending"}` before consent; `{"status":400,"message":"invalid device code"}` after reuse. Device codes and refresh tokens are single-use.

[Twitch device-flow contract](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow).

**Polling:** RFC 8628 requires waiting at least `interval` seconds between requests, defaulting to five if absent. `authorization_pending` continues; `slow_down` permanently adds five seconds to subsequent waits; `access_denied` and `expired_token` terminate. Other OAuth errors terminate. Connection timeouts require reduced polling frequency, preferably exponential backoff. [RFC 8628 §3.5](https://datatracker.ietf.org/doc/html/rfc8628#section-3.5).

**Evidence boundary:** Twitch's DCF page does not specify its exact denial, expiry, or throttling HTTP/body combinations. The extra RFC error names are compatibility handling, not observed Twitch responses. Parse both Twitch's `message` and standard OAuth `error`; HTTP 400 alone does not mean terminal failure. Do not silently reinterpret every unrecognized 400 as pending. Xtra demonstrates URL-encoded bodies for both device endpoints, although Twitch's examples use multipart. [Xtra `AuthRepository.kt`, lines 145–250](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/repository/AuthRepository.kt#L145).

## Refresh, validation, and session ownership

Refresh with form-encoded `POST https://id.twitch.tv/oauth2/token`: `client_id`, `grant_type=refresh_token`, `refresh_token`; omit `client_secret` for Public clients. Encode values once with a form encoder. Store the replacement refresh token. Twitch recommends reacting to API 401s and serializing refresh across callers. Invalid refresh is documented both as HTTP 400 with `message: "Invalid refresh token"` and as possible HTTP 401. [Refresh rules](https://dev.twitch.tv/docs/authentication/refresh-tokens/).

**Documentation discrepancy:** the DCF page describes 30-day inactivity expiry; the refresh page says Public refresh tokens expire 30 days after generation. The latter also retains an obsolete-sounding claim that only authorization-code tokens are refreshable, despite its Public-client exception and explicit DCF support. Treat public refresh tokens as expiring and rotating; do not promise indefinite login or use authorization-code fallback. [DCF](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow), [refresh documentation](https://dev.twitch.tv/docs/authentication/refresh-tokens/#can-a-refresh-token-become-invalid).

`GET https://id.twitch.tv/oauth2/validate`, with `Authorization: OAuth <token>` or `Bearer <token>`, is required at startup and hourly during an OAuth session. HTTP 200 returns `client_id`, `scopes`, `expires_in`, `user_id`, `login`; app tokens lack user identity. Invalid access returns HTTP 401 with `message: "invalid access token"`. Disconnected/revoked sessions must end. [Validation](https://dev.twitch.tv/docs/authentication/validate-tokens/).

Implementation recommendations:

- Accept a login only after validating this client ID, a nonempty user ID, and the required scope; use that ID for follows. A valid token for another app is insufficient.
- Keep one refresh operation in flight; atomically persist the new token pair before releasing waiting requests. Retry an API request at most once after refresh. A persistent 401 can mean scope/user/client mismatch, not expiry.
- On invalid validation, stop using the old session. A refresh attempt may recover ordinary expiry; terminal refresh failure needs fresh device consent. Network/5xx failures are indeterminate, not evidence of revocation.
- Serialize refresh with logout/account changes. Ignore results belonging to an older session. A crash or lost response after consuming a single-use refresh token may require login again; blindly retrying cannot recover its successor.

## Helix data contract

Use `Authorization: Bearer <access_token>` and matching `Client-Id`. Both follows endpoints require a user token, `user:read:follows`, and `user_id` matching its owner. That scope covers both lists; no email/chat scope is needed for this prototype. [Scope mapping](https://dev.twitch.tv/docs/authentication/scopes/#user-access-token-scopes).

| GET under `https://api.twitch.tv/helix` | Content and ordering | Pagination |
| --- | --- | --- |
| `/channels/followed?user_id=…` | All follows; newest `followed_at` first. `broadcaster_id`, `broadcaster_login`, `broadcaster_name`, `followed_at`; changing `total`. | `first`: default 20, range 1–100; `after`. |
| `/streams/followed?user_id=…` | Live follows; descending viewers. Stream/channel IDs, names, title, game, viewer count, start time, thumbnail; no `total`. Live reordering can duplicate/omit rows across pages. | `first`: default 100, range 1–100; `after`. |

Empty `pagination` ends traversal. [Followed Channels](https://dev.twitch.tv/docs/api/reference/#get-followed-channels), [Followed Streams](https://dev.twitch.tv/docs/api/reference/#get-followed-streams).

`GET /helix/users` without filters returns the token owner. Profile fields include `id`, `login`, `display_name`, `description`, `profile_image_url`, `offline_image_url`, `type`, `broadcaster_type`, `created_at`. `view_count` is deprecated. Email requires `user:read:email`; omit that scope. Fetch followed-channel avatars with repeated `id` parameters, at most 100 IDs/logins combined; join by ID. [Get Users](https://dev.twitch.tv/docs/api/reference/#get-users).

Keep separate opaque cursors per list, URL-encode them, and follow cursors even after a short page. Send explicit `first=100`. Stop on absent cursor, not `total` or page length. Helix 429 supplies `Ratelimit-Reset`; wait accordingly. Unknown JSON fields must not break parsing. [API concepts](https://dev.twitch.tv/docs/api/guide/).

Recommendations: deduplicate live rows by broadcaster ID; keep IDs as strings. Label the complete list **All followed channels**, not **Offline**. Absence from a partially loaded live list proves nothing, and even complete pagination is not an atomic snapshot. Do not call unfiltered `/users` for an empty avatar batch: that retrieves the signed-in profile instead.

## What Xtra and Frosty actually implement

Links pin the inspected revisions: Xtra `0ab697de04799365f590f91e2dfbefc092c3ad5a`; Frosty `3ea6ccd43508fd162b045c7b0a4760716d6bcfd1`.

- **Xtra auth:** `LoginActivity.kt` builds Helix implicit authorization (`response_type=token`, lines 138–165), separately stores `helixToken`, `gqlToken`, and `gqlWebToken`, and validates client-ID equality (630–653). Its external device-code path (551–627) uses a GraphQL client and legacy scopes, with token exchange on a Next button—not a timed public-client polling implementation. It also loads Twitch's Android-TV login in a secondary WebView (508). These are implementation observations, not patterns to copy into SlinkTV. [Exact `LoginActivity.kt`](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/ui/login/LoginActivity.kt#L551).
- **Xtra follows:** `HelixRepository.getUserFollows` really calls `/channels/followed`, despite its older method name; both it and `getFollowedStreams` pass `first`/`after`. [Exact `HelixRepository.kt`, 219 and 565](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/repository/HelixRepository.kt#L565). Helix paging requests 100 and advances `pagination.cursor`; live results separately fetch profile images with `getUsers`. Xtra also merges local follows and uses GraphQL fallback/enrichment, so its UI is not a pure Helix result. [Exact `FollowedChannelsDataSource.kt`, 276–299](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/repository/datasource/FollowedChannelsDataSource.kt#L276), [exact `FollowedStreamsDataSource.kt`, 170–210](https://github.com/crackededed/Xtra/blob/0ab697de04799365f590f91e2dfbefc092c3ad5a/app/src/main/java/com/github/andreyasadchy/xtra/repository/datasource/FollowedStreamsDataSource.kt#L170).
- **Frosty auth:** `auth_store.dart` uses WebView implicit login (142–219), takes `access_token` from the redirect fragment (95–112), validates and securely stores it, and keeps a separate web-cookie `_gqlToken`. This is not DCF refresh. [Exact `auth_store.dart`](https://github.com/tommyxchow/frosty/blob/3ea6ccd43508fd162b045c7b0a4760716d6bcfd1/lib/screens/settings/stores/auth_store.dart#L142). `twitch_api.dart` distinguishes validation 401 from transient errors (153–175), but its anonymous token path uses `client_credentials` plus a secret (133–145), unsuitable for SlinkTV Public clients. [Exact `twitch_api.dart`](https://github.com/tommyxchow/frosty/blob/3ea6ccd43508fd162b045c7b0a4760716d6bcfd1/lib/apis/twitch_api.dart#L133).
- **Frosty follows:** `twitch_api.dart` requests channels with `first=20`, while streams omit `first`; its comment claiming “top 20 followed streams” disagrees with Twitch's current default. Both pass `after`. [Exact methods, 188–218](https://github.com/tommyxchow/frosty/blob/3ea6ccd43508fd162b045c7b0a4760716d6bcfd1/lib/apis/twitch_api.dart#L188). `stream_list_store.dart` maintains separate cursors and appends pages (203–231, 278–292). Its “offline” projection subtracts currently loaded live IDs (124–139); inference: incomplete live pagination can mislabel still-live channels. [Exact store](https://github.com/tommyxchow/frosty/blob/3ea6ccd43508fd162b045c7b0a4760716d6bcfd1/lib/screens/home/stream_list/stream_list_store.dart#L124).

## Helix login is not authenticated playback

Frosty's separate `twitch_gql_api.dart` sends its web-session credential to private GraphQL, obtains a playback `value`/`signature`, then constructs an Usher HLS URL. Neither credential is interchangeable with SlinkTV's Helix user token. [Exact playback implementation](https://github.com/tommyxchow/frosty/blob/3ea6ccd43508fd162b045c7b0a4760716d6bcfd1/lib/apis/twitch_gql_api.dart#L40). Streamlink explicitly documents website-derived OAuth for playback and rejection of third-party-generated credentials in its authentication history. Client integrity is another separate requirement. Do not pass the new DCF token into Streamlink or promise subscriber/Turbo playback benefits from QR login. [Streamlink authentication](https://streamlink.github.io/cli/plugins/twitch.html#authentication), [integrity requirements](https://streamlink.github.io/cli/plugins/twitch.html#client-integrity-token).

## Prototype acceptance edges

Engineering checks, not claims of completed tests:

- QR URI preserves its query; manual code remains readable; expiry uses a monotonic deadline. Cancel/restart cancels polling and discards old callbacks. No overlapping polls or automatic endless code renewal.
- Exercise pending, slow-down compatibility, denied/expired/unknown errors, offline timeout, non-JSON server failure, and late consent after cancellation with SlinkTV's own client.
- Validate startup/hourly/resume behavior; test missing scope, wrong client/account, concurrent 401s, refresh rotation, disconnect, and logout while requests are pending.
- Test zero follows, follows with zero live streams, more than 100 follows, short pages with cursors, repeated cursors, account switch mid-page, and changing live order. Keep partial/error/empty states distinct.
- Raw diagnostics should show endpoint, HTTP status, and sanitized response JSON; redact access/refresh/device codes and authorization headers before display, persistence, logs, or sharing. Keep page responses distinguishable from assembled lists. Credential storage must not depend on the diagnostic view.
