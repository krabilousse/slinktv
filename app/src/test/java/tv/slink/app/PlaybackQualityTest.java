package tv.slink.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PlaybackQualityTest {
    private JSONArray variants(String... names) throws Exception {
        JSONArray result = new JSONArray();
        for (String name : names) result.put(new JSONObject().put("name", name).put("url", "https://example.invalid/" + name));
        return result;
    }

    @Test public void portraitCannotBecomeBestOrAppearInOptions() throws Exception {
        JSONArray result = PlaybackQuality.landscapeVariants(variants("2160p60_portrait", "1080p60", "720p30_PORTRAIT", "720p60", "audio_only"));
        assertEquals(3, result.length());
        assertEquals("1080p60", result.getJSONObject(PlaybackQuality.choose(result, 0)).getString("name"));
        assertEquals("1080P · 60 FPS (best)", PlaybackQuality.label(result, 0));
        assertEquals("720P · 60 FPS", PlaybackQuality.label(result, 1));
        assertEquals("Audio only", PlaybackQuality.label(result, 2));
    }

    @Test public void unavailableResolutionFallsBackBelowCapAndPrefersHigherFrameRate() throws Exception {
        JSONArray result = variants("audio_only", "1080p60", "480p30", "480p60", "360p30");
        assertEquals(3, PlaybackQuality.choose(result, 720));
        assertEquals(1, PlaybackQuality.choose(result, 0));
        assertEquals(4, PlaybackQuality.choose(result, 160));
    }

    @Test public void duplicateResolutionOptionsRemainDistinguishable() throws Exception {
        JSONArray result = variants("1080p", "1080p60");
        assertEquals("1080P", PlaybackQuality.label(result, 0));
        assertEquals("1080P · 60 FPS (best)", PlaybackQuality.label(result, 1));
    }

    @Test(expected = org.json.JSONException.class) public void portraitOnlyResponseIsRejected() throws Exception {
        PlaybackQuality.landscapeVariants(variants("1080p60_portrait"));
    }
}
