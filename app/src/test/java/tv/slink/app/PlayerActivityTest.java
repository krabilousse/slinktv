package tv.slink.app;

import android.content.Intent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.media3.ui.PlayerView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34, 35}, qualifiers = "w960dp-h540dp-land-xhdpi")
public class PlayerActivityTest {
    private Intent streamIntent() {
        return new Intent(RuntimeEnvironment.getApplication(), PlayerActivity.class)
                .putExtra("stream", "{\"channel\":\"Player test\",\"headers\":{},\"variants\":[{\"name\":\"Auto\",\"url\":\"https://example.invalid/test.m3u8\"}]}")
                .putExtra("quality", 0);
    }

    @Test public void selectingAStreamCreatesThePlayerScreen() {
        try (ActivityController<PlayerActivity> activity = Robolectric.buildActivity(PlayerActivity.class, streamIntent())) {
            activity.create();
            assertFalse(activity.get().isFinishing());
            FrameLayout content = activity.get().findViewById(android.R.id.content);
            FrameLayout screen = (FrameLayout) content.getChildAt(0);
            assertTrue(screen.getChildAt(0) instanceof PlayerView);
            assertNotNull(activity.get().getWindow().getInsetsController());
        }
    }

    @Test public void oneBackActionClosesPlayerEvenWithControlsVisible() {
        try (ActivityController<PlayerActivity> activity = Robolectric.buildActivity(PlayerActivity.class, streamIntent())) {
            activity.create().start().resume();
            assertFalse(activity.get().isFinishing());
            assertTrue(activity.get().getOnBackPressedDispatcher().hasEnabledCallbacks());
            activity.get().getOnBackPressedDispatcher().onBackPressed();
            assertTrue(activity.get().isFinishing());
        }
    }

    @Test public void playerStartsInsideActivityAndReleasesWhenLeaving() {
        try (ActivityController<PlayerActivity> activity = Robolectric.buildActivity(PlayerActivity.class, streamIntent())) {
            activity.create().start().resume();
            FrameLayout content = activity.get().findViewById(android.R.id.content);
            PlayerView view = (PlayerView) ((FrameLayout) content.getChildAt(0)).getChildAt(0);
            assertNotNull(view.getPlayer());
            activity.get().getOnBackPressedDispatcher().onBackPressed();
            activity.pause().stop();
            assertTrue(activity.get().isFinishing());
            assertNull(view.getPlayer());
        }
    }

    private FrameLayout screen(PlayerActivity activity) {
        FrameLayout content = activity.findViewById(android.R.id.content);
        return (FrameLayout) content.getChildAt(0);
    }

    private Button button(View view, String label) {
        if (view instanceof Button button && (label.contentEquals(button.getText()) || label.contentEquals(button.getContentDescription() == null ? "" : button.getContentDescription()))) return button;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Button found = button(group.getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }

    @Test public void hiddenControlsConsumeTheWholeWakePressBeforeOkPauses() {
        for (int key : new int[]{KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER}) {
            try (ActivityController<PlayerActivity> controller = Robolectric.buildActivity(PlayerActivity.class, streamIntent())) {
                controller.create().start().resume().visible();
                FrameLayout root = screen(controller.get());
                PlayerView player = (PlayerView) root.getChildAt(0);
                Button pause = button(root, "Pause");
                assertNotNull(pause);
                LinearLayout controls = (LinearLayout) pause.getParent().getParent();
                controls.setVisibility(View.GONE);
                root.requestFocus();
                assertTrue(player.getPlayer().getPlayWhenReady());
                assertTrue(root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)));
                assertTrue(root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key)));
                assertEquals(View.VISIBLE, controls.getVisibility());
                assertTrue(pause.hasFocus());
                assertTrue(player.getPlayer().getPlayWhenReady());
                root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
                assertFalse(player.getPlayer().getPlayWhenReady());
            }
        }
    }

    @Test public void mediaPauseActsImmediatelyEvenWhenControlsAreHidden() {
        try (ActivityController<PlayerActivity> controller = Robolectric.buildActivity(PlayerActivity.class, streamIntent())) {
            controller.create().start().resume().visible();
            FrameLayout root = screen(controller.get());
            PlayerView player = (PlayerView) root.getChildAt(0);
            Button pause = button(root, "Pause");
            ((View) pause.getParent().getParent()).setVisibility(View.GONE);
            root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
            root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
            assertFalse(player.getPlayer().getPlayWhenReady());
        }
    }

    @Test public void backDismissesQualityBeforeLeavingThePlayer() {
        try (ActivityController<PlayerActivity> controller = Robolectric.buildActivity(PlayerActivity.class, streamIntent())) {
            controller.create().start().resume().visible();
            button(screen(controller.get()), "Quality").performClick();
            controller.get().getOnBackPressedDispatcher().onBackPressed();
            assertFalse(controller.get().isFinishing());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(button(screen(controller.get()), "Quality").hasFocus());
            controller.get().getOnBackPressedDispatcher().onBackPressed();
            assertTrue(controller.get().isFinishing());
        }
    }

    @Test public void savedDefaultAppliesAndPortraitOptionsAreNeverShown() {
        PlaybackQuality.savePreference(RuntimeEnvironment.getApplication(), 720);
        Intent intent = streamIntent().putExtra("stream", "{\"channel\":\"test\",\"variants\":["
                + "{\"name\":\"2160p60_portrait\",\"url\":\"https://example.invalid/portrait\"},"
                + "{\"name\":\"1080p60\",\"url\":\"https://example.invalid/high\"},"
                + "{\"name\":\"720p60\",\"url\":\"https://example.invalid/medium\"}]}");
        try (var controller = Robolectric.buildActivity(PlayerActivity.class, intent)) {
            controller.create().start().resume().visible();
            PlayerView player = (PlayerView) screen(controller.get()).getChildAt(0);
            assertEquals("https://example.invalid/medium", player.getPlayer().getCurrentMediaItem().localConfiguration.uri.toString());
            button(screen(controller.get()), "Quality").performClick();
            var list = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getListView();
            assertEquals(2, list.getAdapter().getCount());
            assertEquals("1080P · 60 FPS (best)", list.getAdapter().getItem(0));
            assertEquals(1, list.getCheckedItemPosition());
            list.performItemClick(null, 0, 0);
            assertEquals("https://example.invalid/high", player.getPlayer().getCurrentMediaItem().localConfiguration.uri.toString());
            assertEquals(720, PlaybackQuality.preference(controller.get()));
        }
    }
}
