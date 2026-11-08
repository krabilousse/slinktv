package tv.slink.app;

import android.graphics.Bitmap;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34, 35})
public class LoginActivityTest {
    @Test public void loginScreenCreatesWithoutNativePythonOrExistingToken() {
        try (var controller = Robolectric.buildActivity(LoginActivity.class)) {
            controller.create().start().resume();
            assertFalse(controller.get().isFinishing());
            assertNotNull(controller.get().findViewById(android.R.id.content));
        }
    }

    @Test public void qrRoundTripsTheExactVerificationUri() throws Exception {
        String uri = "https://www.twitch.tv/activate?public=true&device-code=TEST1234";
        Bitmap bitmap = LoginActivity.qrBitmap(uri);
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        var decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels))));
        assertEquals(uri, decoded.getText());
    }

    @Test public void listIncludesOfflineFollowsAndLiveMetadata() throws Exception {
        JSONArray follows = new JSONArray("[{\"data\":[{\"broadcaster_id\":\"1\",\"broadcaster_name\":\"OfflineFriend\"},{\"broadcaster_id\":\"2\",\"broadcaster_name\":\"LiveFriend\"}]}]");
        JSONArray live = new JSONArray("[{\"data\":[{\"user_id\":\"2\",\"user_name\":\"LiveFriend\",\"game_name\":\"Zelda\",\"viewer_count\":123,\"title\":\"A live title\"}]}]");
        var data = new TwitchApi.Snapshot(new JSONObject(), new JSONObject(), follows, live);
        var all = LoginActivity.channelLines(data, false);
        assertEquals(2, all.size());
        assertTrue(all.get(0).startsWith("Offline  OfflineFriend"));
        assertTrue(all.get(1).startsWith("LIVE  LiveFriend"));
        var liveOnly = LoginActivity.channelLines(data, true);
        assertEquals(1, liveOnly.size());
        assertTrue(liveOnly.get(0).contains("Zelda"));
        assertTrue(liveOnly.get(0).contains("123 viewers"));
    }
}
