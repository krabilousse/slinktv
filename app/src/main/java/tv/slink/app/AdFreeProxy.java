package tv.slink.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;

final class AdFreeProxy {
    static final List<String> DEFAULTS = List.of(
            "https://eu.luminous.dev/live/$channel", "https://eu2.luminous.dev/live/$channel", "https://eu3.luminous.dev/live/$channel",
            "https://as.luminous.dev/live/$channel", "https://lb-eu.cdn-perfprod.com/live/$channel", "https://lb-eu2.cdn-perfprod.com/live/$channel",
            "https://lb-eu3.cdn-perfprod.com/live/$channel", "https://lb-eu4.cdn-perfprod.com/live/$channel", "https://lb-eu5.cdn-perfprod.com/live/$channel",
            "https://lb-na.cdn-perfprod.com/live/$channel", "https://lb-sa.cdn-perfprod.com/live/$channel", "https://lb-as.cdn-perfprod.com/live/$channel");
    private final SharedPreferences preferences;
    AdFreeProxy(Context context) { preferences = context.getSharedPreferences("ad_proxy", Context.MODE_PRIVATE); }
    boolean enabled() { return preferences.getBoolean("enabled", false); }
    void enabled(boolean value) { preferences.edit().putBoolean("enabled", value).apply(); }
    List<String> servers() { String saved = preferences.getString("servers", null); return saved == null ? DEFAULTS : List.of(saved.split("\n")); }
    void servers(String multiline) { preferences.edit().putString("servers", String.join("\n", parse(multiline))).apply(); }
    void restoreDefaults() { preferences.edit().remove("servers").apply(); }
    String requestJson() { return enabled() ? new JSONArray(servers()).toString() : "[]"; }

    static List<String> parse(String multiline) {
        Set<String> servers = new LinkedHashSet<>();
        for (String line : multiline.split("\\R")) {
            String value = line.trim(); if (value.isEmpty()) continue;
            if (!value.contains("://")) value = "https://" + value;
            URI uri;
            try { uri = URI.create(value); } catch (RuntimeException error) { throw new IllegalArgumentException("Not a valid address: " + line.trim()); }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException("Servers must use HTTPS: " + line.trim());
            if (uri.getUserInfo() != null) throw new IllegalArgumentException("Server addresses cannot contain credentials.");
            if (!value.contains("$channel")) throw new IllegalArgumentException("Add $channel where the channel name goes: " + line.trim());
            servers.add(value);
        }
        if (servers.isEmpty()) throw new IllegalArgumentException("Add at least one server, or choose Default.");
        return new ArrayList<>(servers);
    }
}
