package tv.slink.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class UpdateSettings {
    private static final String KEY = "slinktv.updates.v1";
    final SharedPreferences preferences;
    UpdateSettings(Context context) { preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE); }
    UpdateSource source() { return UpdateSource.parse(preferences.getString("source", UpdateSource.DEFAULT)); }
    void source(String value) {
        UpdateSource next = UpdateSource.parse(value);
        if (!next.uri().equals(source().uri())) preferences.edit().putString("source",next.uri().toString()).remove("credential").remove("last_check").apply();
    }
    boolean hasToken() { return preferences.contains("credential"); }
    void forgetToken() { preferences.edit().remove("credential").apply(); }
    boolean previews() { return preferences.getBoolean("previews", false); }
    boolean automatic() { return preferences.getBoolean("automatic", true); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(KEY)) return (SecretKey)store.getKey(KEY,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    String token() throws Exception {
        String value=preferences.getString("credential",null); if(value==null)return "";
        ByteBuffer bytes=ByteBuffer.wrap(Base64.decode(value,Base64.NO_WRAP)); byte[] iv=new byte[12];bytes.get(iv);
        byte[] ciphertext=new byte[bytes.remaining()];bytes.get(ciphertext);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
        return new String(cipher.doFinal(ciphertext),StandardCharsets.UTF_8);
    }
    void token(String token) throws Exception {
        if (source().repository().isEmpty()) throw new IllegalArgumentException("A GitHub repository source is required.");
        if (!token.matches("[A-Za-z0-9_]{20,255}")) throw new IllegalArgumentException("Enter a GitHub access token.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] ciphertext=cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] bytes=ByteBuffer.allocate(12+ciphertext.length).put(cipher.getIV()).put(ciphertext).array();
        if(!preferences.edit().putString("credential",Base64.encodeToString(bytes,Base64.NO_WRAP)).commit())throw new java.io.IOException("Could not save credential.");
    }
}
