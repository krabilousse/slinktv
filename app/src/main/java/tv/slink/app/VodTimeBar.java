package tv.slink.app;

import android.content.Context;
import android.view.KeyEvent;
import android.graphics.Rect;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.TimeBar;

/** D-pad repeat throttling; Media3 owns scrub preview, bounds, and accessibility. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class VodTimeBar extends DefaultTimeBar {
    private long lastStep;
    private long position, duration, target;
    private boolean pending;
    private TimeBar.OnScrubListener listener;
    VodTimeBar(Context context) {
        super(context); setKeyTimeIncrement(10_000); setPlayedColor(TvUi.ACCENT); setScrubberColor(TvUi.ACCENT);
        setBufferedColor(0xFF75818F); setUnplayedColor(0xFF344050); setFocusable(true);
        setContentDescription("Video timeline");
    }
    void listen(TimeBar.OnScrubListener listener) { this.listener = listener; addListener(listener); }
    @Override public void setPosition(long value) { position = value; super.setPosition(pending ? target : value); }
    @Override public void setDuration(long value) {
        duration = value; super.setDuration(value);
        if (pending) {
            target = Math.max(0, Math.min(Math.max(0, duration), target));
            super.setPosition(target); listener.onScrubMove(this, target);
        }
    }
    @Override public void setEnabled(boolean enabled) { if (!enabled && pending) finish(true); super.setEnabled(enabled); }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (!isEnabled() || duration <= 0) return true;
            long held = event.getEventTime() - event.getDownTime();
            if (event.getRepeatCount() > 0 && (held < 600 || event.getEventTime() - lastStep < 120)) return true;
            lastStep = event.getEventTime(); long step = event.getRepeatCount() == 0 ? 10_000 : seekStep(held, duration);
            long next = Math.max(0, Math.min(duration, (pending ? target : position) + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -step : step)));
            target = next;
            if (!pending) { pending = true; listener.onScrubStart(this, target); }
            else listener.onScrubMove(this, target);
            super.setPosition(target); return true;
        }
        if (pending && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) { finish(false); return true; }
        if (pending && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) finish(true);
        return super.onKeyDown(key, event);
    }
    static long seekStep(long held, long duration) {
        if (held >= 8000) return Math.max(60_000, duration / 60);
        if (held >= 5000) return Math.max(60_000, duration / 150);
        if (held >= 2000) return Math.max(30_000, duration / 300);
        return 30_000;
    }
    @Override public boolean onKeyUp(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        return super.onKeyUp(key, event);
    }
    @Override protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        if (!focused && pending) finish(true); super.onFocusChanged(focused, direction, previous);
    }
    private void finish(boolean cancelled) {
        pending = false;
        if (!cancelled) position = target;
        super.setPosition(position); listener.onScrubStop(this, target, cancelled);
    }
    void cancelScrub() { if (pending) finish(true); else { boolean enabled = isEnabled(); setEnabled(false); setEnabled(enabled); } }
}
