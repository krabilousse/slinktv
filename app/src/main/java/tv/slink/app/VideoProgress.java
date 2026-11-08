package tv.slink.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;

/** Local resume positions only; never stores playback URLs. */
final class VideoProgress {
    private final SharedPreferences preferences;
    VideoProgress(Context context) { preferences = context.getApplicationContext().getSharedPreferences("video_progress", 0); }
    long position(String owner, String video) {
        try { return new JSONObject(preferences.getString(owner + ":" + video, "{}")).optLong("position"); }
        catch (Exception ignored) { return 0; }
    }
    void save(String owner, String video, long position, long duration, boolean completeArchive) {
        if (owner.isEmpty() || !video.matches("[0-9]+") || duration <= 0) return;
        String key = owner + ":" + video;
        if (position < 10_000 || completeArchive && duration - position < 30_000) { preferences.edit().remove(key).apply(); return; }
        try {
            JSONObject value = new JSONObject().put("position", position).put("updated", System.currentTimeMillis());
            var edit = preferences.edit().putString(key, value.toString());
            var keys = new ArrayList<>(preferences.getAll().keySet());
            keys.sort(Comparator.comparingLong(this::updated));
            for (int i = 0; i < keys.size() - 199; i++) if (!keys.get(i).equals(key)) edit.remove(keys.get(i));
            edit.apply();
        } catch (Exception ignored) { }
    }
    private long updated(String key) {
        try { return new JSONObject(preferences.getString(key, "{}")).optLong("updated"); } catch (Exception ignored) { return 0; }
    }
    void clear(String owner) {
        var edit = preferences.edit();
        for (String key : preferences.getAll().keySet()) if (key.startsWith(owner + ":")) edit.remove(key); edit.apply();
    }
}
