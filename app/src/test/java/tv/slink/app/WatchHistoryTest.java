package tv.slink.app;

import android.content.Context;
import java.time.Instant;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WatchHistoryTest {
    private static final String ACCOUNT = "100";
    private static final String CHANNEL = "200";
    private Context context;
    private FakeClock clock;
    private WatchHistory history;

    private static final class FakeClock implements WatchHistory.Clock {
        long elapsed = 1_000;
        long wall = Instant.parse("2026-11-08T10:00:00Z").toEpochMilli();
        @Override public long elapsedMillis() { return elapsed; }
        @Override public long wallMillis() { return wall; }
        void advance(long millis) { elapsed += millis; wall += millis; }
    }

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("watch_history", Context.MODE_PRIVATE).edit().clear().commit();
        clock = new FakeClock();
        history = new WatchHistory(context, clock);
    }

    private void watch(long millis) {
        WatchHistory.Tracker tracker = history.tracker(ACCOUNT, CHANNEL);
        tracker.update(true, true, true);
        clock.advance(millis);
        tracker.stop();
    }

    @Test public void defaultsToNoCollectionAndNoRelevanceIncludingGuests() {
        assertFalse(history.isEnabled(ACCOUNT));
        assertFalse(history.isRelevant(ACCOUNT));
        watch(60_000);
        assertEquals(0, history.score(ACCOUNT, CHANNEL), 0);
        history.setEnabled("", true);
        history.setRelevant("", true);
        WatchHistory.Tracker guest = history.tracker("", CHANNEL);
        guest.update(true, true, true);
        clock.advance(60_000);
        guest.stop();
        assertEquals(0, history.score("", CHANNEL), 0);
        assertFalse(history.isEnabled(""));
    }

    @Test public void capsDailyContributionAndHalvesItAfterFourteenDays() {
        history.setRelevant(ACCOUNT, true);
        assertTrue(history.isEnabled(ACCOUNT));
        watch(180 * 60_000L);
        assertEquals(120, history.score(ACCOUNT, CHANNEL), 0.00001);
        clock.advance(14 * 86_400_000L);
        assertEquals(60, history.score(ACCOUNT, CHANNEL), 0.00001);
        clock.advance(76 * 86_400_000L);
        assertEquals(0, history.score(ACCOUNT, CHANNEL), 0);
    }

    @Test public void onlyPlayingVisibleScreenOnIntervalsCount() {
        history.setEnabled(ACCOUNT, true);
        WatchHistory.Tracker tracker = history.tracker(ACCOUNT, CHANNEL);
        tracker.update(true, true, true);
        clock.advance(15_000);
        tracker.update(false, true, true); // pause or buffering
        clock.advance(60_000);
        tracker.update(true, true, true);
        clock.advance(15_000);
        tracker.update(true, false, true); // another app covers the player
        clock.advance(60_000);
        tracker.update(true, true, true);
        clock.advance(15_000);
        tracker.update(true, true, false); // TV screen off
        clock.advance(60_000);
        tracker.stop();
        assertEquals(0.75, history.score(ACCOUNT, CHANNEL), 0.00001);
    }

    @Test public void checkpointsAndStopNeverCountTheSameIntervalTwice() {
        history.setEnabled(ACCOUNT, true);
        WatchHistory.Tracker tracker = history.tracker(ACCOUNT, CHANNEL);
        tracker.update(true, true, true);
        clock.advance(15_000);
        tracker.update(true, true, true);
        assertEquals(0.25, history.score(ACCOUNT, CHANNEL), 0.00001);
        clock.advance(15_000);
        tracker.stop();
        tracker.stop();
        assertEquals(0.5, history.score(ACCOUNT, CHANNEL), 0.00001);
    }

    @Test public void preferencesAndDailyTotalsAreScopedToTheAccountAndPersist() {
        history.setRelevant(ACCOUNT, true);
        watch(60_000);
        WatchHistory reloaded = new WatchHistory(context, clock);
        assertTrue(reloaded.isRelevant(ACCOUNT));
        assertEquals(1, reloaded.score(ACCOUNT, CHANNEL), 0.00001);
        assertFalse(reloaded.isEnabled("300"));
        assertEquals(0, reloaded.score("300", CHANNEL), 0);
        assertEquals(0, reloaded.score(ACCOUNT, "400"), 0);
    }

    @Test public void clearInvalidatesPendingWritesEvenIfCollectionIsReenabled() {
        history.setRelevant(ACCOUNT, true);
        WatchHistory.Tracker tracker = history.tracker(ACCOUNT, CHANNEL);
        tracker.update(true, true, true);
        clock.advance(15_000);
        history.clear(ACCOUNT);
        assertFalse(history.isRelevant(ACCOUNT));
        assertFalse(history.isEnabled(ACCOUNT));
        history.setEnabled(ACCOUNT, true);
        tracker.update(true, true, true);
        clock.advance(60_000);
        tracker.stop();
        assertEquals(0, history.score(ACCOUNT, CHANNEL), 0);
        watch(60_000);
        assertEquals(1, history.score(ACCOUNT, CHANNEL), 0.00001);
    }

    @Test public void disablingCollectionInvalidatesOutstandingInterval() {
        history.setRelevant(ACCOUNT, true);
        WatchHistory.Tracker tracker = history.tracker(ACCOUNT, CHANNEL);
        tracker.update(true, true, true);
        clock.advance(15_000);
        history.setEnabled(ACCOUNT, false);
        tracker.stop();
        assertFalse(history.isRelevant(ACCOUNT));
        assertEquals(0, history.score(ACCOUNT, CHANNEL), 0);
    }

    @Test public void splitsAnIntervalAtUtcMidnight() {
        clock.wall = Instant.parse("2026-11-08T23:59:50Z").toEpochMilli();
        history.setEnabled(ACCOUNT, true);
        watch(20_000);
        assertEquals((10.0 / 60) * Math.pow(2, -1.0 / 14) + 10.0 / 60,
                history.score(ACCOUNT, CHANNEL), 0.00001);
    }

    @Test public void wallClockChangesDoNotCreateViewingTimeOrArbitraryDayBuckets() {
        history.setEnabled(ACCOUNT, true);
        WatchHistory.Tracker tracker = history.tracker(ACCOUNT, CHANNEL);
        tracker.update(true, true, true);
        clock.advance(15_000);
        clock.wall += 3_600_000;
        tracker.update(true, true, true);
        assertEquals(0, history.score(ACCOUNT, CHANNEL), 0);
        clock.advance(15_000);
        tracker.stop();
        assertEquals(0.25, history.score(ACCOUNT, CHANNEL), 0.00001);
    }
}
