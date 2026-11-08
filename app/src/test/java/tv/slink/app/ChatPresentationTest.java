package tv.slink.app;

import android.content.Intent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import androidx.media3.ui.PlayerView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34, 35}, qualifiers = "w960dp-h540dp-land-xhdpi")
public class ChatPresentationTest {
    private Intent intent() {
        return new Intent(RuntimeEnvironment.getApplication(), PlayerActivity.class).putExtra("broadcaster_id", "123")
                .putExtra("display_name", "Linkus7").putExtra("stream_title", "Stream title")
                .putExtra("stream", "{\"channel\":\"linkus7\",\"variants\":[{\"name\":\"1080p60\",\"url\":\"https://example.invalid/live\"}]}");
    }
    private FrameLayout screen(PlayerActivity activity) { return (FrameLayout) ((FrameLayout) activity.findViewById(android.R.id.content)).getChildAt(0); }
    private Button button(View root, String label) {
        if (root instanceof Button b && (label.contentEquals(b.getText()) || label.contentEquals(b.getContentDescription() == null ? "" : b.getContentDescription()))) return b;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Button b = button(group.getChildAt(i), label); if (b != null) return b;
        }
        return null;
    }
    private ChatPanel chat(PlayerActivity activity) throws Exception {
        var field = PlayerActivity.class.getDeclaredField("chat"); field.setAccessible(true); return (ChatPanel) field.get(activity);
    }
    @Test public void placementAndToggleKeepTheSameVideoPlayerAndRestoreLastVisibleMode() throws Exception {
        try (var activity = Robolectric.buildActivity(PlayerActivity.class, intent()).create().start().resume().visible()) {
            FrameLayout root = screen(activity.get()); PlayerView view = (PlayerView) root.getChildAt(0); var player = view.getPlayer();
            ChatPanel chat = chat(activity.get()); assertEquals(ChatPanel.Mode.DOCKED, chat.mode()); assertTrue(chat.videoInset() > 0);
            chat.setMode(ChatPanel.Mode.OVERLAY); assertEquals(0, chat.videoInset());
            root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PROG_YELLOW));
            root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PROG_YELLOW));
            assertEquals(ChatPanel.Mode.HIDDEN, chat.mode()); assertNotNull(button(root, "Show chat"));
            button(root, "Show chat").performClick(); assertEquals(ChatPanel.Mode.OVERLAY, chat.mode());
            assertSame(player, view.getPlayer()); assertTrue(player.getPlayWhenReady());
        }
    }
    @Test public void holdingOkFromVideoTogglesChatWithoutPausingAndBackClosesOptionsFirst() throws Exception {
        try (var activity = Robolectric.buildActivity(PlayerActivity.class, intent()).create().start().resume().visible()) {
            FrameLayout root = screen(activity.get()); PlayerView video = (PlayerView) root.getChildAt(0);
            ((View) button(root, "Pause").getParent().getParent()).setVisibility(View.GONE);
            root.dispatchKeyEvent(new KeyEvent(1000, 1000, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
            root.dispatchKeyEvent(new KeyEvent(1000, 1700, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1));
            root.dispatchKeyEvent(new KeyEvent(1000, 1800, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));
            assertEquals(ChatPanel.Mode.HIDDEN, chat(activity.get()).mode()); assertTrue(video.getPlayer().getPlayWhenReady());
            button(root, "Chat options").performClick(); assertTrue(chat(activity.get()).optionsOpen());
            activity.get().getOnBackPressedDispatcher().onBackPressed();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(chat(activity.get()).optionsOpen()); assertFalse(activity.get().isFinishing());
        }
    }
    @Test public void optionsSavePlacementOpacityTextSizeAndAnimationChoice() throws Exception {
        try (var activity = Robolectric.buildActivity(PlayerActivity.class, intent()).create().start().resume().visible()) {
            button(screen(activity.get()), "Chat options").performClick();
            android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            View options = dialog.getWindow().getDecorView();
            button(options, "Overlay").performClick();
            android.widget.SeekBar opacity = seekBar(options);
            assertTrue(opacity.isEnabled()); opacity.setProgress(30);
            button(options, "Medium").performClick(); button(options, "On").performClick();
            var saved = activity.get().getSharedPreferences("chat", 0);
            assertEquals("OVERLAY", saved.getString("mode", "")); assertEquals(30, saved.getInt("opacity", -1));
            assertEquals(18, saved.getInt("text_size", -1)); assertFalse(saved.getBoolean("animations", true));
            button(options, "Docked").performClick(); assertFalse(opacity.isEnabled());
        }
    }
    @Test public void upHidesControlsImmediatelyEvenWhenPausedAndDoesNotReopenOnRelease() throws Exception {
        try(var activity=Robolectric.buildActivity(PlayerActivity.class,intent()).create().start().resume().visible()) {
            FrameLayout root=screen(activity.get()); ((PlayerView)root.getChildAt(0)).getPlayer().pause();
            View controls=(View)button(root,"Play").getParent().getParent();assertEquals(View.VISIBLE,controls.getVisibility());
            root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));
            assertEquals(View.GONE,controls.getVisibility());
            root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_UP));
            assertEquals(View.GONE,controls.getVisibility());assertFalse(activity.get().isFinishing());
        }
    }
    @Test @Config(sdk=34) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void compactChatMovesAcrossCornersAndDockingReservesTheChosenSide() throws Exception {
        try(var activity=Robolectric.buildActivity(PlayerActivity.class,intent()).create().start().resume().visible()) {
            FrameLayout root=screen(activity.get());VideosScreenTest.layout(root);
            ChatPanel chat=chat(activity.get());chat.setMode(ChatPanel.Mode.OVERLAY);chat.setControlsInset(0);
            chat.showOptions(()->{});var dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();View options=dialog.getWindow().getDecorView();
            button(options,"Top right").performClick();VideosScreenTest.layout(root);
            View panel=root.getChildAt(1);assertTrue(panel.getWidth()<root.getWidth()*.26);assertEquals(root.getHeight()/2,panel.getHeight());
            assertTrue(panel.getLeft()<TvUi.dp(activity.get(),20));assertTrue(panel.getTop()>root.getHeight()/3);
            button(options,"Bottom left").performClick();VideosScreenTest.layout(root);assertTrue(panel.getLeft()>root.getWidth()/2);
            button(options,"Bottom right").performClick();button(options,"Docked").performClick();VideosScreenTest.layout(root);
            assertTrue(chat.videoInsetStart()>0);assertEquals(0,chat.videoInset());
            Button pause=button(root,"Pause");View controls=(View)pause.getParent().getParent();assertEquals(panel.getWidth(),controls.getLeft());
            View decor=dialog.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(TvUi.dp(activity.get(),440),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(TvUi.dp(activity.get(),508),View.MeasureSpec.EXACTLY));decor.layout(0,0,decor.getMeasuredWidth(),decor.getMeasuredHeight());
            var bitmap=android.graphics.Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);decor.draw(new android.graphics.Canvas(bitmap));
            var path=java.nio.file.Path.of("build/reports/ui/chat-settings-0.6.0.png");java.nio.file.Files.createDirectories(path.getParent());try(var output=java.nio.file.Files.newOutputStream(path)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}bitmap.recycle();
        }
    }
    private android.widget.SeekBar seekBar(View root) {
        if (root instanceof android.widget.SeekBar slider) return slider;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            var slider = seekBar(group.getChildAt(i)); if (slider != null) return slider;
        }
        return null;
    }
    @Test public void enablingChatOpensTheQrUpgradeFlow() throws Exception {
        try (var activity = Robolectric.buildActivity(PlayerActivity.class, intent()).create().start().resume().visible()) {
            button(screen(activity.get()), "Enable chat").performClick();
            Intent next = org.robolectric.Shadows.shadowOf(activity.get()).getNextStartedActivity();
            assertEquals(LoginActivity.class.getName(), next.getComponent().getClassName());
            assertTrue(next.getBooleanExtra("enable_chat", false)); assertTrue(next.getBooleanExtra("auto_login", false));
        }
    }
    @Test @Config(sdk = 34) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void dockedControlsFitAndOverlayKeepsTextOpaque() throws Exception {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        try (var activity = Robolectric.buildActivity(PlayerActivity.class, intent()).create().start().resume().visible()) {
            FrameLayout root = screen(activity.get());
            ((PlayerView) root.getChildAt(0)).getPlayer().stop();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1920, 1080); root.getViewTreeObserver().dispatchOnPreDraw();
            for (String label : new String[]{"Pause", "Catch up to live", "Quality", "Hide chat", "Chat options", "Retry", "Channels"}) {
                Button button = button(root, label);
                assertTrue(label + " is outside its row", button.getRight() <= ((View) button.getParent()).getWidth());
                assertTrue(button.getWidth() >= TvUi.dp(activity.get(), 44));
                assertEquals(label, button.getContentDescription());
            }
            assertTrue(((View) button(root, "Pause").getParent().getParent()).getHeight() < TvUi.dp(activity.get(), 225));
            capture(root, "player-docked-0.6.0.png");
            chat(activity.get()).setMode(ChatPanel.Mode.OVERLAY);
            assertEquals(1f, root.getChildAt(1).getAlpha(), 0);
            assertEquals(0, chat(activity.get()).videoInset());
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1920, 1080); capture(root, "player-overlay-0.6.0.png");
            ((View) button(root, "Pause").getParent().getParent()).setVisibility(View.GONE);
            chat(activity.get()).setControlsInset(0);
            fixtureMessages(chat(activity.get()), root);
            capture(root, "chat-messages-0.6.0.png");
        }
    }
    private Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    @SuppressWarnings("unchecked")
    private void fixtureMessages(ChatPanel panel, FrameLayout root) throws Exception {
        ((AuthViewModel) field(panel, "account")).state().removeObservers((PlayerActivity) root.getContext());
        ChatAdapter adapter = (ChatAdapter) field(panel, "adapter");
        var list = (androidx.recyclerview.widget.RecyclerView) field(panel, "messages");
        var cache = (android.util.LruCache<String, byte[]>) field(field(panel, "emotes"), "encoded");
        var emotes = new java.util.HashMap<String, ChatEmote>();
        for (String name : new String[]{"Clap", "7Cinema"}) {
            String url = "https://example.invalid/" + name;
            try (var fixture = getClass().getResourceAsStream("/chat/" + name + ".webp")) { cache.put(url, fixture.readAllBytes()); }
            emotes.put(name, new ChatEmote(name, url, url, name.equals("7Cinema") ? 170 : 44, 64, name.equals("Clap"), false));
        }
        adapter.setEmotes(emotes);
        var rows = new java.util.ArrayList<ChatMessage>();
        String[] texts = {"That was close Clap", "7Cinema", "Great run!", "Hello from the TV! Clap Clap", "Wide emotes keep their proportions 7Cinema"};
        for (int i = 0; i < texts.length; i++) rows.add(ChatMessage.parse(ChatLogTest.message("fixture-" + i, "viewer-" + i, texts[i]), i + 1));
        ((View) field(panel, "status")).setVisibility(View.GONE);
        ((View) field(panel, "authorize")).setVisibility(View.GONE);
        adapter.submit(rows); list.scrollToPosition(rows.size() - 1);
        for (int attempt = 0; attempt < 100; attempt++) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1920, 1080); root.getViewTreeObserver().dispatchOnPreDraw();
            boolean ready = list.getChildCount() > 0;
            for (int i = 0; i < list.getChildCount(); i++) {
                var row = (ChatAdapter.Row) list.getChildViewHolder(list.getChildAt(i));
                for (var span : row.spans) ready &= span.images.size() == span.emotes.size() && span.images.stream().allMatch(java.util.Objects::nonNull);
            }
            if (ready) return;
            Thread.sleep(10);
        }
        capture(root, "chat-fixture-failure.png");
        fail("Fixture emotes did not finish rendering: rows=" + list.getChildCount() + ", messages=" + adapter.getItemCount());
    }
    private void capture(View root, String filename) throws Exception {
        invalidateTree(root); root.getViewTreeObserver().dispatchOnPreDraw();
        var bitmap = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        var output = java.nio.file.Path.of("build/reports/ui/" + filename); java.nio.file.Files.createDirectories(output.getParent());
        try (var file = java.nio.file.Files.newOutputStream(output)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file); }
        bitmap.recycle();
    }
    private void invalidateTree(View view) {
        view.invalidate();
        if (view instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) invalidateTree(group.getChildAt(i));
    }
}
