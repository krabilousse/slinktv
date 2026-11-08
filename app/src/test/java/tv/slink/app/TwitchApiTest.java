package tv.slink.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TwitchApiTest {
    private static final String CLIENT = "testpublicclient12345";
    private static final TwitchApi.Session SESSION = new TwitchApi.Session(CLIENT, "private-access", "private-refresh", 999999);
    private static final String VALID = "{\"client_id\":\"" + CLIENT + "\",\"user_id\":\"42\",\"login\":\"viewer\",\"scopes\":[\"user:read:follows\"],\"expires_in\":3600}";
    private static final String TOKENS = "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600,\"token_type\":\"bearer\"}";
    record Request(String method, String url, Map<String, String> headers, String form) {}
    static class FakeHttp implements TwitchApi.Transport {
        final ArrayDeque<TwitchApi.Reply> replies = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();
        FakeHttp reply(int status, String json) throws Exception { replies.add(new TwitchApi.Reply(status, new JSONObject(json), 0)); return this; }
        @Override public TwitchApi.Reply request(String method, String url, Map<String, String> headers, String form) {
            requests.add(new Request(method, url, headers, form));
            return replies.remove();
        }
    }
    private TwitchApi.DeviceCode code(long expires) { return new TwitchApi.DeviceCode(CLIENT, "device-private", "DISPLAY", "https://www.twitch.tv/activate", expires, 5); }

    @Test public void createsPublicLoginWithFollowAndChatReadPermissionsOnly() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, "{\"device_code\":\"private\",\"user_code\":\"ABCDEF\",\"verification_uri\":\"https://www.twitch.tv/activate?device-code=ABCDEF&public=true\",\"interval\":5,\"expires_in\":1800}");
        var code = new TwitchApi(http).begin(CLIENT, 1000);
        assertEquals(1801000, code.expiresAt());
        assertEquals("ABCDEF", code.userCode());
        assertEquals("client_id=" + CLIENT + "&scopes=user%3Aread%3Afollows+user%3Aread%3Achat", http.requests.get(0).form());
        assertFalse(http.requests.get(0).form().contains("secret"));
    }

    @Test public void chatRegistersModerationBeforeMessagesAndUsesJson() throws Exception {
        FakeHttp http = new FakeHttp().reply(202, "{}").reply(202, "{}").reply(202, "{}").reply(202, "{}");
        new TwitchApi(http).subscribeChat(SESSION, "42", "100", "socket", () -> true);
        assertEquals(4, http.requests.size());
        for (Request request : http.requests) {
            assertEquals("application/json", request.headers().get("Content-Type"));
            JSONObject body = new JSONObject(request.form());
            assertEquals("42", body.getJSONObject("condition").getString("user_id"));
            assertEquals("100", body.getJSONObject("condition").getString("broadcaster_user_id"));
            assertEquals("socket", body.getJSONObject("transport").getString("session_id"));
            assertFalse(body.toString().contains("private-access"));
        }
        assertEquals("channel.chat.message", new JSONObject(http.requests.get(3).form()).getString("type"));
    }

    @Test public void cancelledChatDoesNotCreateSubscriptions() throws Exception {
        FakeHttp http = new FakeHttp();
        new TwitchApi(http).subscribeChat(SESSION, "42", "100", "socket", () -> false);
        assertTrue(http.requests.isEmpty());
    }

    @Test public void existingFollowOnlySessionRemainsValidWithoutChat() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, VALID);
        assertFalse(TwitchApi.canReadChat(new TwitchApi(http).validate(SESSION)));
        assertTrue(TwitchApi.canReadChat(new JSONObject(VALID).put("scopes", new JSONArray().put(TwitchApi.SCOPE).put(TwitchApi.CHAT_SCOPE))));
    }

    @Test public void pendingAndSlowDownRespectTwitchMessageField() throws Exception {
        FakeHttp http = new FakeHttp().reply(400, "{\"message\":\"authorization_pending\"}").reply(400, "{\"error\":\"slow_down\"}");
        TwitchApi api = new TwitchApi(http);
        assertEquals(5, ((TwitchApi.Pending) api.poll(code(100000), 5, 0)).delaySeconds());
        assertEquals(10, ((TwitchApi.Pending) api.poll(code(100000), 5, 0)).delaySeconds());
        assertTrue(http.requests.get(0).form().contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"));
    }

    @Test public void rateLimitsAndServerErrorsRemainRetryable() throws Exception {
        FakeHttp http = new FakeHttp();
        http.replies.add(new TwitchApi.Reply(429, new JSONObject(), 60));
        http.reply(503, "{}");
        TwitchApi api = new TwitchApi(http);
        assertEquals(60, ((TwitchApi.Pending) api.poll(code(100000), 5, 0)).delaySeconds());
        assertEquals(15, ((TwitchApi.Pending) api.poll(code(100000), 5, 0)).delaySeconds());
    }

    @Test public void expiredCodeDoesNotSendAnotherRequest() throws Exception {
        FakeHttp http = new FakeHttp();
        assertThrows(TwitchApi.ApiException.class, () -> new TwitchApi(http).poll(code(100), 5, 100));
        assertTrue(http.requests.isEmpty());
    }

    @Test public void declinedOrConsumedCodesAreTerminal() throws Exception {
        for (String error : List.of("access_denied", "invalid device code", "expired_token")) {
            FakeHttp http = new FakeHttp().reply(400, new JSONObject().put("message", error).toString());
            assertThrows(TwitchApi.ApiException.class, () -> new TwitchApi(http).poll(code(100000), 5, 0));
        }
    }

    @Test public void tokensAreAcceptedAndRefreshIsPublicClientOnly() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, TOKENS).reply(200, TOKENS);
        TwitchApi api = new TwitchApi(http);
        var authorized = (TwitchApi.Authorized) api.poll(code(100000), 5, 1000);
        assertEquals(3601000, authorized.session().expiresAt());
        assertEquals("new-refresh", authorized.session().refreshToken());
        api.refresh(SESSION, 1000);
        assertTrue(http.requests.get(1).form().contains("refresh_token=private-refresh"));
        assertFalse(http.requests.get(1).form().contains("client_secret"));
        assertFalse(authorized.session().toString().contains("new-access"));
    }

    @Test public void missingScopeOrWrongClientCannotLoadUserData() throws Exception {
        FakeHttp missing = new FakeHttp().reply(200, new JSONObject(VALID).put("scopes", new JSONArray()).toString());
        assertThrows(TwitchApi.ApiException.class, () -> new TwitchApi(missing).validate(SESSION));
        FakeHttp mismatch = new FakeHttp().reply(200, new JSONObject(VALID).put("client_id", "other-app").toString());
        assertThrows(TwitchApi.ApiException.class, () -> new TwitchApi(mismatch).validate(SESSION));
    }

    @Test public void loadsAllFollowAndLivePagesWithValidatedUserAndBearerHeaders() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, VALID).reply(200, "{\"data\":[{\"id\":\"42\",\"display_name\":\"Viewer\"}]}")
                .reply(200, "{\"total\":2,\"data\":[{\"broadcaster_id\":\"1\",\"broadcaster_name\":\"First\"}],\"pagination\":{\"cursor\":\"next+one=\"}}")
                .reply(200, "{\"total\":2,\"data\":[{\"broadcaster_id\":\"2\",\"broadcaster_name\":\"Second\"}],\"pagination\":{}}")
                .reply(200, "{\"data\":[{\"user_id\":\"2\"}],\"pagination\":{\"cursor\":\"live-next\"}}")
                .reply(200, "{\"data\":[],\"pagination\":{}}");
        TwitchApi api = new TwitchApi(http);
        var snapshot = api.fetch(SESSION, api.validate(SESSION));
        assertEquals(2, snapshot.followed().length());
        assertEquals(1, snapshot.live().length());
        assertEquals(2, snapshot.followedPages().length());
        assertEquals(2, snapshot.livePages().length());
        assertTrue(http.requests.get(3).url().endsWith("user_id=42&first=100&after=next%2Bone%3D"));
        for (int i = 1; i < http.requests.size(); i++) {
            assertEquals("Bearer private-access", http.requests.get(i).headers().get("Authorization"));
            assertEquals(CLIENT, http.requests.get(i).headers().get("Client-Id"));
            assertFalse(http.requests.get(i).url().contains("private-access"));
        }
        assertEquals("OAuth private-access", http.requests.get(0).headers().get("Authorization"));
        assertFalse(snapshot.validation().toString().contains("private-access"));
    }

    @Test public void repeatedCursorAndPartialFailuresAreNotReportedAsComplete() throws Exception {
        String repeated = "{\"data\":[],\"pagination\":{\"cursor\":\"same\"}}";
        FakeHttp loop = new FakeHttp().reply(200, "{\"data\":[]}").reply(200, repeated).reply(200, repeated);
        assertThrows(TwitchApi.ApiException.class, () -> new TwitchApi(loop).fetch(SESSION, new JSONObject(VALID)));
        FakeHttp failure = new FakeHttp().reply(200, "{\"data\":[]}").reply(200, repeated).reply(500, "{}");
        assertThrows(TwitchApi.ApiException.class, () -> new TwitchApi(failure).fetch(SESSION, new JSONObject(VALID)));
    }

    @Test public void emptyFollowsIsSuccessfulAndErrorsNeverEchoCredentials() throws Exception {
        FakeHttp empty = new FakeHttp().reply(200, "{\"data\":[]}").reply(200, "{\"data\":[],\"pagination\":{}}").reply(200, "{\"data\":[],\"pagination\":{}}");
        assertEquals(0, new TwitchApi(empty).fetch(SESSION, new JSONObject(VALID)).followed().length());
        FakeHttp bad = new FakeHttp().reply(400, "{\"message\":\"private-access leaked by remote\"}");
        Exception error = assertThrows(TwitchApi.ApiException.class, () -> new TwitchApi(bad).poll(code(100000), 5, 0));
        assertFalse(error.getMessage().contains("private-access"));
    }

    @Test public void rejectsNonTwitchVerificationLinks() throws Exception {
        FakeHttp http = new FakeHttp().reply(200, "{\"verification_uri\":\"https://twitch.tv.attacker.example/activate\"}");
        assertThrows(TwitchApi.ApiException.class, () -> new TwitchApi(http).begin(CLIENT, 0));
    }
}
