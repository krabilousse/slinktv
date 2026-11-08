package tv.slink.app;

import org.json.JSONObject;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record VideoItem(String id, String channelId, String login, String name, String title, String thumbnail,
                 long createdAt, long duration, long views) {
    private static final Pattern DURATION = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?");
    static VideoItem parse(JSONObject item) throws Exception {
        String id = item.getString("id"), channel = item.getString("user_id");
        if (!id.matches("[1-9][0-9]{0,17}") || !channel.matches("[0-9]{1,30}")) throw new IllegalArgumentException("Invalid video ID");
        String thumbnail = item.optString("thumbnail_url").replace("%{width}", "640").replace("%{height}", "360")
                .replace("{width}", "640").replace("{height}", "360");
        return new VideoItem(id, channel, item.optString("user_login"), item.optString("user_name"), item.optString("title"),
                thumbnail, Instant.parse(item.getString("created_at")).toEpochMilli(), duration(item.optString("duration")), Math.max(0, item.optLong("view_count")));
    }
    static long duration(String value) {
        Matcher match = DURATION.matcher(value); if (!match.matches() || value.isEmpty()) return 0;
        long seconds = 0;
        try { for (int i = 1; i <= 3; i++) if (match.group(i) != null) seconds += Long.parseLong(match.group(i)) * (i == 1 ? 3600 : i == 2 ? 60 : 1); }
        catch (NumberFormatException ignored) { return 0; }
        return Math.min(seconds, 365 * 86400L) * 1000;
    }
    static String clock(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    String age(long now) {
        long hours = Math.max(0, (now - createdAt) / 3_600_000);
        return hours < 1 ? "Less than 1h ago" : hours < 24 ? hours + "h ago" : hours / 24 + "d ago";
    }
}
