package tv.slink.app;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import org.json.JSONObject;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A single worker owns token rotation, persistence, polling, and authenticated requests. */
public final class AuthViewModel extends AndroidViewModel {
    sealed interface State permits SignedOut, Working, Awaiting, Ready {}
    record SignedOut(String message) implements State {}
    record Working(String message) implements State {}
    record Awaiting(TwitchApi.DeviceCode code, String message) implements State {}
    record Ready(TwitchApi.Snapshot snapshot, String message) implements State {}

    private final MutableLiveData<State> state = new MutableLiveData<>(new Working("Checking saved login…"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final TwitchApi api;
    private final SessionStore store;
    private TwitchApi.Session session;
    private TwitchApi.DeviceCode deviceCode;
    private TwitchApi.Snapshot snapshot;
    private ScheduledFuture<?> scheduled;
    private int pollInterval;
    private long nextPollAt;
    private long validatedAt;
    private boolean foreground = true;
    private volatile boolean closed;

    public AuthViewModel(Application application) { this(application, new TwitchApi(), new EncryptedSessionStore(application)); }

    AuthViewModel(Application application, TwitchApi api, SessionStore store) {
        super(application);
        this.api = api;
        this.store = store;
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        worker.execute(() -> restore(0));
    }

    LiveData<State> state() { return state; }
    interface Request<T> { T run(TwitchApi api, TwitchApi.Session session) throws Exception; }
    interface Result<T> { void complete(T value, String error); }

    <T> void request(Request<T> request, Result<T> result) {
        request(request, result, true);
    }

    <T> void requestChat(Request<T> request, Result<T> result) {
        request(request, result, false);
    }

    private <T> void request(Request<T> request, Result<T> result, boolean invalidateForbidden) {
        int id = generation.get();
        worker.execute(() -> {
            if (!current(id)) return;
            try {
                if (session == null) throw new TwitchApi.ApiException(401, "Sign in to load Twitch data.");
                if (session.expiresAt() <= System.currentTimeMillis() + 60_000) rotate();
                T value;
                try { value = request.run(api, session); }
                catch (TwitchApi.ApiException exception) {
                    if (exception.status != 401) throw exception;
                    rotate();
                    api.validate(session);
                    value = request.run(api, session);
                }
                T data = value;
                main.post(() -> { if (current(id)) result.complete(data, null); });
            } catch (Exception exception) {
                String message = exception instanceof TwitchApi.ApiException ? exception.getMessage() : "Could not load streams. Check the connection and retry.";
                if (exception instanceof TwitchApi.ApiException apiError && (apiError.status == 401 || invalidateForbidden && apiError.status == 403)) fail(id, exception);
                main.post(() -> { if (current(id)) result.complete(null, message); });
            }
        });
    }
    private boolean current(int id) { return !closed && generation.get() == id; }
    private void emit(int id, State value) { main.post(() -> { if (current(id)) state.setValue(value); }); }
    private void cancelSchedule() { if (scheduled != null) scheduled.cancel(false); scheduled = null; }

    void signIn(String clientId) {
        int id = generation.incrementAndGet();
        emit(id, new Working("Requesting a login code from Twitch…"));
        worker.execute(() -> {
            if (!current(id)) return;
            cancelSchedule();
            deviceCode = null;
            try {
                TwitchApi.DeviceCode code = api.begin(clientId.trim(), System.currentTimeMillis());
                if (!current(id)) return;
                deviceCode = code;
                pollInterval = code.intervalSeconds();
                emit(id, new Awaiting(code, "Scan with your phone, sign in to Twitch, and approve access."));
                schedulePoll(id, pollInterval);
            } catch (Exception exception) { loginFailed(id, exception); }
        });
    }

    void cancelLogin() {
        int id = generation.incrementAndGet();
        worker.execute(() -> {
            cancelSchedule();
            deviceCode = null;
            try {
                if (snapshot != null) {
                    emit(id, new Ready(snapshot, "Login cancelled. Existing account retained."));
                    scheduleValidation(id);
                } else if (session != null) restore(id);
                else emit(id, new SignedOut("Login cancelled. You can request a new QR code."));
            } catch (Exception exception) { fail(id, exception); }
        });
    }

    void refreshData() {
        int id = generation.incrementAndGet();
        emit(id, new Working("Validating login and fetching Twitch data…"));
        worker.execute(() -> {
            if (!current(id)) return;
            cancelSchedule();
            deviceCode = null;
            restore(id);
        });
    }

    private void restore(int id) {
        if (!current(id)) return;
        try {
            if (session == null) session = store.load();
            if (session == null) { emit(id, new SignedOut("Sign in with your phone to load your followed channels.")); return; }
            loadData(id);
        } catch (Exception exception) { fail(id, exception); }
    }

    private void schedulePoll(int id, int seconds) {
        nextPollAt = System.currentTimeMillis() + seconds * 1000L;
        if (current(id) && foreground) scheduled = worker.schedule(() -> poll(id), seconds, TimeUnit.SECONDS);
    }

    private void poll(int id) {
        if (!current(id) || deviceCode == null) return;
        try {
            TwitchApi.PollResult result = api.poll(deviceCode, pollInterval, System.currentTimeMillis());
            if (!current(id)) return;
            if (result instanceof TwitchApi.Pending pending) {
                pollInterval = pending.delaySeconds();
                emit(id, new Awaiting(deviceCode, pending.message()));
                schedulePoll(id, pollInterval);
            } else if (result instanceof TwitchApi.Authorized authorized) {
                JSONObject validation = api.validate(authorized.session());
                if (!current(id)) return;
                store.save(authorized.session());
                session = authorized.session();
                snapshot = null;
                deviceCode = null;
                emit(id, new Working("Signed in. Loading profile and all followed-channel pages…"));
                loadProfile(id, validation);
            }
        } catch (java.io.IOException exception) {
            if (!current(id)) return;
            if (deviceCode == null) { fail(id, exception); return; }
            pollInterval = Math.min(60, Math.max(10, pollInterval * 2));
            emit(id, new Awaiting(deviceCode, "Connection interrupted. Retrying while this code is valid…"));
            schedulePoll(id, pollInterval);
        } catch (Exception exception) { deviceCode = null; loginFailed(id, exception); }
    }

    private void loginFailed(int id, Exception exception) {
        if (!current(id)) return;
        if (snapshot != null) {
            emit(id, new Ready(snapshot, "Authorization was not completed. Existing account retained."));
            scheduleValidation(id);
        } else fail(id, exception);
    }

    private void loadData(int id) throws Exception {
        if (!current(id)) return;
        JSONObject validation;
        if (session.expiresAt() <= System.currentTimeMillis() + 60_000) rotate();
        try { validation = api.validate(session); }
        catch (TwitchApi.ApiException exception) {
            if (exception.status != 401) throw exception;
            rotate();
            validation = api.validate(session);
        }
        loadProfile(id, validation);
    }

    private void loadProfile(int id, JSONObject validation) throws Exception {
        validatedAt = System.currentTimeMillis();
        if (!current(id)) return;
        TwitchApi.Snapshot data = api.fetch(session, validation);
        if (!current(id)) return;
        snapshot = data;
        emit(id, new Ready(data, "Signed in as " + validation.optString("login") + ". All pages loaded."));
        scheduleValidation(id);
    }

    private void rotate() throws Exception {
        TwitchApi.Session rotated;
        try { rotated = api.refresh(session, System.currentTimeMillis()); }
        catch (TwitchApi.ApiException exception) {
            // A rejected refresh is an expired session; a catalog HTTP 400 is only a failed request.
            if (exception.status == 400) throw new TwitchApi.ApiException(401, exception.getMessage());
            throw exception;
        }
        // Persist the replacement pair before another request can use the single-use refresh token.
        store.save(rotated);
        session = rotated;
    }

    private void scheduleValidation(int id) {
        cancelSchedule();
        if (current(id) && foreground && session != null) {
            long delay = Math.max(1000, Math.min(3_600_000 - (System.currentTimeMillis() - validatedAt),
                    session.expiresAt() - System.currentTimeMillis() - 60_000));
            scheduled = worker.schedule(() -> {
                try { loadData(id); } catch (Exception exception) { fail(id, exception); }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    void setForeground(boolean visible) {
        worker.execute(() -> {
            foreground = visible;
            cancelSchedule();
            int id = generation.get();
            if (!visible || !current(id)) return;
            if (deviceCode != null) {
                long delay = Math.max(0, nextPollAt - System.currentTimeMillis());
                scheduled = worker.schedule(() -> poll(id), delay, TimeUnit.MILLISECONDS);
            } else if (snapshot != null) scheduleValidation(id);
        });
    }

    void signOut() {
        int id = generation.incrementAndGet();
        emit(id, new Working("Signing out…"));
        worker.execute(() -> {
            cancelSchedule();
            deviceCode = null;
            TwitchApi.Session old = session;
            try {
                if (snapshot != null) {
                    String owner = snapshot.validation().optString("user_id");
                    new WatchHistory(getApplication()).clear(owner);
                    new VideoProgress(getApplication()).clear(owner);
                    getApplication().getSharedPreferences("video_discovery", 0).edit().remove("after:" + owner).apply();
                }
                store.clear();
                session = null;
                snapshot = null;
                if (old != null) api.revoke(old);
                emit(id, new SignedOut("Signed out. Saved credentials and displayed account data were cleared."));
            } catch (Exception exception) { fail(id, exception); }
        });
    }

    private void fail(int id, Exception exception) {
        if (!current(id)) return;
        String message = exception instanceof TwitchApi.ApiException ? exception.getMessage()
                : exception instanceof java.io.IOException ? "Network request failed. Check the connection and retry."
                : "Login or data loading failed. Retry, or sign out and sign in again.";
        if (exception instanceof TwitchApi.ApiException apiError && (apiError.status == 401 || apiError.status == 403)) {
            try { store.clear(); } catch (Exception ignored) { message += " Local session could not be cleared."; }
            session = null;
            snapshot = null;
        }
        if (snapshot == null) emit(id, new SignedOut(message));
        else {
            emit(id, new Ready(snapshot, message + " Showing previously loaded data."));
            if (foreground) scheduled = worker.schedule(() -> { try { loadData(id); } catch (Exception retryError) { fail(id, retryError); } }, 60, TimeUnit.SECONDS);
        }
    }

    @Override protected void onCleared() {
        closed = true;
        generation.incrementAndGet();
        // Drain already queued persistence/sign-out work, but never continue polling a closed screen.
        worker.shutdown();
    }
}
