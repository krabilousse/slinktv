package tv.slink.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Twitch's public-client device flow and the read-only Helix endpoints used by this prototype. */
final class TwitchApi {
    static final String SCOPE = "user:read:follows";
    static final String CHAT_SCOPE = "user:read:chat";
    static final String LOGIN_SCOPES = SCOPE + " " + CHAT_SCOPE;
    static final String[] CHAT_EVENTS = {"channel.chat.message_delete", "channel.chat.clear_user_messages", "channel.chat.clear", "channel.chat.message"};
    private static final String OAUTH = "https://id.twitch.tv/oauth2/";
    private static final String HELIX = "https://api.twitch.tv/helix/";

    record Reply(int status, JSONObject body, int retryAfterSeconds) {}
    interface Transport {
        Reply request(String method, String url, Map<String, String> headers, String form) throws Exception;
    }
    record Session(String clientId, String accessToken, String refreshToken, long expiresAt) {
        @Override public String toString() { return "Twitch session (credentials redacted)"; }
    }
    record DeviceCode(String clientId, String deviceCode, String userCode, String verificationUri,
                      long expiresAt, int intervalSeconds) {}
    sealed interface PollResult permits Pending, Authorized {}
    record Pending(int delaySeconds, String message) implements PollResult {}
    record Authorized(Session session) implements PollResult {}
    record Snapshot(JSONObject validation, JSONObject profile, JSONArray followedPages, JSONArray livePages) {
        JSONArray followed() throws Exception { return items(followedPages); }
        JSONArray live() throws Exception { return items(livePages); }
        private static JSONArray items(JSONArray pages) throws Exception {
            JSONArray result = new JSONArray();
            for (int i = 0; i < pages.length(); i++) {
                JSONArray data = pages.getJSONObject(i).getJSONArray("data");
                for (int j = 0; j < data.length(); j++) result.put(data.getJSONObject(j));
            }
            return result;
        }
    }
    static final class ApiException extends Exception {
        final int status;
        final int retrySeconds;
        ApiException(int status, String message) { this(status, message, 0); }
        ApiException(int status, String message, int retrySeconds) { super(message); this.status = status; this.retrySeconds = retrySeconds; }
    }

    private final Transport transport;
    TwitchApi() { this(new HttpTransport()); }
    TwitchApi(Transport transport) { this.transport = transport; }

