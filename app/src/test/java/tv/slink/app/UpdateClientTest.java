package tv.slink.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class UpdateClientTest {
    private JSONObject manifest() throws Exception {
        return new JSONObject().put("schemaVersion",1).put("packageName","tv.slink.app").put("versionCode",100)
                .put("versionName","1.0.0").put("size",3).put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[]{1,2,3})))
                .put("apkName","SlinkTV-1.0.0.apk").put("apkUrl","https://downloads.example/app.apk").put("minSdk",34).put("notes","Changes");
    }
    private static class Network implements UpdateClient.Transport {
        final List<UpdateClient.Response> replies=new ArrayList<>();final List<URI> urls=new ArrayList<>();final List<Map<String,String>> headers=new ArrayList<>();
        Network reply(int code,String location,String text){byte[] bytes=text.getBytes(StandardCharsets.UTF_8);replies.add(new UpdateClient.Response(code,location,bytes.length,new ByteArrayInputStream(bytes),()->{}));return this;}
        public UpdateClient.Response open(URI url,Map<String,String> headers){urls.add(url);this.headers.add(headers);return replies.remove(0);}
    }
    private String assets() throws Exception {
        return new JSONObject().put("assets",new JSONArray().put(new JSONObject().put("id",12).put("name","update.json"))
                .put(new JSONObject().put("id",13).put("name","SlinkTV-1.0.0.apk").put("size",3))).toString();
    }
    @Test public void privateAssetRedirectStripsCredentialAndUsesExactApkAsset() throws Exception {
        Network network=new Network().reply(200,null,assets()).reply(302,"https://cdn.example/signed","").reply(200,null,manifest().toString());
        UpdateClient.Release release=new UpdateClient(UpdateSource.parse(UpdateSource.DEFAULT),"private-read",network).check(false,8,34);
        assertEquals("Bearer private-read",network.headers.get(0).get("Authorization"));
        assertEquals("application/octet-stream",network.headers.get(1).get("Accept"));
        assertFalse(network.headers.get(2).containsKey("Authorization"));assertEquals(100,release.manifest().code());
        assertEquals("https://api.github.com/repos/krabilousse/slinktv/releases/assets/13",release.apk().toString());
    }
    @Test public void customSourceNeverReceivesGithubCredentialAndRejectsDowngrade() throws Exception {
        Network network=new Network().reply(200,null,manifest().toString());
        var release=new UpdateClient(UpdateSource.parse("https://updates.example/update.json"),"private-read",network).check(false,100,34);
        assertNull(release);assertFalse(network.headers.get(0).containsKey("Authorization"));
    }
    @Test public void privateNotFoundIsAnErrorRatherThanUpToDate() {
        Network network=new Network().reply(404,null,"{}");
        assertThrows(IOException.class,()->new UpdateClient(UpdateSource.parse(UpdateSource.DEFAULT),"",network).check(false,8,34));
    }
    @Test public void malformedFeedWrongAppUnsupportedSdkAndHttpRedirectAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,()->UpdateSource.parse("http://example.com/update.json"));
        assertThrows(IllegalArgumentException.class,()->UpdateSource.parse("https://user:secret@example.com/update.json"));
        assertThrows(IOException.class,()->UpdateClient.Manifest.parse(manifest().put("sha256","missing")));
        assertThrows(IOException.class,()->UpdateClient.Manifest.parse(manifest().put("packageName","other.app")));
        Network network=new Network().reply(302,"http://example.com/unsafe","");
        assertThrows(IllegalArgumentException.class,()->new UpdateClient(UpdateSource.parse("https://updates.example/update.json"),"",network).check(false,8,34));
        Network unsupported=new Network().reply(200,null,manifest().put("minSdk",36).toString());
        assertThrows(IOException.class,()->new UpdateClient(UpdateSource.parse("https://updates.example/update.json"),"",unsupported).check(false,8,34));
    }
    @Test public void corruptedAndTruncatedDownloadsAreRejected() throws Exception {
        var file=Files.createTempFile("slink-update",".apk");
        try{Files.write(file,new byte[]{1,2,3});var data=UpdateClient.Manifest.parse(manifest());UpdateClient.verifyFile(file.toFile(),data);
            Files.write(file,new byte[]{3,2,1});assertThrows(IOException.class,()->UpdateClient.verifyFile(file.toFile(),data));
            Files.write(file,new byte[]{1,2});assertThrows(IOException.class,()->UpdateClient.verifyFile(file.toFile(),data));
        }finally{Files.delete(file);}
    }
    @Test public void installedSignerPackageAndVersionAreIndependentTrustChecks() throws Exception {
        UpdateClient.verifyIdentity("tv.slink.app",9,Set.of("trusted"),8,Set.of("trusted"),9);
        assertThrows(IOException.class,()->UpdateClient.verifyIdentity("tv.slink.app",9,Set.of("other"),8,Set.of("trusted"),9));
        assertThrows(IOException.class,()->UpdateClient.verifyIdentity("tv.slink.app",9,Set.of(),8,Set.of(),9));
        assertThrows(IOException.class,()->UpdateClient.verifyIdentity("other.app",9,Set.of("trusted"),8,Set.of("trusted"),9));
        assertThrows(IOException.class,()->UpdateClient.verifyIdentity("tv.slink.app",8,Set.of("trusted"),8,Set.of("trusted"),8));
        assertThrows(IOException.class,()->UpdateClient.verifyIdentity("tv.slink.app",10,Set.of("trusted"),8,Set.of("trusted"),9));
    }
    @Test public void credentialCannotReachOtherRepositoriesOrLookalikeHosts() {
        UpdateSource source=UpdateSource.parse(UpdateSource.DEFAULT);
        assertFalse(source.authorize(URI.create("https://api.github.com/repos/other/repo/releases")));
        assertFalse(source.authorize(URI.create("https://api.github.com.evil.invalid/repos/krabilousse/slinktv/releases")));
        assertFalse(source.authorize(URI.create("https://api.github.com:8443/repos/krabilousse/slinktv/releases")));
        assertTrue(source.authorize(URI.create("https://api.github.com/repos/krabilousse/slinktv/releases/latest")));
    }
    @Test public void fileProviderExposesOnlyTheUpdateDirectory() throws Exception {
        org.junit.Assume.assumeTrue("AndroidX FileProvider requires POSIX separators; covered by Linux release CI",java.io.File.separatorChar=='/');
        var app=org.robolectric.RuntimeEnvironment.getApplication();
        var dir=new java.io.File(app.getFilesDir(),"updates");dir.mkdirs();var apk=new java.io.File(dir,"update.apk");
        assertTrue(androidx.core.content.FileProvider.getUriForFile(app,"tv.slink.app.updates",apk).toString().startsWith("content://tv.slink.app.updates/updates/"));
        assertThrows(IllegalArgumentException.class,()->androidx.core.content.FileProvider.getUriForFile(app,"tv.slink.app.updates",new java.io.File(app.getFilesDir(),"private.txt")));
    }
    @Test public void updateProviderHasPrivateConfiguredPaths() {
        var app=org.robolectric.RuntimeEnvironment.getApplication();
        var provider=app.getPackageManager().resolveContentProvider("tv.slink.app.updates",android.content.pm.PackageManager.GET_META_DATA);
        assertNotNull(provider);assertFalse(provider.exported);assertTrue(provider.grantUriPermissions);
        assertEquals(R.xml.update_paths,provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS"));
    }
}
