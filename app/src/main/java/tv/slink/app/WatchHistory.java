package tv.slink.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.Map;

/** Per-account viewing totals, kept only on this device. */
final class WatchHistory {
    private static final Object LOCK = new Object();
    private static final long DAY_MS = 86_400_000L;
    private static final long DAILY_CAP_MS = 120 * 60_000L;
    private final SharedPreferences preferences;
    private final Clock clock;

    interface Clock {
        long elapsedMillis();
        long wallMillis();
    }

    WatchHistory(Context context) {
        this(context, new Clock() {
            @Override public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
            @Override public long wallMillis() { return System.currentTimeMillis(); }
        });
    }

    WatchHistory(Context context, Clock clock) {
        preferences = context.getApplicationContext().getSharedPreferences("watch_history", Context.MODE_PRIVATE);
        this.clock = clock;
    }

    boolean isEnabled(String account) {
        synchronized (LOCK) { return !account.isEmpty() && preferences.getBoolean("enabled:" + account, false); }
    }

    void setEnabled(String account, boolean enabled) {
        if (account.isEmpty()) return;
        synchronized (LOCK) {
            if (enabled == isEnabled(account)) return;
            SharedPreferences.Editor editor = preferences.edit().putBoolean("enabled:" + account, enabled)
                    .putLong("epoch:" + account, epoch(account) + 1);
            if (!enabled) editor.putBoolean("relevant:" + account, false);
            editor.apply();
        }
    }

    boolean isRelevant(String account) {
        synchronized (LOCK) { return isEnabled(account) && preferences.getBoolean("relevant:" + account, false); }
    }

    void setRelevant(String account, boolean relevant) {
        if (account.isEmpty()) return;
        synchronized (LOCK) {
            if (relevant) setEnabled(account, true);
            preferences.edit().putBoolean("relevant:" + account, relevant).apply();
        }
    }

    void clear(String account) {
        if (account.isEmpty()) return;
        synchronized (LOCK) {
            SharedPreferences.Editor editor = preferences.edit();
            String prefix = "watch_ms:" + account + ":";
            for (String key : preferences.getAll().keySet()) if (key.startsWith(prefix)) editor.remove(key);
            editor.putBoolean("enabled:" + account, false).putBoolean("relevant:" + account, false)
                    .putLong("epoch:" + account, epoch(account) + 1).apply();
        }
    }

    double score(String account, String broadcaster) {
        if (account.isEmpty() || broadcaster.isEmpty()) return 0;
        synchronized (LOCK) {
            long today = Math.floorDiv(clock.wallMillis(), DAY_MS);
            prune(account, today);
            String prefix = "watch_ms:" + account + ":" + broadcaster + ":";
            double score = 0;
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (!entry.getKey().startsWith(prefix) || !(entry.getValue() instanceof Long millis)) continue;
                long age = today - dayOf(entry.getKey());
                if (age >= 0 && age < 90) score += Math.min(millis, DAILY_CAP_MS) / 60_000.0 * Math.pow(2, -age / 14.0);
            }
            return score;
        }
    }

    Tracker tracker(String account, String broadcaster) { return new Tracker(account, broadcaster); }

    private long epoch(String account) { return preferences.getLong("epoch:" + account, 0); }
    private static long dayOf(String key) { return Long.parseLong(key.substring(key.lastIndexOf(':') + 1)); }

    private void prune(String account, long today) {
        SharedPreferences.Editor editor = null;
        String prefix = "watch_ms:" + account + ":";
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith(prefix)) continue;
            long age = today - dayOf(key);
            if (age < 0 || age >= 90) {
                if (editor == null) editor = preferences.edit();
                editor.remove(key);
            }
        }
        if (editor != null) editor.apply();
    }

    private void record(String account, String broadcaster, long wallStart, long elapsed) {
        long today = Math.floorDiv(clock.wallMillis(), DAY_MS);
        prune(account, today);
        SharedPreferences.Editor editor = preferences.edit();
        while (elapsed > 0) {
            long day = Math.floorDiv(wallStart, DAY_MS);
            long segment = Math.min(elapsed, DAY_MS - Math.floorMod(wallStart, DAY_MS));
            if (day <= today && today - day < 90) {
                String key = "watch_ms:" + account + ":" + broadcaster + ":" + day;
                editor.putLong(key, Math.min(DAILY_CAP_MS, preferences.getLong(key, 0) + segment));
            }
            wallStart += segment;
            elapsed -= segment;
        }
        editor.apply();
    }

    /** A tracker is permanently invalidated when its account's history is cleared or switched off. */
    final class Tracker {
        private final String account;
        private final String broadcaster;
        private final long epoch;
        private long elapsedStart;
        private long wallStart;
        private boolean eligible;
        private boolean invalidated;

        private Tracker(String account, String broadcaster) {
            this.account = account;
            this.broadcaster = broadcaster;
            synchronized (LOCK) { epoch = epoch(account); }
        }

        void update(boolean isPlaying, boolean visible, boolean screenOn) {
            synchronized (LOCK) {
                if (invalidated || epoch != epoch(account)) {
                    invalidated = true;
                    eligible = false;
                    return;
                }
                long elapsedNow = clock.elapsedMillis(), wallNow = clock.wallMillis();
                long elapsed = elapsedNow - elapsedStart;
                // Do not assign an interval to an arbitrary UTC day after a wall-clock jump.
                if (eligible && elapsed > 0 && Math.abs((wallNow - wallStart) - elapsed) <= 2_000)
                    record(account, broadcaster, wallStart, elapsed);
                elapsedStart = elapsedNow;
                wallStart = wallNow;
                eligible = isPlaying && visible && screenOn && !broadcaster.isEmpty() && isEnabled(account);
            }
        }

        void stop() { update(false, false, false); }
    }
}