    DeviceCode begin(String clientId, long now) throws Exception {
        if (!clientId.matches("[A-Za-z0-9]{10,100}")) throw new ApiException(0, "Enter the Client ID of a Twitch Public application.");
        Reply reply = transport.request("POST", OAUTH + "device", Map.of(), form("client_id", clientId, "scopes", LOGIN_SCOPES));
        requireSuccess(reply, "Could not start login. Check that the Client ID belongs to a Public Twitch application.");
        JSONObject body = reply.body();
        String url = body.optString("verification_uri_complete", body.getString("verification_uri"));
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getHost() == null
                || !Set.of("www.twitch.tv", "twitch.tv", "id.twitch.tv").contains(uri.getHost())) {
            throw new ApiException(0, "Twitch returned an unexpected verification address.");
        }
        return new DeviceCode(clientId, body.getString("device_code"), body.getString("user_code"), url,
                now + body.getLong("expires_in") * 1000, Math.max(5, body.optInt("interval", 5)));
    }

    PollResult poll(DeviceCode code, int interval, long now) throws Exception {
        if (now >= code.expiresAt()) throw new ApiException(0, "Login code expired. Select Sign in to get a new QR code.");
        Reply reply = transport.request("POST", OAUTH + "token", Map.of(), form("client_id", code.clientId(),
                "device_code", code.deviceCode(), "grant_type", "urn:ietf:params:oauth:grant-type:device_code", "scopes", LOGIN_SCOPES));
        if (reply.status() == 200) return new Authorized(parseSession(code.clientId(), reply.body(), now));
        String error = reply.body().optString("error", reply.body().optString("message"));
        if (error.equals("authorization_pending")) return new Pending(interval, "Waiting for approval on your phone…");
        if (error.equals("slow_down") || reply.status() == 429) return new Pending(Math.max(interval + 5, reply.retryAfterSeconds()), "Twitch asked us to wait. Login is still pending…");
        if (reply.status() >= 500) return new Pending(Math.max(interval, 15), "Twitch is temporarily unavailable. Retrying…");
        if (error.equals("access_denied")) throw new ApiException(reply.status(), "Login was declined. Select Sign in to try again.");
        if (error.equals("expired_token") || error.equals("invalid device code")) throw new ApiException(reply.status(), "Login code expired or was already used. Select Sign in again.");
        throw new ApiException(reply.status(), "Twitch rejected device login (HTTP " + reply.status() + "). Confirm the app's Client Type is Public.");
    }

    Session refresh(Session current, long now) throws Exception {
        Reply reply = transport.request("POST", OAUTH + "token", Map.of(), form("client_id", current.clientId(),
                "grant_type", "refresh_token", "refresh_token", current.refreshToken()));
        requireSuccess(reply, "Session renewal failed. Sign in again if the authorization has expired or been revoked.");
        return parseSession(current.clientId(), reply.body(), now);
    }

    JSONObject validate(Session session) throws Exception {
        Reply reply = transport.request("GET", OAUTH + "validate", Map.of("Authorization", "OAuth " + session.accessToken()), null);
        requireSuccess(reply, "The login is no longer valid. Sign in again.");
        JSONObject body = reply.body();
        if (!session.clientId().equals(body.getString("client_id")) || body.optString("user_id").isEmpty())
            throw new ApiException(401, "This token does not belong to the configured application and user.");
        JSONArray scopes = body.getJSONArray("scopes");
        boolean follows = false;
        for (int i = 0; i < scopes.length(); i++) follows |= SCOPE.equals(scopes.getString(i));
        if (!follows) throw new ApiException(403, "Followed-channel permission was not granted. Sign out and authorize user:read:follows.");
        return body;
    }

    Snapshot fetch(Session session, JSONObject validation) throws Exception {
        String userId = validation.getString("user_id");
        JSONObject profile = get(session, "users?id=" + encode(userId));
        JSONArray followed = pages(session, "channels/followed", userId);
        JSONArray live = pages(session, "streams/followed", userId);
        return new Snapshot(validation, profile, followed, live);
    }

    private JSONArray pages(Session session, String endpoint, String userId) throws Exception {
        JSONArray pages = new JSONArray();
        Set<String> seen = new HashSet<>();
        String cursor = "";
        do {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
            JSONObject page = get(session, endpoint + "?user_id=" + encode(userId) + "&first=100"
                    + (cursor.isEmpty() ? "" : "&after=" + encode(cursor)));
            page.getJSONArray("data");
            pages.put(page);
            JSONObject pagination = page.optJSONObject("pagination");
            cursor = pagination == null ? "" : pagination.optString("cursor");
            if (!cursor.isEmpty() && (!seen.add(cursor) || pages.length() >= 1000))
                throw new ApiException(0, "Twitch pagination did not complete. Refresh to retry; this list is not complete.");
        } while (!cursor.isEmpty());
        return pages;
    }

    private JSONObject get(Session session, String path) throws Exception {
        Reply reply = transport.request("GET", HELIX + path,
                Map.of("Authorization", "Bearer " + session.accessToken(), "Client-Id", session.clientId()), null);
        requireSuccess(reply, reply.status() == 429 ? "Twitch rate limit reached. Wait a minute, then Refresh."
                : "Could not load Twitch data (HTTP " + reply.status() + "). Check the connection or sign in again.");
        return reply.body();
    }

    record StreamPage(java.util.List<StreamChannel> items, String cursor) {}

    record VideoPage(java.util.List<VideoItem> items, String cursor) {}
    VideoPage videos(Session session, String channel, String cursor, int count) throws Exception {
        JSONObject page = get(session, "videos?user_id=" + encode(channel) + "&type=archive&sort=time&first=" + count
                + (cursor.isEmpty() ? "" : "&after=" + encode(cursor)));
        java.util.List<VideoItem> videos = new java.util.ArrayList<>();
        JSONArray items = page.getJSONArray("data");
        for (int i = 0; i < items.length(); i++) videos.add(VideoItem.parse(items.getJSONObject(i)));
        JSONObject pagination = page.optJSONObject("pagination");
        return new VideoPage(java.util.List.copyOf(videos), pagination == null ? "" : pagination.optString("cursor"));
    }

    Set<String> liveFollowedIds(Session session, String user) throws Exception {
        JSONArray pages = pages(session, "streams/followed", user);
        Set<String> live = new HashSet<>();
        for (int p = 0; p < pages.length(); p++) {
            JSONArray data = pages.getJSONObject(p).getJSONArray("data");
            for (int i = 0; i < data.length(); i++) live.add(data.getJSONObject(i).getString("user_id"));
        }
        return live;
    }

    java.util.List<StreamChannel> cards(Session session, JSONArray streams) throws Exception {
        java.util.Map<String, String> avatars = new java.util.HashMap<>();
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < streams.length(); i++) {
            String id = streams.getJSONObject(i).getString("user_id");
            if (!ids.contains(id)) ids.add(id);
        }
        for (int start = 0; start < ids.size(); start += 100) {
            StringBuilder query = new StringBuilder("users?");
            for (String id : ids.subList(start, Math.min(start + 100, ids.size()))) query.append("id=").append(encode(id)).append('&');
            JSONArray users = get(session, query.toString()).getJSONArray("data");
            for (int i = 0; i < users.length(); i++) {
                JSONObject user = users.getJSONObject(i);
                avatars.put(user.getString("id"), user.optString("profile_image_url"));
            }
        }
        return StreamChannel.fromHelix(streams, avatars);
    }

    StreamPage popular(Session session, String language, String cursor) throws Exception {
        JSONObject page = get(session, "streams?first=60" + (language.isEmpty() ? "" : "&language=" + encode(language))
                + (cursor.isEmpty() ? "" : "&after=" + encode(cursor)));
        return new StreamPage(cards(session, page.getJSONArray("data")), cursor(page));
    }

    StreamPage search(Session session, String query, String after) throws Exception {
        JSONObject page = get(session, "search/channels?live_only=true&first=50&query=" + encode(query)
                + (after.isEmpty() ? "" : "&after=" + encode(after)));
        JSONArray found = page.getJSONArray("data");
        if (found.length() == 0) return new StreamPage(java.util.List.of(), cursor(page));
        StringBuilder path = new StringBuilder("streams?first=100");
        for (int i = 0; i < found.length(); i++) path.append("&user_id=").append(encode(found.getJSONObject(i).getString("id")));
        return new StreamPage(cards(session, get(session, path.toString()).getJSONArray("data")), cursor(page));
    }

    private static String cursor(JSONObject page) {
        JSONObject pagination = page.optJSONObject("pagination");
        return pagination == null ? "" : pagination.optString("cursor");
    }

    void revoke(Session session) throws Exception {
        Reply reply = transport.request("POST", OAUTH + "revoke", Map.of(), form("client_id", session.clientId(), "token", session.accessToken()));
        requireSuccess(reply, "Signed out locally. Could not revoke at Twitch; remove SlinkTV in Twitch Settings → Connections if needed.");
    }

    static boolean canReadChat(JSONObject validation) {
        JSONArray scopes = validation.optJSONArray("scopes");
        if (scopes != null) for (int i = 0; i < scopes.length(); i++) if (CHAT_SCOPE.equals(scopes.optString(i))) return true;
        return false;
    }

    String channelId(Session session, String login) throws Exception {
        JSONArray users = get(session, "users?login=" + encode(login)).getJSONArray("data");
        if (users.length() == 0) throw new ApiException(404, "Channel not found.");
        return users.getJSONObject(0).getString("id");
    }

    void subscribeChat(Session session, String reader, String channel, String socketId,
                       java.util.function.BooleanSupplier active) throws Exception {
        JSONObject condition = new JSONObject().put("broadcaster_user_id", channel).put("user_id", reader);
        for (String type : CHAT_EVENTS) {
            if (!active.getAsBoolean()) return;
            JSONObject body = new JSONObject().put("type", type).put("version", "1").put("condition", condition)
                    .put("transport", new JSONObject().put("method", "websocket").put("session_id", socketId));
            Reply response = transport.request("POST", HELIX + "eventsub/subscriptions",
                    Map.of("Authorization", "Bearer " + session.accessToken(), "Client-Id", session.clientId(), "Content-Type", "application/json"), body.toString());
            if (response.status() == 409) {
                JSONArray existing = get(session, "eventsub/subscriptions?type=" + encode(type)).getJSONArray("data");
                boolean attached = false;
                for (int i = 0; i < existing.length(); i++) {
                    JSONObject subscription = existing.getJSONObject(i);
                    JSONObject match = subscription.getJSONObject("condition");
                    attached |= "enabled".equals(subscription.optString("status"))
                            && channel.equals(match.optString("broadcaster_user_id")) && reader.equals(match.optString("user_id"))
                            && socketId.equals(subscription.getJSONObject("transport").optString("session_id"));
                }
                if (attached) continue;
            }
            requireSuccess(response, response.status() == 403
                    ? "Chat permission is unavailable. Enable chat again."
                    : "Could not connect chat (HTTP " + response.status() + ").");
        }
    }

    private Session parseSession(String clientId, JSONObject body, long now) throws Exception {
        if (!"bearer".equalsIgnoreCase(body.getString("token_type"))) throw new ApiException(0, "Unsupported token type from Twitch.");
        String access = body.getString("access_token"), refresh = body.getString("refresh_token");
        if (access.isEmpty() || refresh.isEmpty()) throw new ApiException(0, "Twitch returned an incomplete login.");
        return new Session(clientId, access, refresh, now + body.getLong("expires_in") * 1000);
    }

    private static void requireSuccess(Reply reply, String message) throws ApiException {
        if (reply.status() < 200 || reply.status() >= 300) throw new ApiException(reply.status(), message, reply.retryAfterSeconds());
    }

    static String form(String... pairs) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) body.append('&');
            body.append(encode(pairs[i])).append('=').append(encode(pairs[i + 1]));
        }
        return body.toString();
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static final class HttpTransport implements Transport {
        @Override public Reply request(String method, String url, Map<String, String> headers, String form) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            try {
                connection.setRequestMethod(method);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json");
                for (var entry : headers.entrySet()) connection.setRequestProperty(entry.getKey(), entry.getValue());
                if (form != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", headers.getOrDefault("Content-Type", "application/x-www-form-urlencoded"));
                    byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (var output = connection.getOutputStream()) { output.write(bytes); }
                }
                int status = connection.getResponseCode();
                String body = "";
                try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                    if (input != null) {
                        byte[] bytes = input.readNBytes(2_000_001);
                        if (bytes.length > 2_000_000) throw new IOException("Response too large");
                        body = new String(bytes, StandardCharsets.UTF_8);
                    }
                }
                int retry = 0;
                try { retry = Integer.parseInt(connection.getHeaderField("Retry-After")); } catch (NumberFormatException ignored) {}
                if (status == 429) try { retry = Math.max(retry, (int) Math.min(3600, Math.max(0, Long.parseLong(connection.getHeaderField("Ratelimit-Reset")) - System.currentTimeMillis() / 1000))); } catch (NumberFormatException ignored) {}
                JSONObject json;
                try { json = body.isBlank() ? new JSONObject() : new JSONObject(body); }
                catch (org.json.JSONException exception) {
                    if (status >= 200 && status < 300) throw new IOException("Invalid JSON response");
                    json = new JSONObject();
                }
                return new Reply(status, json, retry);
            } finally { connection.disconnect(); }
        }
    }
}
