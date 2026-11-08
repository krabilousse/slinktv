package tv.slink.app;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.ColorDrawable;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class ChatRenderingTest {
    private ChatEmote emote(String name, boolean zero) { return new ChatEmote(name, "https://cdn.7tv.app/emote/x/2x.webp", "", 64, 64, true, zero); }
    @Test public void aliasesAndBothZeroWidthFlagsAreRecognizedWithCombinedBits() throws Exception {
        JSONObject file = new JSONObject().put("name", "2x.webp").put("static_name", "2x_static.webp")
                .put("width", 170).put("height", 64).put("frame_count", 15).put("format", "WEBP");
        JSONObject data = new JSONObject().put("name", "original").put("flags", 258)
                .put("host", new JSONObject().put("url", "//cdn.7tv.app/emote/id").put("files", new JSONArray().put(file)));
        JSONArray entries = new JSONArray().put(new JSONObject().put("name", "Alias").put("data", data));
        Map<String, ChatEmote> result = ChatEmote.parseSet(new JSONObject().put("emotes", entries));
        assertFalse(result.containsKey("original"));
        ChatEmote alias = result.get("Alias"); assertTrue(alias.zeroWidth()); assertTrue(alias.animated());
        assertEquals(170, alias.width()); assertEquals(64, alias.height());
        assertEquals("https://cdn.7tv.app/emote/id/2x.webp", alias.url());
        data.put("flags", 0); entries.getJSONObject(0).put("flags", 3);
        assertTrue(ChatEmote.parseSet(new JSONObject().put("emotes", entries)).get("Alias").zeroWidth());
    }
    @Test public void twitchIdentityWinsAndZeroWidthLayersShareOneRun() throws Exception {
        ChatMessage message = ChatMessage.parse(ChatLogTest.message("id", "u", "😀 Base Overlay Overlay"), 1);
        List<ChatText.Run> runs = ChatText.runs(message, Map.of("Base", emote("Base", false), "Overlay", emote("Overlay", true)));
        ChatText.Images composite = (ChatText.Images) runs.get(runs.size() - 1);
        assertEquals(3, composite.layers().size());
        ChatMessage twitch = new ChatMessage("id", "", "u", "Viewer", "", "", "",
                List.of(new ChatMessage.Fragment("Base", "25", false)), 1);
        assertTrue(((ChatText.Images) ChatText.runs(twitch, Map.of("Base", emote("Base", false))).get(0)).layers().get(0).url().contains("jtvnw.net"));
    }
    @Test public void animationBudgetStopsOffscreenHiddenAndRecycledDrawables() {
        ChatAdapter adapter = new ChatAdapter(null);
        var row = adapter.onCreateViewHolder(new FrameLayout(RuntimeEnvironment.getApplication()), 0);
        ChatEmoteSpan span = new ChatEmoteSpan(List.of(emote("animated", false)), 32, 200);
        for (int i = 0; i < 16; i++) span.images.add(new Animated()); row.spans.add(span);
        adapter.setPresentation(true, true, 15); adapter.onViewAttachedToWindow(row);
        assertEquals(12, span.images.stream().filter(d -> ((Animated) d).running).count());
        adapter.onViewDetachedFromWindow(row);
        assertEquals(0, span.images.stream().filter(d -> ((Animated) d).running).count());
        adapter.onViewAttachedToWindow(row); adapter.setPresentation(false, true, 15);
        assertEquals(0, span.images.stream().filter(d -> ((Animated) d).running).count());
        adapter.onViewRecycled(row); assertTrue(row.spans.isEmpty()); adapter.close();
    }
    @Test public void wideEmotesKeepTheirRatioAndOverlaysDoNotAddAdvance() {
        ChatEmoteSpan span = new ChatEmoteSpan(List.of(emote("base", false), emote("layer", true)), 32, 200);
        span.images.add(new ColorDrawable(Color.WHITE) { @Override public int getIntrinsicWidth() { return 170; } @Override public int getIntrinsicHeight() { return 64; } });
        Paint paint = new Paint(); paint.setTextSize(20);
        int width = span.getSize(paint, "x", 0, 1, paint.getFontMetricsInt());
        span.images.add(new ColorDrawable(Color.RED));
        assertEquals(87, width); assertEquals(width, span.getSize(paint, "x", 0, 1, null));
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeDecoderRecognizesAnimatedWebpAndKeepsWideImageProportions() throws Exception {
        try (var animated = getClass().getResourceAsStream("/chat/Clap.webp"); var wide = getClass().getResourceAsStream("/chat/7Cinema.webp")) {
            assertNotNull(animated); assertNotNull(wide);
            assertTrue(ChatEmotes.decode(animated.readAllBytes(), 44) instanceof android.graphics.drawable.AnimatedImageDrawable);
            var image = ChatEmotes.decode(wide.readAllBytes(), 44);
            assertEquals(44, image.getIntrinsicHeight());
            assertEquals(170f / 64, (float) image.getIntrinsicWidth() / image.getIntrinsicHeight(), .04);
        }
    }
    private static final class Animated extends ColorDrawable implements Animatable {
        boolean running;
        @Override public void start() { running = true; }
        @Override public void stop() { running = false; }
        @Override public boolean isRunning() { return running; }
    }
}
