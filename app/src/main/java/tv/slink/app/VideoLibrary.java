package tv.slink.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One account's bounded discovery cache; a missing lookup never means offline. */
final class VideoLibrary {
    record Channel(String id, String login, String name) {}
    final Map<String, Channel> channels = new LinkedHashMap<>();
    private final Map<String, List<VideoItem>> videos = new HashMap<>();
    private final Map<String, Long> checked = new HashMap<>(), failures = new HashMap<>();
    private Set<String> live = Set.of();
    private final Map<String, Long> offlineSince = new HashMap<>();
    private long liveChecked;
    private boolean hasLiveSample;
    private final LinkedHashMap<String, Boolean> cached = new LinkedHashMap<>();

    void follows(JSONArray follows) throws Exception {
        channels.clear();
        for (int i = 0; i < follows.length(); i++) {
            JSONObject row = follows.getJSONObject(i);
            Channel c = new Channel(row.getString("broadcaster_id"), row.getString("broadcaster_login"), row.getString("broadcaster_name"));
            channels.put(c.id(), c);
        }
        videos.keySet().retainAll(channels.keySet()); checked.keySet().retainAll(channels.keySet());
        failures.keySet().retainAll(channels.keySet()); cached.keySet().retainAll(channels.keySet());
    }
    void live(Set<String> ids, long now) {
        if (hasLiveSample) for (String id : live) if (!ids.contains(id)) offlineSince.put(id, now);
        for (String id : ids) offlineSince.remove(id);
        offlineSince.keySet().retainAll(channels.keySet());
        live = Set.copyOf(ids); liveChecked = now; hasLiveSample = true;
    }
    List<String> offlineOrder(long now, java.util.function.ToDoubleFunction<String> relevance) {
        List<String> ids = new ArrayList<>();
        for (String id : channels.keySet()) if (offline(id, now)) ids.add(id);
        Map<String, Double> scores = new HashMap<>(); for (String id : ids) scores.put(id, relevance.applyAsDouble(id));
        ids.sort(Comparator.<String>comparingLong(id -> offlineSince.getOrDefault(id, 0L)).reversed()
                .thenComparing(Comparator.<String>comparingDouble(scores::get).reversed()));
        return ids;
    }
    boolean liveKnown(long now) { return liveChecked > 0 && now - liveChecked < 5 * 60_000; }
    long liveRevision(long now) { return liveKnown(now) ? liveChecked : 0; }
    boolean offline(String id, long now) { return liveKnown(now) && !live.contains(id); }
    String channelState(String id, long now) { return !liveKnown(now) ? "Status unknown" : live.contains(id) ? "Live now" : "Offline"; }
    void store(String channel, List<VideoItem> page, long now) {
        videos.put(channel, List.copyOf(page.subList(0, Math.min(5, page.size())))); checked.put(channel, now); failures.remove(channel);
        cached.remove(channel); cached.put(channel, true);
        while (cached.size() > 1000) {
            String evicted = cached.keySet().iterator().next(); cached.remove(evicted); videos.remove(evicted); checked.remove(evicted);
        }
    }
    void failed(String id, long now) { failures.put(id, now); }
    boolean fresh(String id, long now) {
        long ttl = videos.getOrDefault(id, List.of()).isEmpty() ? 30 * 60_000 : 15 * 60_000;
        return checked.containsKey(id) && now - checked.get(id) < ttl || failures.containsKey(id) && now - failures.get(id) < 60_000;
    }
    void invalidate() { checked.clear(); failures.clear(); liveChecked = 0; }
    int checkedCount() { return checked.size(); }
    int failureCount() { return failures.size(); }
    List<VideoItem> recent(boolean offlineOnly, long now) {
        Map<String, VideoItem> merged = new HashMap<>();
        for (var entry : videos.entrySet()) if (!offlineOnly || offline(entry.getKey(), now))
            entry.getValue().stream().max(Comparator.comparingLong(VideoItem::createdAt)).ifPresent(item -> merged.put(item.id(), item));
        List<VideoItem> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparingLong(VideoItem::createdAt).reversed().thenComparing(VideoItem::id)); return result;
    }
    List<Channel> findChannels(String query, boolean offlineOnly, long now) {
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        List<Channel> result = new ArrayList<>();
        for (Channel channel : channels.values()) if ((!offlineOnly || offline(channel.id(), now))
                && (channel.name().toLowerCase(java.util.Locale.ROOT).contains(needle) || channel.login().toLowerCase(java.util.Locale.ROOT).contains(needle))) result.add(channel);
        result.sort(Comparator.<Channel>comparingLong(c -> latest(c.id())).reversed().thenComparing(Channel::name, String.CASE_INSENSITIVE_ORDER)); return result;
    }
    long latest(String id) { return videos.getOrDefault(id, List.of()).stream().mapToLong(VideoItem::createdAt).max().orElse(0); }
}
