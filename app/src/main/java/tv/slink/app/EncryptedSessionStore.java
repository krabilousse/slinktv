package tv.slink.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class EncryptedSessionStore implements SessionStore {
    private static final String KEY = "slinktv.twitch.session.v1";
    private final SharedPreferences preferences;
    EncryptedSessionStore(Context context) { preferences = context.getSharedPreferences("twitch_session", Context.MODE_PRIVATE); }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY)) return (SecretKey) store.getKey(KEY, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    @Override public TwitchApi.Session load() throws Exception {
        String encoded = preferences.getString("encrypted_session", null);
        if (encoded == null) return null;
        ByteBuffer bytes = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
        byte[] iv = new byte[12];
        bytes.get(iv);
        byte[] ciphertext = new byte[bytes.remaining()];
        bytes.get(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        JSONObject json = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        return new TwitchApi.Session(json.getString("client_id"), json.getString("access_token"),
                json.getString("refresh_token"), json.getLong("expires_at"));
    }

    @Override public void save(TwitchApi.Session session) throws Exception {
        JSONObject json = new JSONObject().put("client_id", session.clientId()).put("access_token", session.accessToken())
                .put("refresh_token", session.refreshToken()).put("expires_at", session.expiresAt());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        byte[] bytes = ByteBuffer.allocate(12 + encrypted.length).put(cipher.getIV()).put(encrypted).array();
        if (!preferences.edit().putString("encrypted_session", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
            throw new IOException("Unable to save login");
    }

    @Override public void clear() throws Exception {
        if (!preferences.edit().remove("encrypted_session").commit()) throw new IOException("Unable to clear login");
    }
}
