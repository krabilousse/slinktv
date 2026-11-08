package tv.slink.app;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.Cache;
import okhttp3.CacheControl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Shared encoded cache only. Every visible row gets its own drawable and animation callback. */
final class ChatEmotes {
    record Catalog(Map<String, ChatEmote> emotes, boolean stale) {}
    private static ChatEmotes instance;
    private final OkHttpClient http;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService metadata = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor images = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
    private final android.util.LruCache<String, byte[]> encoded = new android.util.LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, byte[] value) { return value.length; }
    };
    private Map<String, ChatEmote> global = Map.of();
    private final android.util.LruCache<String, Map<String, ChatEmote>> channels = new android.util.LruCache<>(8);

    static synchronized ChatEmotes get(Context context) {
        if (instance == null) instance = new ChatEmotes(context.getApplicationContext());
        return instance;
    }
    private ChatEmotes(Context context) {
        http = new OkHttpClient.Builder().cache(new Cache(new File(context.getCacheDir(), "chat-images"), 64L * 1024 * 1024))
                .connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build();
        images.allowCoreThreadTimeOut(true);
    }

    Future<?> catalog(String channel, Consumer<Catalog> complete) {
        return metadata.submit(() -> {
            boolean stale = false;
            try { global = Map.copyOf(ChatEmote.parseSet(json("https://7tv.io/v3/emote-sets/global"))); }
            catch (Exception ignored) { stale = true; }
            if (Thread.currentThread().isInterrupted()) return;
            Map<String, ChatEmote> local = channels.get(channel);
            try {
                JSONObject user = json("https://7tv.io/v3/users/twitch/" + channel);
                JSONObject set = user.optJSONObject("emote_set");
                String id = user.optString("emote_set_id");
                if (set == null && id.matches("[A-Za-z0-9_-]+")) set = json("https://7tv.io/v3/emote-sets/" + id);
                local = set == null ? Map.of() : Map.copyOf(ChatEmote.parseSet(set));
                channels.put(channel, local);
            } catch (NotFound ignored) { local = Map.of(); channels.put(channel, local); }
            catch (Exception ignored) { stale = true; }
            if (Thread.currentThread().isInterrupted()) return;
            Map<String, ChatEmote> merged = new LinkedHashMap<>(global);
            if (local != null) merged.putAll(local);
            Catalog result = new Catalog(Map.copyOf(merged), stale);
            main.post(() -> complete.accept(result));
        });
    }

    Future<?> load(List<ChatEmote> emotes, int height, Consumer<List<Drawable>> complete) {
        images.purge();
        try {
            return images.submit(() -> {
                List<Drawable> loaded = new ArrayList<>();
                for (ChatEmote emote : emotes) {
                    if (Thread.currentThread().isInterrupted()) return;
                    Drawable image = null;
                    try { image = decode(bytes(emote.url()), height); }
                    catch (Exception ignored) {
                        if (!emote.staticUrl().equals(emote.url())) try { image = decode(bytes(emote.staticUrl()), height); } catch (Exception unavailable) { }
                    }
                    loaded.add(image);
                }
                if (!Thread.currentThread().isInterrupted()) main.post(() -> complete.accept(loaded));
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { return null; }
    }

    private JSONObject json(String url) throws Exception {
        Request request = new Request.Builder().url(url).cacheControl(new CacheControl.Builder().noCache().build()).build();
        return new JSONObject(new String(fetch(request, 8 * 1024 * 1024), StandardCharsets.UTF_8));
    }
    private byte[] bytes(String url) throws Exception {
        byte[] data = encoded.get(url);
        if (data != null) return data;
        data = fetch(new Request.Builder().url(url).build(), 2 * 1024 * 1024);
        encoded.put(url, data); return data;
    }
    private byte[] fetch(Request request, int maximum) throws Exception {
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 404) throw new NotFound();
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Image service unavailable");
            if (response.body().contentLength() > maximum) throw new IOException("Image response too large");
            byte[] data = response.body().byteStream().readNBytes(maximum + 1);
            if (data.length > maximum) throw new IOException("Image response too large");
            return data;
        }
    }
    static Drawable decode(byte[] encoded, int targetHeight) throws IOException {
        return ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(encoded)), (decoder, info, source) -> {
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            if (width <= 0 || height <= 0 || (long) width * height > 4_000_000) throw new IllegalArgumentException("Image dimensions too large");
            float scale = Math.min((float) targetHeight / height, 512f / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
        });
    }
    private static final class NotFound extends IOException {}
}
