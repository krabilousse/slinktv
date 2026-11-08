package tv.slink.app;

import android.content.Context;
import android.widget.Toast;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.PlayerView;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import org.json.JSONObject;

/**
 * Xtra's ad bypass. While the main session has an ad ahead of the playhead, a backup player shows the same
 * channel from a source that has none: first the same playlist fetched through an HTTP proxy, then a fresh
 * Android autoplay session. A backup that shows an ad or fails is dropped for the next source.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class AdBypass {
    static InetSocketAddress httpProxy = InetSocketAddress.createUnresolved("firefox.api.cdn-perfprod.com", 2023);
    static Pattern proxiedHosts = Pattern.compile("[a-z0-9-]+\\.playlist\\.(?:live-video|ttvnw)\\.net|video-weaver\\.[a-z0-9-]+\\.hls\\.ttvnw\\.net");
    private enum Source { HTTP_PROXY, VIDEO_SWAP }

    private final Context context;
    private final PlayerView view;
    private final ExoPlayer main;
    private final String channel, userAgent;
    private final Map<String, String> headers;
    private final DataSource.Factory http;
    private final Executor resolver;
    private ExoPlayer backup;
    private int next, generation;
    private boolean resolving, failed, showing;

    AdBypass(Context context, PlayerView view, ExoPlayer main, String channel, String userAgent, Map<String, String> headers, DataSource.Factory http, Executor resolver) {
        this.context = context; this.view = view; this.main = main; this.channel = channel;
        this.userAgent = userAgent; this.headers = headers; this.http = http; this.resolver = resolver;
    }

    /** Returns whether the backup is on screen. */
    boolean update() {
        if (!StitchedAds.ahead(main)) { stop(); return false; }
        if (backup != null && (failed || StitchedAds.ahead(backup))) drop();
        if (backup == null && !resolving && next < Source.values().length) start(Source.values()[next++]);
        if (backup != null) backup.setPlayWhenReady(main.getPlayWhenReady());
        show(backup != null && backup.getCurrentManifest() != null);
        return showing;
    }

    void stop() {
        generation++; resolving = false; next = 0;
        if (backup != null) drop();
    }

    private void start(Source source) {
        if (source == Source.HTTP_PROXY) { play(main.getCurrentMediaItem().localConfiguration.uri.toString(), proxied()); return; }
        resolving = true;
        int request = generation;
        resolver.execute(() -> {
            String data;
            try { data = StreamEngine.call(context, "resolver", "resolve_swap", channel); }
            catch (Exception | LinkageError engineUnavailable) { data = "{}"; }
            String result = data;
            view.post(() -> {
                if (request != generation) return;
                resolving = false;
                try { play(new JSONObject(result).getJSONArray("variants").getJSONObject(0).getString("url"), http); }
                catch (Exception noSwapStream) { }
            });
        });
    }

    private void play(String url, DataSource.Factory data) {
        backup = new ExoPlayer.Builder(context).setMediaSourceFactory(new DefaultMediaSourceFactory(data)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(1))).build();
        backup.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) { failed = true; }
        });
        backup.setMediaItem(new MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build());
        backup.prepare();
        backup.setPlayWhenReady(main.getPlayWhenReady());
    }

    private void show(boolean backupOnScreen) {
        if (backupOnScreen == showing) return;
        showing = backupOnScreen;
        view.setPlayer(showing ? backup : main);
        main.setTrackSelectionParameters(main.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, showing).build());
        if (showing) Toast.makeText(context, "Ad skipped. Showing a backup stream until the break ends.", Toast.LENGTH_SHORT).show();
    }

    private void drop() {
        show(false);
        backup.release();
        backup = null; failed = false;
    }

    private DataSource.Factory proxied() {
        OkHttpClient client = new OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).proxySelector(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                return List.of(uri.getHost() != null && proxiedHosts.matcher(uri.getHost()).matches() ? new Proxy(Proxy.Type.HTTP, httpProxy) : Proxy.NO_PROXY);
            }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        }).build();
        return new OkHttpDataSource.Factory(client).setUserAgent(userAgent).setDefaultRequestProperties(headers);
    }
}
