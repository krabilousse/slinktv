package tv.slink.app;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** No user tokens or outgoing chat traffic live in this connection. */
final class TwitchChat {
    static final String ENDPOINT = "wss://eventsub.wss.twitch.tv/ws?keepalive_timeout_seconds=30";
    interface Completion { void complete(String error, boolean permissionRequired); }
    interface Subscriptions { void create(String sessionId, BooleanSupplier active, Completion completion); }
    interface Listener { void changed(List<ChatMessage> messages, String status, boolean permissionRequired); }
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS).build();
    private final WebSocket.Factory sockets;
    private final Subscriptions subscriptions;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ChatLog log = new ChatLog();
    private Connection current, replacement;
    private boolean running, publishing, permissionRequired;
    private int generation, attempts;
    private String status = "Connecting…";
    private long replacementStarted;

    TwitchChat(Subscriptions subscriptions, Listener listener) { this(HTTP, subscriptions, listener); }
    TwitchChat(WebSocket.Factory sockets, Subscriptions subscriptions, Listener listener) {
        this.sockets = sockets; this.subscriptions = subscriptions; this.listener = listener;
    }

    synchronized void start() {
        if (running) return;
        running = true; permissionRequired = false; generation++; attempts = 0;
        log.clear(); status = "Connecting…"; publish();
        connect(ENDPOINT, false);
        main.postDelayed(watchdog, 5000);
    }

    synchronized void stop() {
        running = false; generation++;
        main.removeCallbacksAndMessages(null); publishing = false;
        close(current); close(replacement); current = replacement = null;
        log.clear();
    }

    private void connect(String url, boolean handoff) {
        Connection connection = new Connection(generation);
        if (handoff) { replacement = connection; replacementStarted = SystemClock.elapsedRealtime(); }
        else current = connection;
        connection.socket = sockets.newWebSocket(new Request.Builder().url(url).build(), connection);
    }

    private boolean active(Connection connection) {
        return running && connection.generation == generation && (connection == current || connection == replacement);
    }

    private synchronized void receive(Connection connection, String text) {
        if (!active(connection)) return;
        if (text.length() > 65_536) { reconnect(); return; }
        try {
            JSONObject envelope = new JSONObject(text);
            JSONObject metadata = envelope.getJSONObject("metadata"), payload = envelope.getJSONObject("payload");
            String type = metadata.getString("message_type");
            connection.lastMessage = SystemClock.elapsedRealtime();
            switch (type) {
                case "session_welcome" -> welcome(connection, payload.getJSONObject("session"));
                case "session_keepalive" -> { }
                case "notification" -> {
                    if (!connection.welcomed) return;
                    log.accept(payload.getJSONObject("subscription").getString("type"), payload.getJSONObject("event"),
                            metadata.optString("message_id"), metadata.getString("message_timestamp"));
                    publish();
                }
                case "session_reconnect" -> {
                    if (connection != current || replacement != null) return;
                    String url = payload.getJSONObject("session").getString("reconnect_url");
                    if (!validReconnect(url)) { reconnect(); return; }
                    status = "Reconnecting…"; publish(); connect(url, true);
                }
                case "revocation" -> {
                    String reason = payload.getJSONObject("subscription").optString("status");
                    terminal(reason.equals("authorization_revoked") || reason.equals("user_removed")
                            ? "Enable chat again to restore access." : "Chat subscription ended. Retry chat.",
                            reason.equals("authorization_revoked") || reason.equals("user_removed"));
                }
            }
        } catch (Exception ignored) {
            // Treat malformed events as unavailable data, never as text to render or log.
        }
    }

    private void welcome(Connection connection, JSONObject session) throws Exception {
        if (connection.welcomed) return;
        connection.welcomed = true;
        connection.timeout = Math.max(10, Math.min(600, session.optInt("keepalive_timeout_seconds", 30))) * 1000L;
        if (connection == replacement) {
            Connection previous = current; current = replacement; replacement = null;
            close(previous); attempts = 0; status = "Live chat"; publish(); return;
        }
        String sessionId = session.getString("id");
        subscriptions.create(sessionId, () -> { synchronized (TwitchChat.this) { return active(connection); } }, (error, consent) -> {
            synchronized (TwitchChat.this) {
                if (!active(connection)) return;
                if (error == null) { attempts = 0; status = "Live chat"; publish(); }
                else if (consent) terminal(error, true);
                else reconnect();
            }
        });
    }

    static boolean validReconnect(String url) {
        try {
            URI uri = URI.create(url);
            return "wss".equals(uri.getScheme()) && "eventsub.wss.twitch.tv".equals(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private void reconnect() {
        if (!running) return;
        close(current); close(replacement); current = replacement = null;
        generation++; log.clear();
        status = "Reconnecting · messages may be missing"; publish();
        int expected = generation;
        long delay = Math.min(60_000, 1000L << Math.min(attempts++, 6)) + ThreadLocalRandom.current().nextInt(400);
        main.postDelayed(() -> { synchronized (TwitchChat.this) {
            if (running && generation == expected) connect(ENDPOINT, false);
        } }, delay);
    }

    private void terminal(String message, boolean consent) {
        close(current); close(replacement); current = replacement = null;
        running = false; generation++; log.clear();
        permissionRequired = consent; status = message; publish();
        main.removeCallbacks(watchdog);
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() { synchronized (TwitchChat.this) {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (replacement != null && now - replacementStarted > 30_000
                    || current != null && now - current.lastMessage > current.timeout + 5000) reconnect();
            main.postDelayed(this, 5000);
        } }
    };

    private void publish() {
        if (publishing) return;
        publishing = true;
        main.postDelayed(() -> {
            List<ChatMessage> messages; String state; boolean consent;
            synchronized (TwitchChat.this) {
                publishing = false; messages = log.snapshot(); state = status; consent = permissionRequired;
            }
            listener.changed(messages, state, consent);
        }, 100);
    }

    private static void close(Connection connection) { if (connection != null && connection.socket != null) connection.socket.cancel(); }

    private final class Connection extends WebSocketListener {
        final int generation;
        WebSocket socket;
        boolean welcomed;
        long lastMessage = SystemClock.elapsedRealtime(), timeout = 15_000;
        Connection(int generation) { this.generation = generation; }
        @Override public void onMessage(WebSocket socket, String text) { receive(this, text); }
        @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, null); }
        @Override public void onClosed(WebSocket socket, int code, String reason) { disconnected(this); }
        @Override public void onFailure(WebSocket socket, Throwable error, Response response) { disconnected(this); }
    }

    private synchronized void disconnected(Connection connection) { if (active(connection)) reconnect(); }
}
