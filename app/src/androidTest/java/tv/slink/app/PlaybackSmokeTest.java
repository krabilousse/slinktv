package tv.slink.app;

import android.content.Context;
import android.view.Surface;
import android.graphics.SurfaceTexture;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Run on a TV/emulator with network access using connectedDebugAndroidTest. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlaybackSmokeTest {
    @Test public void embeddedPythonImportsNativeDependencies() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (!Python.isStarted()) Python.start(new AndroidPlatform(context));
        Python python = Python.getInstance();
        python.getModule("lxml.etree");
        python.getModule("Crypto.Cipher.AES");
        python.getModule("streamlink");
        assertEquals("linkus7", python.getModule("resolver").callAttr("channel_name", "https://twitch.tv/Linkus7").toString());
    }

    @Test public void media3DecodesHlsVideoFrame() throws Exception {
        CountDownLatch frame = new CountDownLatch(1);
        AtomicReference<PlaybackException> failure = new AtomicReference<>();
        ExoPlayer[] player = new ExoPlayer[1];
        SurfaceTexture[] texture = new SurfaceTexture[1];
        Surface[] surface = new Surface[1];
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            texture[0] = new SurfaceTexture(false);
            surface[0] = new Surface(texture[0]);
            player[0] = new ExoPlayer.Builder(instrumentation.getTargetContext()).build();
            player[0].setVideoSurface(surface[0]);
            player[0].setVolume(0);
            player[0].addListener(new Player.Listener() {
                @Override public void onRenderedFirstFrame() { frame.countDown(); }
                @Override public void onPlayerError(PlaybackException error) { failure.set(error); frame.countDown(); }
            });
            player[0].setMediaItem(new MediaItem.Builder()
                    .setUri("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
                    .setMimeType(MimeTypes.APPLICATION_M3U8).build());
            player[0].prepare();
            player[0].play();
        });
        try {
            assertTrue("No decoded HLS frame within 45 seconds", frame.await(45, TimeUnit.SECONDS));
            assertNull("Playback failed", failure.get());
        } finally {
            instrumentation.runOnMainSync(() -> {
                player[0].release();
                surface[0].release();
                texture[0].release();
            });
        }
    }
}
