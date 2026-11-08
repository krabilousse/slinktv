package tv.slink.app;

import android.os.Looper;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class TwitchChatTest {
    private final List<Socket> sockets = new ArrayList<>();
    private final List<List<ChatMessage>> frames = new ArrayList<>();
    private final List<String> states = new ArrayList<>();
    private final List<Boolean> consents = new ArrayList<>();
    private int subscriptions;
    private TwitchChat chat;
    private class Socket implements WebSocket {
        final Request request; final WebSocketListener listener; boolean cancelled; int sends;
        Socket(Request request, WebSocketListener listener) { this.request = request; this.listener = listener; }
        @Override public Request request() { return request; }
        @Override public long queueSize() { return 0; }
        @Override public boolean send(String text) { sends++; return true; }
        @Override public boolean send(ByteString bytes) { sends++; return true; }
        @Override public boolean close(int code, String reason) { cancelled = true; return true; }
        @Override public void cancel() { cancelled = true; }
        void event(String type, JSONObject payload, String id) throws Exception {
            listener.onMessage(this, new JSONObject().put("metadata", new JSONObject().put("message_type", type).put("message_id", id)
                    .put("message_timestamp", "2026-11-08T10:00:00Z")).put("payload", payload).toString());
        }
        void welcome(String id) throws Exception { event("session_welcome", new JSONObject().put("session", new JSONObject().put("id", id).put("keepalive_timeout_seconds", 10)), "welcome" + id); }
        void message(String id) throws Exception { event("notification", new JSONObject().put("subscription", new JSONObject().put("type", "channel.chat.message"))
                .put("event", ChatLogTest.message(id, "u", "hello")), id); }
    }
    private void start(TwitchChat.Subscriptions subscribe) {
        chat = new TwitchChat((request, listener) -> { Socket socket = new Socket(request, listener); sockets.add(socket); return socket; }, subscribe,
                (messages, state, consent) -> { frames.add(messages); states.add(state); consents.add(consent); });
        chat.start();
    }
    private void start() { start((id, active, done) -> { subscriptions++; done.complete(null, false); }); }
    private void flush() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(110)); }
    @After public void close() { if (chat != null) chat.stop(); }

    @Test public void handoffTransfersSubscriptionsAndDeduplicatesOverlap() throws Exception {
        start(); Socket first = sockets.get(0); first.welcome("first"); first.message("a"); flush();
        first.event("session_reconnect", new JSONObject().put("session", new JSONObject().put("reconnect_url", "wss://eventsub.wss.twitch.tv/ws?session_id=handoff")), "reconnect");
        assertFalse(first.cancelled); Socket second = sockets.get(1);
        first.message("b"); second.welcome("second"); second.message("b"); flush();
        assertTrue(first.cancelled); assertEquals(1, subscriptions);
        assertEquals(2, frames.get(frames.size() - 1).size());
        assertEquals(0, first.sends + second.sends);
    }
    @Test public void lostConnectionClearsStaleChatAndRecreatesSubscriptions() throws Exception {
        start(); Socket old = sockets.get(0); old.welcome("old"); old.message("old"); flush();
        old.listener.onFailure(old, new java.io.IOException("network"), null); flush();
        assertTrue(frames.get(frames.size() - 1).isEmpty());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        Socket fresh = sockets.get(1); fresh.welcome("fresh"); old.message("late"); fresh.message("new"); flush();
        assertEquals(2, subscriptions); assertEquals("new", frames.get(frames.size() - 1).get(0).id());
    }
    @Test public void missingHeartbeatReconnectsAndStoppingCancelsAllRetries() throws Exception {
        start(); sockets.get(0).welcome("a");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(22));
        assertTrue(sockets.get(0).cancelled); assertTrue(sockets.size() >= 2);
        chat.stop(); int count = sockets.size();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2)); assertEquals(count, sockets.size());
    }
    @Test public void permissionRevocationStopsInsteadOfRetryingForever() throws Exception {
        start(); Socket socket = sockets.get(0); socket.welcome("a"); socket.message("m");
        socket.event("revocation", new JSONObject().put("subscription", new JSONObject().put("status", "authorization_revoked")), "revoke"); flush();
        assertTrue(consents.get(consents.size() - 1)); assertTrue(frames.get(frames.size() - 1).isEmpty());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2)); assertEquals(1, sockets.size());
    }
    @Test public void lateSubscriptionCompletionCannotReviveStoppedChat() throws Exception {
        List<TwitchChat.Completion> pending = new ArrayList<>();
        start((id, active, done) -> pending.add(done)); sockets.get(0).welcome("pending"); chat.stop();
        pending.get(0).complete(null, false); sockets.get(0).message("late"); flush(); assertTrue(frames.isEmpty());
    }
    @Test public void reconnectUrlsStayOnTwitchAndUseTls() {
        assertTrue(TwitchChat.validReconnect("wss://eventsub.wss.twitch.tv/ws?token=opaque"));
        assertFalse(TwitchChat.validReconnect("ws://eventsub.wss.twitch.tv/ws"));
        assertFalse(TwitchChat.validReconnect("wss://example.com/ws"));
        assertFalse(TwitchChat.validReconnect("wss://user@eventsub.wss.twitch.tv/ws"));
    }
}
