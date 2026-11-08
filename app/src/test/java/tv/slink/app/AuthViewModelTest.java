package tv.slink.app;

import android.app.Application;
import android.os.Looper;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AuthViewModelTest {
    private static final String CLIENT = "testpublicclient12345";
    private static final String VALID = "{\"client_id\":\"" + CLIENT + "\",\"user_id\":\"42\",\"login\":\"viewer\",\"scopes\":[\"user:read:follows\"],\"expires_in\":3600}";
    private static final String TOKENS = "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600,\"token_type\":\"bearer\"}";
    private static final String EMPTY = "{\"data\":[],\"pagination\":{}}";
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private AuthViewModel model;

    private class MemoryStore implements SessionStore {
        volatile TwitchApi.Session saved;
        MemoryStore(boolean expired) { saved = new TwitchApi.Session(CLIENT, "old-access", "old-refresh", expired ? 0 : System.currentTimeMillis() + 3_600_000); }
        @Override public TwitchApi.Session load() { return saved; }
        @Override public void save(TwitchApi.Session session) { saved = session; events.add("save:" + session.accessToken()); }
        @Override public void clear() { saved = null; events.add("clear"); }
    }

    private class FakeHttp implements TwitchApi.Transport {
        final ArrayDeque<Object> replies = new ArrayDeque<>();
        FakeHttp reply(int status, String body) throws Exception { replies.add(new TwitchApi.Reply(status, new JSONObject(body), 0)); return this; }
        FakeHttp data() throws Exception { return reply(200, EMPTY).reply(200, EMPTY).reply(200, EMPTY); }
        @Override public TwitchApi.Reply request(String method, String url, Map<String, String> headers, String form) throws Exception {
            events.add(url.substring(url.lastIndexOf('/') + 1) + ":" + headers.getOrDefault("Authorization", ""));
            Object next = replies.remove();
            if (next instanceof Exception error) throw error;
            return (TwitchApi.Reply) next;
        }
    }

    private void start(TwitchApi.Transport http, MemoryStore store) {
        Application application = RuntimeEnvironment.getApplication();
        model = new AuthViewModel(application, new TwitchApi(http), store);
    }

    private <T extends AuthViewModel.State> T await(Class<T> type) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (type.isInstance(model.state().getValue())) return type.cast(model.state().getValue());
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected " + type.getSimpleName() + ", got " + model.state().getValue());
    }

    @After public void close() { if (model != null) model.onCleared(); }

    @Test public void expiredSessionPersistsRotatedPairBeforeLoadingAccount() throws Exception {
        MemoryStore store = new MemoryStore(true);
        start(new FakeHttp().reply(200, TOKENS).reply(200, VALID).data(), store);
        assertEquals(0, await(AuthViewModel.Ready.class).snapshot().followed().length());
        assertEquals("new-refresh", store.saved.refreshToken());
        assertTrue(events.indexOf("save:new-access") < events.indexOf("validate:OAuth new-access"));
        assertTrue(events.contains("users?id=42:Bearer new-access"));
    }

    @Test public void validation401RefreshesOnceAndRecoversSavedLogin() throws Exception {
        MemoryStore store = new MemoryStore(false);
        start(new FakeHttp().reply(401, "{}").reply(200, TOKENS).reply(200, VALID).data(), store);
        await(AuthViewModel.Ready.class);
        assertEquals(1, events.stream().filter(event -> event.equals("token:")).count());
        assertEquals("new-refresh", store.saved.refreshToken());
    }

    @Test public void revokedRefreshClearsSessionAndRequiresLogin() throws Exception {
        MemoryStore store = new MemoryStore(false);
        start(new FakeHttp().reply(401, "{}").reply(400, "{\"message\":\"Invalid refresh token\"}"), store);
        await(AuthViewModel.SignedOut.class);
        assertNull(store.saved);
        assertEquals(1, events.stream().filter(event -> event.equals("token:")).count());
    }

    @Test public void transientFailureKeepsSavedLoginForRetry() throws Exception {
        MemoryStore store = new MemoryStore(false);
        FakeHttp http = new FakeHttp();
        http.replies.add(new IOException("offline"));
        http.reply(200, VALID).data();
        start(http, store);
        await(AuthViewModel.SignedOut.class);
        assertNotNull(store.saved);
        model.refreshData();
        await(AuthViewModel.Ready.class);
        assertFalse(events.contains("clear"));
    }

    @Test public void catalogBadRequestKeepsLoginAndAllowsRetry() throws Exception {
        MemoryStore store = new MemoryStore(false);
        start(new FakeHttp().reply(200, VALID).data().reply(400, "{}").reply(200, EMPTY), store);
        await(AuthViewModel.Ready.class);
        List<String> results = new ArrayList<>();
        model.request((api, session) -> api.popular(session, "en", "stale-cursor"), (page, error) -> {
            assertNull(page); assertNotNull(error); results.add("failed");
            model.request((api, session) -> api.popular(session, "en", ""), (retry, retryError) -> {
                assertNull(retryError); assertTrue(retry.items().isEmpty()); results.add("retried");
            });
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (results.size() < 2 && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
        assertEquals(List.of("failed", "retried"), results);
        assertTrue(model.state().getValue() instanceof AuthViewModel.Ready);
        assertNotNull(store.saved); assertFalse(events.contains("clear"));
    }

    @Test public void catalogUnauthorizedWithRevokedRefreshClearsLogin() throws Exception {
        MemoryStore store = new MemoryStore(false);
        start(new FakeHttp().reply(200, VALID).data().reply(401, "{}").reply(400, "{\"message\":\"Invalid refresh token\"}"), store);
        await(AuthViewModel.Ready.class);
        model.request((api, session) -> api.popular(session, "en", ""), (page, error) -> {});
        await(AuthViewModel.SignedOut.class);
        assertNull(store.saved);
    }

    @Test public void chatPermissionFailureDoesNotRemoveAccountLogin() throws Exception {
        MemoryStore store = new MemoryStore(false);
        start(new FakeHttp().reply(200, VALID).data(), store);
        await(AuthViewModel.Ready.class);
        List<String> errors = new ArrayList<>();
        model.requestChat((api, session) -> { throw new TwitchApi.ApiException(403, "Chat permission required"); },
                (value, error) -> errors.add(error));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (errors.isEmpty() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); }
        assertEquals(List.of("Chat permission required"), errors);
        assertNotNull(store.saved); assertFalse(events.contains("clear"));
        assertTrue(model.state().getValue() instanceof AuthViewModel.Ready);
    }

    @Test public void cancellingChatReauthorizationPreservesExistingTokenPair() throws Exception {
        MemoryStore store = new MemoryStore(false);
        String code = "{\"device_code\":\"new-device\",\"user_code\":\"ABCDEF\",\"verification_uri\":\"https://www.twitch.tv/activate\",\"expires_in\":1800}";
        start(new FakeHttp().reply(200, VALID).data().reply(200, code), store);
        await(AuthViewModel.Ready.class);
        model.signIn(CLIENT); await(AuthViewModel.Awaiting.class);
        assertEquals("old-access", store.saved.accessToken());
        model.cancelLogin(); await(AuthViewModel.Ready.class);
        assertEquals("old-refresh", store.saved.refreshToken());
        assertFalse(events.contains("clear"));
    }

    @Test public void signOutDuringFetchDiscardsLateDataAndClearsBeforeRevoking() throws Exception {
        MemoryStore store = new MemoryStore(false);
        FakeHttp http = new FakeHttp().reply(200, VALID).data().reply(200, "{}");
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch finishFetch = new CountDownLatch(1);
        start((method, url, headers, form) -> {
            if (url.contains("/helix/users")) {
                fetching.countDown();
                if (!finishFetch.await(5, TimeUnit.SECONDS)) throw new IOException("Test fetch timed out");
            }
            return http.request(method, url, headers, form);
        }, store);
        List<AuthViewModel.State> displayed = new ArrayList<>();
        model.state().observeForever(displayed::add);
        try {
            assertTrue(fetching.await(5, TimeUnit.SECONDS));
            model.signOut();
        } finally { finishFetch.countDown(); }
        await(AuthViewModel.SignedOut.class);
        assertNull(store.saved);
        assertTrue(events.indexOf("clear") < events.indexOf("revoke:"));
        assertFalse(displayed.stream().anyMatch(state -> state instanceof AuthViewModel.Ready));
    }
}
