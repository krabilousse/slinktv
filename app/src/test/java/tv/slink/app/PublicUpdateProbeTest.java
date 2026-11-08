package tv.slink.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Explicit opt-in network probe against the default public repository, without credentials. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class PublicUpdateProbeTest {
    @Test public void publishedReleaseDownloadsAnonymouslyThroughTheActualUpdater() throws Exception {
        org.junit.Assume.assumeTrue("Requires explicit live release probe", "1".equals(System.getenv("SLINK_LIVE_UPDATE_PROBE")));
        UpdateClient client=new UpdateClient(UpdateSource.parse(UpdateSource.DEFAULT),"");
        var release=client.check(false,0,34);assertNotNull(release);
        Path output=Path.of("../.tools/public-update-probe.apk");Files.createDirectories(output.getParent());
        client.downloadFile(output.toFile(),release,p->{});
        JSONObject evidence=new JSONObject().put("checked_at",java.time.Instant.now().toString()).put("version",release.manifest().version())
                .put("version_code",release.manifest().code()).put("size",Files.size(output)).put("sha256",release.manifest().sha256())
                .put("actual_java_updater",true).put("anonymous_public_release",true).put("checksum_verified",true).put("android_installer_tested",false);
        Files.write(Path.of("../docs/evidence/public-update-"+release.manifest().version()+".json"),(evidence.toString(2)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
