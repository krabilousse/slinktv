package tv.slink.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

record ChatEmote(String name, String url, String staticUrl, int width, int height, boolean animated, boolean zeroWidth) {
    static Map<String, ChatEmote> parseSet(JSONObject set) {
        Map<String, ChatEmote> result = new LinkedHashMap<>();
        JSONArray emotes = set.optJSONArray("emotes");
        if (emotes == null) return result;
        for (int i = 0; i < Math.min(3000, emotes.length()); i++) {
            try {
                JSONObject entry = emotes.getJSONObject(i), data = entry.getJSONObject("data"), host = data.getJSONObject("host");
                String base = host.getString("url");
                if (base.startsWith("//")) base = "https:" + base;
                URI uri = URI.create(base);
                if (!"https".equals(uri.getScheme()) || !"cdn.7tv.app".equals(uri.getHost()) || uri.getUserInfo() != null) continue;
                JSONObject file = chooseFile(host.getJSONArray("files"));
                if (file == null) continue;
                String name = entry.getString("name"), asset = file.getString("name"), still = file.optString("static_name", asset);
                if (name.isEmpty() || name.length() > 100 || !safeFilename(asset) || !safeFilename(still)) continue;
                int width = file.getInt("width"), height = file.getInt("height");
                boolean animated = file.optInt("frame_count", 1) > 1;
                if (file.optInt("frame_count", 1) > 200) { asset = still; animated = false; }
                boolean zero = (data.optInt("flags") & 256) != 0 || (entry.optInt("flags") & 1) != 0;
                result.put(name, new ChatEmote(name, base + "/" + asset, base + "/" + still, width, height, animated, zero));
            } catch (Exception ignored) { }
        }
        return result;
    }

    private static boolean safeFilename(String name) { return name.matches("[A-Za-z0-9_.-]+") && !name.contains(".."); }

    private static JSONObject chooseFile(JSONArray files) {
        JSONObject best = null; int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String format = file.optString("format");
            int w = file.optInt("width"), h = file.optInt("height");
            if (w <= 0 || h <= 0 || w > 4096 || h > 4096 || (long) w * h > 4_000_000 || file.optLong("size") > 2_097_152) continue;
            if (!format.equals("WEBP") && !format.equals("GIF") && !format.equals("PNG")) continue;
            int score = (format.equals("WEBP") ? 0 : 10000) + (h < 64 ? 1000 : 0) + Math.abs(h - 64);
            if (score < bestScore) { best = file; bestScore = score; }
        }
        return best;
    }

    static ChatEmote twitch(ChatMessage.Fragment fragment) {
        String base = "https://static-cdn.jtvnw.net/emoticons/v2/" + fragment.emoteId();
        return new ChatEmote(fragment.text(), base + "/default/dark/2.0", base + "/static/dark/2.0", 56, 56, fragment.animated(), false);
    }
}
