package tv.slink.app;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w960dp-h540dp-land-xhdpi")
public class AdFreePlaybackTest {
    private static final long LIVE_MS = 5_000, LAST_LIVE_FRAMES_MS = 17_800, AD_MS = 25_000;
    private ServerSocket server;

    @Before public void serveCapturedMidroll() throws IOException {
        byte[] playlist;
        try (InputStream in = getClass().getResourceAsStream("/ads/twitch-midroll.m3u8")) { playlist = in.readAllBytes(); }
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread serving = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    String request = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                    boolean manifest = request != null && request.split(" ")[1].endsWith(".m3u8");
                    OutputStream out = client.getOutputStream();
                    out.write(((manifest ? "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: " + playlist.length
                            : "HTTP/1.1 404 Not Found\r\nContent-Length: 0") + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    if (manifest) out.write(playlist);
                } catch (IOException closed) { }
            }
        });
        serving.setDaemon(true);
        serving.start();
    }

    @After public void stopServer() throws IOException { server.close(); }

    @Test public void adFreeModeCoversAndMutesAStitchedAdThenRestoresLive() {
        new AdFreeProxy(RuntimeEnvironment.getApplication()).enabled(true);
        try (ActivityController<PlayerActivity> controller = Robolectric.buildActivity(PlayerActivity.class, liveIntent())) {
            Player player = openAndLoad(controller);
            seek(player, LIVE_MS);
            assertFalse("live picture shown", adNotice(controller.get()).isShown());
            seek(player, LAST_LIVE_FRAMES_MS);
            assertTrue("covered before the first ad frame", adNotice(controller.get()).isShown());
            seek(player, AD_MS);
            assertEquals("ad audio muted", 0f, player.getVolume(), 0f);
            assertTrue("ad picture covered", adNotice(controller.get()).isShown());
            seek(player, LIVE_MS);
            assertEquals("live audio restored", 1f, player.getVolume(), 0f);
            assertFalse("live picture uncovered", adNotice(controller.get()).isShown());
        }
    }

    @Test public void withAdFreeModeOffAdsPlayUntouched() {
        try (ActivityController<PlayerActivity> controller = Robolectric.buildActivity(PlayerActivity.class, liveIntent())) {
            Player player = openAndLoad(controller);
            seek(player, AD_MS);
            assertEquals(1f, player.getVolume(), 0f);
            TextView notice = findNotice(controller.get().findViewById(android.R.id.content));
            assertTrue(notice == null || !notice.isShown());
        }
    }

    private Intent liveIntent() {
        String url = "http://127.0.0.1:" + server.getLocalPort() + "/live.m3u8";
        return new Intent(RuntimeEnvironment.getApplication(), PlayerActivity.class)
                .putExtra("stream", "{\"channel\":\"jynxzi\",\"kind\":\"live\",\"headers\":{},\"variants\":[{\"name\":\"720p60\",\"url\":\"" + url + "\"}]}");
    }

    private Player openAndLoad(ActivityController<PlayerActivity> controller) {
        controller.setup();
        FrameLayout content = controller.get().findViewById(android.R.id.content);
        Player player = ((PlayerView) ((FrameLayout) content.getChildAt(0)).getChildAt(0)).getPlayer();
        await("captured playlist loaded", () -> player.getCurrentManifest() != null);
        return player;
    }

    private static void seek(Player player, long positionMs) {
        player.seekTo(positionMs);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
    }

    private static void await(String what, BooleanSupplier ready) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!ready.getAsBoolean()) {
            assertTrue("timed out waiting: " + what, System.nanoTime() < deadline);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
            try { Thread.sleep(20); } catch (InterruptedException error) { throw new AssertionError(error); }
        }
    }

    private static TextView adNotice(PlayerActivity activity) {
        TextView notice = findNotice(activity.findViewById(android.R.id.content));
        assertNotNull("ad notice exists", notice);
        return notice;
    }

    private static TextView findNotice(View view) {
        if (view instanceof TextView text && text.getText().toString().startsWith("Ad hidden")) return text;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = findNotice(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}
