package tv.slink.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class ChatLogTest {
    static JSONObject message(String id, String user, String text) throws Exception {
        return new JSONObject().put("message_id", id).put("chatter_user_id", user).put("chatter_user_name", "Viewer")
                .put("message", new JSONObject().put("text", text).put("fragments", new JSONArray().put(new JSONObject().put("type", "text").put("text", text))));
    }
    private static final String EARLIER = "2026-11-08T10:00:00Z", LATER = "2026-11-08T10:00:01Z";
    @Test public void transcriptIsBoundedAndDuplicateMessageCannotReappearAfterEviction() throws Exception {
        ChatLog log = new ChatLog();
        for (int i = 0; i < 140; i++) log.accept("channel.chat.message", message("m" + i, "u", "Hello"), "e" + i, EARLIER);
        assertEquals(100, log.snapshot().size()); assertEquals("m40", log.snapshot().get(0).id());
        log.accept("channel.chat.message", message("m0", "u", "Hello"), "duplicate-envelope", EARLIER);
        assertEquals("m40", log.snapshot().get(0).id());
    }
    @Test public void deletionBeforeMessageAndSharedSourceDeletionAreApplied() throws Exception {
        ChatLog log = new ChatLog();
        log.accept("channel.chat.message_delete", new JSONObject().put("message_id", "deleted"), "delete", LATER);
        log.accept("channel.chat.message", message("deleted", "u", "late"), "late", EARLIER);
        log.accept("channel.chat.message", message("local", "u", "shared").put("source_message_id", "origin"), "shared", EARLIER);
        log.accept("channel.chat.message_delete", new JSONObject().put("message_id", "origin"), "delete-source", LATER);
        assertTrue(log.snapshot().isEmpty());
    }
    @Test public void userAndRoomClearsSuppressLateMessagesButKeepNewerMessages() throws Exception {
        ChatLog log = new ChatLog();
        log.accept("channel.chat.clear_user_messages", new JSONObject().put("target_user_id", "u"), "clear-user", LATER);
        log.accept("channel.chat.message", message("old", "u", "old"), "old", EARLIER);
        log.accept("channel.chat.message", message("new", "u", "new"), "new", "2026-11-08T10:00:02Z");
        log.accept("channel.chat.clear", new JSONObject(), "room", LATER);
        log.accept("channel.chat.message", message("other-old", "other", "old"), "late", EARLIER);
        assertEquals(1, log.snapshot().size()); assertEquals("new", log.snapshot().get(0).id());
    }
    @Test public void structuredEmotesPreserveUnicodeAndIdentity() throws Exception {
        JSONObject event = message("m", "u", "😀 Kappa");
        event.getJSONObject("message").put("fragments", new JSONArray().put(new JSONObject().put("type", "text").put("text", "😀 "))
                .put(new JSONObject().put("type", "emote").put("text", "Kappa").put("emote", new JSONObject().put("id", "25").put("format", new JSONArray().put("static")))));
        ChatMessage result = ChatMessage.parse(event, 1);
        assertEquals("😀 ", result.fragments().get(0).text()); assertEquals("25", result.fragments().get(1).emoteId());
    }
}
