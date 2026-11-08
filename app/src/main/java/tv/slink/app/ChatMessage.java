package tv.slink.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

record ChatMessage(String id, String sourceMessageId, String userId, String name, String color,
                   String sourceName, String badge, List<Fragment> fragments, long timestamp) {
    record Fragment(String text, String emoteId, boolean animated) {}

    static ChatMessage parse(JSONObject event, long timestamp) throws Exception {
        String id = event.getString("message_id");
        JSONObject message = event.getJSONObject("message");
        JSONArray raw = message.optJSONArray("fragments");
        List<Fragment> fragments = new ArrayList<>();
        int remaining = 3000;
        if (raw != null) for (int i = 0; i < Math.min(100, raw.length()) && remaining > 0; i++) {
            JSONObject part = raw.getJSONObject(i);
            String text = part.optString("text");
            if (text.length() > remaining) text = text.substring(0, remaining);
            remaining -= text.length();
            JSONObject emote = part.optJSONObject("emote");
            String emoteId = ""; boolean animated = false;
            if ("emote".equals(part.optString("type")) && emote != null) {
                emoteId = emote.optString("id");
                if (!emoteId.matches("[A-Za-z0-9_-]{1,150}")) emoteId = "";
                JSONArray formats = emote.optJSONArray("format");
                if (formats != null) for (int j = 0; j < formats.length(); j++) animated |= "animated".equals(formats.optString(j));
            }
            fragments.add(new Fragment(text, emoteId, animated));
        }
        if (fragments.isEmpty()) {
            String text = message.optString("text");
            fragments.add(new Fragment(text.substring(0, Math.min(3000, text.length())), "", false));
        }
        String badge = "";
        JSONArray badges = event.optJSONArray("badges");
        if (badges != null) for (int i = 0; i < badges.length(); i++) {
            String set = badges.getJSONObject(i).optString("set_id");
            if (set.equals("broadcaster")) { badge = "HOST"; break; }
            if (set.equals("moderator")) badge = "MOD";
            else if (badge.isEmpty() && set.equals("vip")) badge = "VIP";
        }
        String source = event.optString("source_broadcaster_user_name", "");
        if (source.equals("null") || source.equals(event.optString("broadcaster_user_name"))) source = "";
        return new ChatMessage(id, event.optString("source_message_id", ""), event.getString("chatter_user_id"),
                event.optString("chatter_user_name", event.optString("chatter_user_login", "Viewer")),
                event.optString("color"), source, badge, List.copyOf(fragments), timestamp);
    }
}
