package tv.slink.app;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34, 35}, qualifiers = "w960dp-h540dp-land-xhdpi")
public class StreamWallTest {
    private Button button(View root, String label) {
        if (root instanceof Button b && label.contentEquals(b.getText())) return b;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Button b = button(group.getChildAt(i), label); if (b != null) return b;
        }
        return null;
    }

    private RecyclerView grid(View root) {
        if (root instanceof RecyclerView view) return view;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            RecyclerView view = grid(group.getChildAt(i)); if (view != null) return view;
        }
        return null;
    }

    @Test public void twoRowsFitAndGuestPaginationFailureKeepsLoadedCards() throws Exception {
        try (var activity = Robolectric.buildActivity(MainActivity.class).create()) {
            View root = activity.get().findViewById(android.R.id.content);
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1920, 1080);
            RecyclerView grid = grid(root);
            View sixth = grid.findViewHolderForAdapterPosition(5).itemView;
            assertTrue("Two complete rows must fit on the TV home screen", sixth.getBottom() <= grid.getHeight() - grid.getPaddingBottom());
            var complete = MainActivity.class.getDeclaredMethod("complete", int.class, boolean.class, TwitchApi.StreamPage.class, String.class, boolean.class);
            complete.setAccessible(true);
            var item = new StreamChannel("1", "linkus7", "Linkus7", "Live title", "Zelda", "", "", 1234, "en");
            complete.invoke(activity.get(), 0, false, new TwitchApi.StreamPage(List.of(item), "next"), null, false);
            assertEquals(1, grid.getAdapter().getItemCount());
            assertNull(button(root, "More"));
            assertNull(button(root, "Browse"));
            complete.invoke(activity.get(), 0, true, null, "Sign in to load more streams.", true);
            assertEquals(1, grid.getAdapter().getItemCount());
            button(root, "Sign in").performClick();
            var intent = org.robolectric.Shadows.shadowOf(activity.get()).getNextStartedActivity();
            assertEquals(LoginActivity.class.getName(), intent.getComponent().getClassName());
            assertTrue(intent.getBooleanExtra("auto_login", false));
        }
    }
    @Test public void homeCreatesSkeletonsAndSettingsSearchAreReachable() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).create()) {
            View root = activity.get().findViewById(android.R.id.content);
            assertNotNull(button(root, "Home")); assertNotNull(button(root, "Search"));
            button(root, "Settings").performClick();
            assertNotNull(button(root, "Sign in with QR"));
            button(root, "Search").performClick();
            assertNotNull(button(root, "Open channel"));
            assertFalse(activity.get().isFinishing());
        }
    }
    @Test public void shortPagesKeepLoadingWithoutNeedingAScrollOrMoreButton() throws Exception {
        try (var activity = Robolectric.buildActivity(MainActivity.class).create()) {
            View root = activity.get().findViewById(android.R.id.content);
            var complete = MainActivity.class.getDeclaredMethod("complete", int.class, boolean.class, TwitchApi.StreamPage.class, String.class, boolean.class);
            complete.setAccessible(true);
            var item = new StreamChannel("1", "linkus7", "Linkus7", "", "", "", "", 100, "en");
            complete.invoke(activity.get(), 0, false, new TwitchApi.StreamPage(List.of(item), "next-page"), null, false);
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1920, 1080);
            grid(root).getViewTreeObserver().dispatchOnPreDraw();
            var generation = MainActivity.class.getDeclaredField("generation"); generation.setAccessible(true);
            assertEquals(1, generation.getInt(activity.get()));
            assertEquals(1, grid(root).getAdapter().getItemCount());
            assertNull(button(root, "More"));
        }
    }
    @Test public void skeletonAndRealCardHaveIdenticalBounds() {
        var context = RuntimeEnvironment.getApplication();
        FrameLayout parent = new FrameLayout(context);
        StreamWallAdapter adapter = new StreamWallAdapter(600, item -> {});
        var holder = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder, 0);
        holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int skeletonHeight = holder.itemView.getMeasuredHeight();
        assertEquals(12, adapter.getItemCount()); assertFalse(holder.itemView.isFocusable());
        adapter.submit(List.of(new StreamChannel("1", "linkus7", "Linkus7", "Live title", "Zelda", "", "", 1234, "en")));
        adapter.onBindViewHolder(holder, 0);
        holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(skeletonHeight, holder.itemView.getMeasuredHeight());
        assertTrue(holder.itemView.isFocusable()); assertEquals(View.VISIBLE, holder.live.getVisibility());
        assertTrue(holder.itemView.getContentDescription().toString().contains("1234 viewers"));
    }

    @Test public void loadingStaysOnItsCardAndClearsAfterRecyclingOrCancel() {
        var context = RuntimeEnvironment.getApplication();
        StreamWallAdapter adapter = new StreamWallAdapter(600, item -> {});
        adapter.submit(List.of(
                new StreamChannel("1", "linkus7", "Linkus7", "", "", "", "", 100, "en"),
                new StreamChannel("2", "other", "Other", "", "", "", "", 10, "en")));
        var holder = adapter.onCreateViewHolder(new FrameLayout(context), 0);
        adapter.setLoadingChannel("linkus7"); adapter.onBindViewHolder(holder, 0);
        assertEquals(View.VISIBLE, holder.loading.getVisibility());
        assertTrue(((android.widget.ProgressBar) holder.loading.getChildAt(0)).isIndeterminate());
        int height = holder.itemView.getLayoutParams().height;
        adapter.onBindViewHolder(holder, 1);
        assertEquals(View.GONE, holder.loading.getVisibility());
        adapter.onBindViewHolder(holder, 0);
        adapter.setLoadingChannel(""); adapter.onBindViewHolder(holder, 0, List.of("loading"));
        assertEquals(View.GONE, holder.loading.getVisibility());
        assertEquals(height, holder.itemView.getLayoutParams().height);
    }

    @Test public void defaultQualityCanBeChangedFromSettingsAndPersists() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).create()) {
            View root = activity.get().findViewById(android.R.id.content);
            button(root, "Settings").performClick();
            root.findViewWithTag("quality").performClick();
            android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            var list = dialog.getListView();
            int target = -1;
            for (int i = 0; i < list.getAdapter().getCount(); i++) {
                if ("Up to 720P".equals(list.getAdapter().getItem(i).toString())) target = i;
            }
            assertTrue(target >= 0);
            list.performItemClick(null, target, target);
            assertEquals(720, PlaybackQuality.preference(activity.get()));
            root.findViewWithTag("quality").performClick();
            assertEquals(target, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getListView().getCheckedItemPosition());
        }
    }

    private boolean showsText(View root, String text) {
        if (root instanceof android.widget.TextView view && text.contentEquals(view.getText())) return true;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) if (showsText(group.getChildAt(i), text)) return true;
        return false;
    }

    private android.widget.EditText editText(View root) {
        if (root instanceof android.widget.EditText input) return input;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            android.widget.EditText input = editText(group.getChildAt(i)); if (input != null) return input;
        }
        return null;
    }

    @Test public void adFreeProxyCanBeToggledAndEditedFromSettingsAndPersists() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).create().visible()) {
            View root = activity.get().findViewById(android.R.id.content);
            button(root, "Settings").performClick();
            root.findViewWithTag("ad_proxy").performClick();
            android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            Button toggle = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            assertEquals("Turn on", toggle.getText().toString());
            toggle.performClick();
            assertTrue(new AdFreeProxy(activity.get()).enabled());
            assertTrue(dialog.isShowing());
            assertEquals("Turn off", toggle.getText().toString());
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertTrue(showsText(root, "On · 12 servers"));
            assertTrue(root.findViewWithTag("ad_proxy").isFocused());

            root.findViewWithTag("ad_proxy").performClick();
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            android.app.AlertDialog editor = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            android.widget.EditText input = editText(editor.getWindow().getDecorView());
            assertTrue(input.getText().toString().startsWith("https://eu.luminous.dev/live/$channel\n"));
            input.setText("http://insecure.example/live/$channel");
            editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(editor.isShowing());
            assertNotNull(input.getError());
            input.setText("as.luminous.dev/live/$channel");
            editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertFalse(editor.isShowing());
            assertTrue(showsText(root, "On · 1 server"));
            assertEquals(List.of("https://as.luminous.dev/live/$channel"), new AdFreeProxy(activity.get()).servers());
        }
    }

    @Test public void leavingTheWallCancelsCardLoadingAndIgnoresItsLateResult() throws Exception {
        try (var activity = Robolectric.buildActivity(MainActivity.class).create()) {
            var workerField = MainActivity.class.getDeclaredField("resolver"); workerField.setAccessible(true);
            var worker = (java.util.concurrent.ExecutorService) workerField.get(activity.get());
            var release = new java.util.concurrent.CountDownLatch(1);
            worker.execute(() -> {
                try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            try {
                View root = activity.get().findViewById(android.R.id.content);
                StreamWallAdapter adapter = (StreamWallAdapter) grid(root).getAdapter();
                var item = new StreamChannel("1", "linkus7", "Linkus7", "", "", "", "", 100, "en");
                adapter.submit(List.of(item));
                var holder = adapter.onCreateViewHolder(new FrameLayout(activity.get()), 0);
                adapter.onBindViewHolder(holder, 0);
                holder.itemView.performClick(); adapter.onBindViewHolder(holder, 0);
                assertEquals(View.VISIBLE, holder.loading.getVisibility());
                assertNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
                button(root, "Settings").performClick(); adapter.onBindViewHolder(holder, 0);
                assertEquals(View.GONE, holder.loading.getVisibility());
                var complete = MainActivity.class.getDeclaredMethod("completePlayback", int.class, StreamChannel.class, String.class, String.class);
                complete.setAccessible(true);
                complete.invoke(activity.get(), 1, item, "", "{\"channel\":\"linkus7\",\"variants\":[{\"name\":\"1080p60\",\"url\":\"https://example.invalid/live\"}]}");
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertNull(org.robolectric.Shadows.shadowOf(activity.get()).getNextStartedActivity());
                assertNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
                assertNotNull(root.findViewWithTag("quality"));
            } finally { worker.shutdownNow(); release.countDown(); }
        }
    }

    @Test @Config(sdk = 34)
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void signedInPillsHaveSeparateSelectionAndFocusAndFitOnTv() throws Exception {
        try (var activity = Robolectric.buildActivity(MainActivity.class).create()) {
            var account = MainActivity.class.getDeclaredField("account"); account.setAccessible(true);
            ((AuthViewModel) account.get(activity.get())).state().removeObservers(activity.get());
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
            activity.start().resume().visible();
            var id = MainActivity.class.getDeclaredField("accountId"); id.setAccessible(true); id.set(activity.get(), "test-user");
            var header = MainActivity.class.getDeclaredMethod("updateHeader"); header.setAccessible(true); header.invoke(activity.get());
            new WatchHistory(activity.get()).setEnabled("test-user", true);
            View root = activity.get().findViewById(android.R.id.content);
            button(root, "Relevant").performClick();
            assertTrue(button(root, "Relevant").isSelected());
            assertFalse(button(root, "Most viewers").isSelected());
            button(root, "Most viewers").requestFocus();
            assertTrue(button(root, "Relevant").isSelected());
            assertTrue(button(root, "Following").isSelected());
            ((StreamWallAdapter) grid(root).getAdapter()).showLoading();
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1920, 1080);
            root.getViewTreeObserver().dispatchOnPreDraw();
            View feed = (View) button(root, "Following").getParent();
            View sorting = (View) button(root, "Most viewers").getParent();
            assertTrue(sorting.getLeft() - feed.getRight() >= TvUi.dp(activity.get(), 24));
            assertTrue(button(root, "Refresh").getRight() <= ((View) button(root, "Refresh").getParent()).getWidth());
            for (String label : new String[]{"Following", "Popular", "Most viewers", "Relevant"}) {
                Button segment = button(root, label);
                assertTrue(segment.getPaint().measureText(label) > 0);
                assertTrue(segment.getLayout().getWidth() <= segment.getWidth());
                assertTrue(segment.getPaint().measureText(label) <= segment.getWidth() - segment.getPaddingLeft() - segment.getPaddingRight());
            }
            assertFalse(root.isInTouchMode());
            assertTrue(button(root, "Most viewers").requestFocus());
            assertTrue(button(root, "Relevant").isSelected());
            assertSame(button(root, "Popular"), button(root, "Following").focusSearch(View.FOCUS_RIGHT));
            assertSame(button(root, "Relevant"), button(root, "Most viewers").focusSearch(View.FOCUS_RIGHT));
            var bitmap = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888);
            root.draw(new android.graphics.Canvas(bitmap));
            java.nio.file.Path output = java.nio.file.Path.of("build/reports/ui/stream-wall-0.3.1.png");
            java.nio.file.Files.createDirectories(output.getParent());
            try (var file = java.nio.file.Files.newOutputStream(output)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file); }
            bitmap.recycle();
        }
    }
    @Test public void helixCardsDeduplicateChannelsAndKeepPreviewSeparateFromAvatar() throws Exception {
        var http = new TwitchApiTest.FakeHttp().reply(200, "{\"data\":[{\"id\":\"1\",\"profile_image_url\":\"https://avatar.example/image\"}]}");
        JSONArray rows = new JSONArray("[{\"user_id\":\"1\",\"user_login\":\"linkus7\",\"user_name\":\"Linkus7\",\"thumbnail_url\":\"https://preview.example/{width}x{height}\",\"viewer_count\":10},{\"user_id\":\"1\",\"user_login\":\"linkus7\"}]");
        var result = new TwitchApi(http).cards(new TwitchApi.Session("client", "token", "refresh", 0), rows);
        assertEquals(1, result.size()); assertEquals("https://preview.example/640x360", result.get(0).thumbnail());
        assertEquals("https://avatar.example/image", result.get(0).avatar());
        assertEquals(1, http.requests.size());
    }
    @Test public void searchEnrichesLiveResultsAndPreservesPagination() throws Exception {
        var http = new TwitchApiTest.FakeHttp().reply(200, "{\"data\":[{\"id\":\"42\"}],\"pagination\":{\"cursor\":\"next\"}}")
                .reply(200, "{\"data\":[{\"user_id\":\"42\",\"user_login\":\"channel\",\"viewer_count\":9}]}")
                .reply(200, "{\"data\":[{\"id\":\"42\",\"profile_image_url\":\"https://example/avatar\"}]}");
        var page = new TwitchApi(http).search(new TwitchApi.Session("client", "token", "refresh", 0), "zelda & link", "page+1");
        assertEquals("next", page.cursor()); assertEquals(9, page.items().get(0).viewers());
        assertTrue(http.requests.get(0).url().contains("query=zelda+%26+link"));
        assertTrue(http.requests.get(0).url().contains("after=page%2B1"));
        assertTrue(http.requests.get(1).url().contains("user_id=42"));
    }
}
