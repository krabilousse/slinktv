package tv.slink.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Explicit opt-in network probe. No credential or signed download URL is recorded. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class PrivateUpdateProbeTest {
    @Test public void publishedPrivateReleaseDownloadsThroughTheActualUpdater() throws Exception {
        org.junit.Assume.assumeTrue("Requires explicit private release probe", "1".equals(System.getenv("SLINK_LIVE_UPDATE_PROBE")));
        String token=System.getenv("GH_TOKEN");assertNotNull(token);
        UpdateClient client=new UpdateClient(UpdateSource.parse(UpdateSource.DEFAULT),token);
        var release=client.check(false,BuildConfig.VERSION_CODE-1,34);assertNotNull(release);
        assertEquals(BuildConfig.VERSION_CODE,release.manifest().code());
        Path output=Path.of("../.tools/private-update-probe.apk");Files.createDirectories(output.getParent());
        client.downloadFile(output.toFile(),release,p->{});
        JSONObject evidence=new JSONObject().put("checked_at",java.time.Instant.now().toString()).put("version",release.manifest().version())
                .put("version_code",release.manifest().code()).put("size",Files.size(output)).put("sha256",release.manifest().sha256())
                .put("actual_java_updater",true).put("authenticated_private_release",true).put("checksum_verified",true).put("android_installer_tested",false);
        Files.write(Path.of("../docs/evidence/private-update-0.6.0.json"),(evidence.toString(2)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
