package tv.slink.app;

import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import java.util.Set;

/** Twitch titles live segments "live" and stitched ad segments "Vendor|creative id". */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class StitchedAds {
    private static final Set<String> VENDORS = Set.of("Amazon", "Adform", "DCM");

    static boolean within(HlsMediaPlaylist playlist, long fromMs, long toMs) {
        for (HlsMediaPlaylist.Segment segment : playlist.segments) {
            long startMs = segment.relativeStartTimeUs / 1000, endMs = (segment.relativeStartTimeUs + segment.durationUs) / 1000;
            if (startMs <= toMs && fromMs < endMs && VENDORS.contains(segment.title.split("\\|", 2)[0])) return true;
        }
        return false;
    }

    private StitchedAds() {}
}
