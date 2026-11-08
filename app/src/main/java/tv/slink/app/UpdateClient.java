package tv.slink.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

final class UpdateClient {
    static final long MAX_APK = 300L * 1024 * 1024;
    record Manifest(long code, String version, long size, String sha256, String apkName, String notes, int minSdk) {
        static Manifest parse(JSONObject json) throws Exception {
            if (json.getInt("schemaVersion") != 1 || !BuildConfig.APPLICATION_ID.equals(json.getString("packageName")))
                throw new IOException("This update feed is for a different app.");
            Manifest value = new Manifest(json.getLong("versionCode"),json.getString("versionName"),json.getLong("size"),
                    json.getString("sha256").toLowerCase(java.util.Locale.ROOT),json.getString("apkName"),json.optString("notes"),json.getInt("minSdk"));
            if(value.code<=0 || value.size<=0 || value.size>MAX_APK || !value.sha256.matches("[a-f0-9]{64}")
                    || !value.apkName.matches("[A-Za-z0-9_.-]+\\.apk") || value.version.length()>100 || value.notes.length()>50_000)
                throw new IOException("Invalid update metadata.");
            return value;
        }
    }
    record Release(Manifest manifest, URI apk) {}
    record Response(int code, String location, long length, InputStream body, Runnable disconnect) implements AutoCloseable {
        @Override public void close() throws IOException { try { body.close(); } finally { disconnect.run(); } }
    }
    interface Transport { Response open(URI uri, Map<String,String> headers) throws Exception; }
    private final UpdateSource source;
    private final String token;
    private final Transport transport;
    UpdateClient(UpdateSource source,String token) { this(source,token,UpdateClient::http); }
    UpdateClient(UpdateSource source,String token,Transport transport) { this.source=source;this.token=token;this.transport=transport; }
    private static Response http(URI uri,Map<String,String> headers) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();
        connection.setConnectTimeout(15_000);connection.setReadTimeout(20_000);connection.setInstanceFollowRedirects(false);
        for(var header:headers.entrySet())connection.setRequestProperty(header.getKey(),header.getValue());
        try {
            int code=connection.getResponseCode();
            InputStream body=code>=400?connection.getErrorStream():connection.getInputStream();
            return new Response(code,connection.getHeaderField("Location"),connection.getContentLengthLong(),
                    body==null?InputStream.nullInputStream():body,connection::disconnect);
        } catch(Exception error) { connection.disconnect();throw error; }
    }
    private void read(URI original,String accept,long max,OutputStream output,IntConsumer progress) throws Exception {
        URI uri=original; long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(15);
        for(int redirects=0;redirects<=5;redirects++) {
            UpdateSource.https(uri);
            Map<String,String> headers=new HashMap<>();headers.put("Accept",accept);headers.put("User-Agent","SlinkTV/"+BuildConfig.VERSION_NAME);
            headers.put("Accept-Encoding","identity");
            if (redirects==0 && source.authorize(uri)) {
                headers.put("X-GitHub-Api-Version","2026-03-10");
                if(!token.isEmpty())headers.put("Authorization","Bearer "+token);
            }
            try(Response response=transport.open(uri,headers)) {
                if(response.code==301||response.code==302||response.code==303||response.code==307||response.code==308) {
                    if(response.location==null)throw new IOException("Update redirect is incomplete.");
                    uri=uri.resolve(response.location);continue;
                }
                if(response.code==401||response.code==403||response.code==404)
                    throw new IOException("Update source unavailable (HTTP "+response.code+"). For a private repository, check its read credential.");
                if(response.code!=200)throw new IOException("Update server returned HTTP "+response.code+".");
                if(response.length>max)throw new IOException("Update response exceeds its size limit.");
                byte[] buffer=new byte[64*1024];long total=0;int last=-1;
                for(int count;(count=response.body.read(buffer))!=-1;) {
                    if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IOException("Download cancelled or timed out.");
                    total+=count;if(total>max)throw new IOException("Update response exceeds its size limit.");output.write(buffer,0,count);
                    int percent=(int)(total*100/max);if(percent!=last){last=percent;progress.accept(percent);}
                }
                return;
            }
        }
        throw new IOException("Too many update redirects.");
    }
    private String json(URI uri,String accept) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();read(uri,accept,1024*1024,output,p->{});
        return output.toString(StandardCharsets.UTF_8);
    }
    Release check(boolean previews,long installedCode,int sdk) throws Exception {
        if(source.repository().isEmpty()) {
            JSONObject data=new JSONObject(json(source.uri(),"application/json"));Manifest manifest=Manifest.parse(data);
            URI apk=source.uri().resolve(data.getString("apkUrl"));UpdateSource.https(apk);
            return eligible(manifest,installedCode,sdk)?new Release(manifest,apk):null;
        }
        // Stable uses GitHub's latest published stable release. Preview opts into the newest published release.
        String endpoint=source.api()+(previews?"/releases?per_page=100":"/releases/latest");
        String raw=json(URI.create(endpoint),"application/vnd.github+json");
        JSONObject release=null;
        if(previews){JSONArray releases=new JSONArray(raw);for(int i=0;i<releases.length();i++)if(!releases.getJSONObject(i).optBoolean("draft")){release=releases.getJSONObject(i);break;}}
        else release=new JSONObject(raw);
        if(release==null)throw new IOException("No published release is available.");
        JSONArray assets=release.getJSONArray("assets");JSONObject manifestAsset=asset(assets,"update.json");
        Manifest manifest=Manifest.parse(new JSONObject(json(assetUri(manifestAsset),"application/octet-stream")));
        if(!eligible(manifest,installedCode,sdk))return null;
        JSONObject apkAsset=asset(assets,manifest.apkName);
        if(apkAsset.getLong("size")!=manifest.size)throw new IOException("Release asset size does not match the update feed.");
        return new Release(manifest,assetUri(apkAsset));
    }
    private boolean eligible(Manifest manifest,long installedCode,int sdk) throws IOException {
        if(manifest.minSdk>sdk)throw new IOException("This update requires a newer Android version.");
        return manifest.code>installedCode;
    }
    private JSONObject asset(JSONArray assets,String name) throws Exception {
        for(int i=0;i<assets.length();i++)if(name.equals(assets.getJSONObject(i).getString("name")))return assets.getJSONObject(i);
        throw new IOException("The release is missing "+name+".");
    }
    private URI assetUri(JSONObject asset) throws Exception {
        long id=asset.getLong("id");if(id<=0)throw new IOException("Invalid release asset.");
        return URI.create(source.api()+"/releases/assets/"+id);
    }
    File download(Context context,Release release,IntConsumer progress) throws Exception {
        File directory=new File(context.getFilesDir(),"updates");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create update directory.");
        if(directory.getUsableSpace()<release.manifest.size+10*1024*1024)throw new IOException("Not enough free space for the update.");
        File part=new File(directory,"download.part"),ready=new File(directory,"update.apk");
        try {
            downloadFile(part,release,progress);verifyPackage(context,part,release.manifest);
            Files.move(part.toPath(),ready.toPath(),StandardCopyOption.REPLACE_EXISTING);
            return ready;
        } finally { Files.deleteIfExists(part.toPath()); }
    }
    void downloadFile(File target,Release release,IntConsumer progress) throws Exception {
        try(OutputStream output=Files.newOutputStream(target.toPath())){read(release.apk,"application/octet-stream",release.manifest.size,output,progress);}
        verifyFile(target,release.manifest);
    }
    static void verifyFile(File file,Manifest manifest) throws Exception {
        if(file.length()!=manifest.size)throw new IOException("The APK download is incomplete.");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=Files.newInputStream(file.toPath())){byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))!=-1;)digest.update(buffer,0,n);}
        if(!HexFormat.of().formatHex(digest.digest()).equals(manifest.sha256))throw new IOException("The APK checksum does not match.");
    }
    static void verifyPackage(Context context,File file,Manifest expected) throws Exception {
        PackageManager pm=context.getPackageManager();
        PackageInfo installed=pm.getPackageInfo(context.getPackageName(),PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
        PackageInfo archive=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
        if(archive==null)throw new IOException("The downloaded file is not a valid APK.");
        verifyIdentity(archive.packageName,archive.getLongVersionCode(),signatures(archive),installed.getLongVersionCode(),signatures(installed),expected.code);
    }
    static void verifyIdentity(String name,long code,Set<String> signing,long installed,Set<String> trusted,long expected) throws IOException {
        if(!BuildConfig.APPLICATION_ID.equals(name)||code!=expected||code<=installed||signing.isEmpty()||!signing.equals(trusted))
            throw new IOException("APK package, version, or signing identity does not match this installation.");
    }
    private static Set<String> signatures(PackageInfo info) {
        if(info.signingInfo==null)return Set.of();
        return java.util.Arrays.stream(info.signingInfo.getApkContentsSigners()).map(android.content.pm.Signature::toCharsString).collect(Collectors.toSet());
    }
}
