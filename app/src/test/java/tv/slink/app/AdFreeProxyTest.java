package tv.slink.app;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AdFreeProxyTest {
    private final AdFreeProxy proxy = new AdFreeProxy(RuntimeEnvironment.getApplication());

    @Test public void defaultsAreOffWithXtrasTwelveServersAndRequestNothing() {
        assertFalse(proxy.enabled());
        assertEquals(12, proxy.servers().size());
        assertEquals("https://eu.luminous.dev/live/$channel", proxy.servers().get(0));
        assertEquals("https://lb-as.cdn-perfprod.com/live/$channel", proxy.servers().get(11));
        assertEquals("[]", proxy.requestJson());
    }

    @Test public void enabledRequestListsSavedServersInOrderAndSurvivesReload() throws Exception {
        proxy.enabled(true);
        proxy.servers("as.luminous.dev/live/$channel\nhttps://eu.luminous.dev/live/$channel");
        JSONArray request = new JSONArray(new AdFreeProxy(RuntimeEnvironment.getApplication()).requestJson());
        assertEquals(2, request.length());
        assertEquals("https://as.luminous.dev/live/$channel", request.getString(0));
        assertEquals("https://eu.luminous.dev/live/$channel", request.getString(1));
        proxy.restoreDefaults();
        assertEquals(12, new JSONArray(proxy.requestJson()).length());
    }

    @Test public void parseNormalisesBareHostsAndDropsBlankAndDuplicateLines() {
        assertEquals(List.of("https://a.example/live/$channel", "https://b.example/hls/$channel.m3u8"),
                AdFreeProxy.parse(" a.example/live/$channel \r\n\nhttps://b.example/hls/$channel.m3u8\nhttps://a.example/live/$channel\n"));
    }

    @Test public void parseRejectsUnsafeOrUnusableServersWithAMessage() {
        for (String bad : new String[]{"http://a.example/live/$channel", "https://user:pw@a.example/live/$channel", "https://a.example/live/", "ftp://a.example/$channel", "", "  \n "}) {
            IllegalArgumentException error = assertThrows(bad, IllegalArgumentException.class, () -> AdFreeProxy.parse(bad));
            assertFalse(error.getMessage().isEmpty());
            assertFalse(error.getMessage().contains("pw"));
        }
    }

    @Test public void rejectedEditKeepsPreviousServers() {
        proxy.servers("a.example/live/$channel");
        assertThrows(IllegalArgumentException.class, () -> proxy.servers("http://b.example/live/$channel"));
        assertEquals(List.of("https://a.example/live/$channel"), proxy.servers());
    }
}
