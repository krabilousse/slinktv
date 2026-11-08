package tv.slink.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record StreamChannel(String id, String login, String name, String title, String game,
                     String thumbnail, String avatar, int viewers, String language) {
    static List<StreamChannel> fromHelix(JSONArray streams, Map<String, String> avatars) throws Exception {
        Map<String, StreamChannel> unique = new LinkedHashMap<>();
        for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.getJSONObject(i);
            String id = s.getString("user_id");
            unique.putIfAbsent(id, new StreamChannel(id, s.getString("user_login"), s.optString("user_name"),
                    s.optString("title"), s.optString("game_name"), s.optString("thumbnail_url")
                    .replace("{width}", "640").replace("{height}", "360"), avatars.getOrDefault(id, ""),
                    Math.max(0, s.optInt("viewer_count")), s.optString("language")));
        }
        return new ArrayList<>(unique.values());
    }

    static List<StreamChannel> fromGuest(JSONArray items) throws Exception {
        Map<String, StreamChannel> unique = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject s = items.getJSONObject(i);
            StreamChannel item = new StreamChannel(s.getString("id"), s.getString("login"), s.getString("name"),
                    s.optString("title"), s.optString("game"), s.optString("thumbnail"), s.optString("avatar"),
                    Math.max(0, s.optInt("viewers")), s.optString("language"));
            unique.putIfAbsent(item.id(), item);
        }
        return new ArrayList<>(unique.values());
    }
}
