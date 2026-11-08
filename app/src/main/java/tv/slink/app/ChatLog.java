package tv.slink.app;

import org.json.JSONObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded transcript; moderation is applied before the next UI batch, including late delivery. */
final class ChatLog {
    private final List<ChatMessage> messages = new ArrayList<>();
    private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
    private final LinkedHashMap<String, Boolean> messageIds = new LinkedHashMap<>();
    private final LinkedHashMap<String, Boolean> deleted = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> userClears = new LinkedHashMap<>();
    private long clearedAt;

    void accept(String type, JSONObject event, String envelopeId, String timestamp) throws Exception {
        if (!envelopeId.isEmpty() && seen.put(envelopeId, true) != null) return;
        trim(seen, 512);
        long time = Instant.parse(timestamp).toEpochMilli();
        switch (type) {
            case "channel.chat.message" -> {
                ChatMessage message = ChatMessage.parse(event, time);
                if (time <= clearedAt || time <= userClears.getOrDefault(message.userId(), 0L)
                        || deleted.containsKey(message.id()) || deleted.containsKey(message.sourceMessageId())) return;
                if (messageIds.put(message.id(), true) != null) return;
                trim(messageIds, 512);
                messages.add(message);
                if (messages.size() > 100) messages.remove(0);
            }
            case "channel.chat.message_delete" -> {
                String id = event.getString("message_id");
                deleted.put(id, true); trim(deleted, 512);
                messages.removeIf(m -> m.id().equals(id) || m.sourceMessageId().equals(id));
            }
            case "channel.chat.clear_user_messages" -> {
                String user = event.getString("target_user_id");
                userClears.put(user, Math.max(time, userClears.getOrDefault(user, 0L))); trim(userClears, 512);
                messages.removeIf(m -> m.userId().equals(user) && m.timestamp() <= time);
            }
            case "channel.chat.clear" -> {
                clearedAt = Math.max(clearedAt, time);
                messages.removeIf(m -> m.timestamp() <= time);
            }
        }
    }

    List<ChatMessage> snapshot() { return List.copyOf(messages); }
    void clear() { messages.clear(); seen.clear(); messageIds.clear(); deleted.clear(); userClears.clear(); clearedAt = 0; }
    private static void trim(Map<?, ?> map, int limit) { while (map.size() > limit) map.remove(map.keySet().iterator().next()); }
}
