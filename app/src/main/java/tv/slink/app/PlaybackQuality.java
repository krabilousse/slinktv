package tv.slink.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PlaybackQuality {
    static final int[] LIMITS = {0, 2160, 1440, 1080, 720, 480, 360, 160};
    private static final Pattern RESOLUTION = Pattern.compile("^(\\d+)[pP](\\d*)(?:_.*)?$");

    static int preference(Context context) {
        return context.getSharedPreferences("playback", Context.MODE_PRIVATE).getInt("resolution_limit", 0);
    }

    static void savePreference(Context context, int limit) {
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().putInt("resolution_limit", limit).apply();
    }

    static String preferenceLabel(int limit) { return limit == 0 ? "Best available" : "Up to " + limit + "P"; }

    static JSONArray landscapeVariants(JSONArray source) throws JSONException {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject variant = source.getJSONObject(i);
            if (!variant.getString("name").toLowerCase(Locale.ROOT).contains("_portrait")) result.put(variant);
        }
        if (result.length() == 0) throw new JSONException("No landscape stream quality available");
        return result;
    }

    static int choose(JSONArray variants, int limit) {
        int chosen = -1, lowest = -1;
        for (int i = 0; i < variants.length(); i++) {
            String name = name(variants, i);
            int height = height(name);
            if (height == 0) continue;
            if (lowest < 0 || height < height(name(variants, lowest))
                    || height == height(name(variants, lowest)) && rank(name) > rank(name(variants, lowest))) lowest = i;
            if ((limit == 0 || height <= limit) && (chosen < 0 || rank(name) > rank(name(variants, chosen)))) chosen = i;
        }
        if (chosen >= 0) return chosen;
        if (lowest >= 0) return lowest;
        for (int i = 0; i < variants.length(); i++) if (!name(variants, i).equalsIgnoreCase("audio_only")) return i;
        return 0;
    }

    static String label(JSONArray variants, int index) {
        String raw = name(variants, index);
        Matcher match = RESOLUTION.matcher(raw);
        String text;
        if (match.matches()) {
            text = match.group(1) + "P";
            if (!match.group(2).isEmpty()) text += " · " + match.group(2) + " FPS";
        } else {
            text = switch (raw.toLowerCase(Locale.ROOT)) {
                case "audio_only" -> "Audio only";
                case "auto" -> "Automatic";
                case "source", "chunked" -> "Source";
                default -> raw.replace('_', ' ');
            };
        }
        return height(raw) > 0 && index == choose(variants, 0) ? text + " (best)" : text;
    }

    private static String name(JSONArray variants, int index) { return variants.optJSONObject(index).optString("name"); }
    private static int height(String name) {
        Matcher match = RESOLUTION.matcher(name);
        return match.matches() ? Integer.parseInt(match.group(1)) : 0;
    }
    private static long rank(String name) {
        Matcher match = RESOLUTION.matcher(name);
        return match.matches() ? Long.parseLong(match.group(1)) * 1000 + (match.group(2).isEmpty() ? 0 : Integer.parseInt(match.group(2))) : 0;
    }
    private PlaybackQuality() {}
}
