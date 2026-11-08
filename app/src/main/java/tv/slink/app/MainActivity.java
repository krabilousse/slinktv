package tv.slink.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private enum Screen { HOME, VIDEOS, SEARCH, SETTINGS }
    private final ExecutorService directory = Executors.newSingleThreadExecutor();
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private final List<StreamChannel> streams = new ArrayList<>();
    private final Set<String> seenCursors = new java.util.HashSet<>();
    private AuthViewModel account;
    private TwitchApi.Snapshot snapshot;
    private WatchHistory history;
    private LinearLayout body;
    private TextView title, status;
    private Button accountButton, language, recovery;
    private Button homeTab, videosTab, searchTab, settingsTab;
    private VideosScreen videos;
    private TvPillGroup feeds, sort;
    private ProgressBar opening;
    private RecyclerView grid;
    private StreamWallAdapter cards;
    private EditText search;
    private AlertDialog dialog;
    private Screen screen = Screen.HOME;
    private String accountId = "", accountName = "", query = "", cursor = "", focusedId = "";
    private boolean following = true, loading, resolving, destroyed, pagingFailed;
    private int generation, playGeneration;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        account = ((SlinkApplication) getApplication()).account();
        history = new WatchHistory(this);
        LinearLayout root = column();
        root.setBackgroundColor(TvUi.BACKGROUND);
        root.setPadding(dp(32), dp(10), dp(32), dp(8));
        LinearLayout navigation = row();
        TextView logo = TvUi.text(this, "▶ SlinkTV", 23, TvUi.ACCENT);
        logo.setPadding(dp(5), 0, dp(24), 0);
        navigation.addView(logo);
        homeTab = button("Home", v -> navigate(Screen.HOME));
        videosTab = button("Videos", v -> navigate(Screen.VIDEOS));
        searchTab = button("Search", v -> navigate(Screen.SEARCH));
        settingsTab = button("Settings", v -> navigate(Screen.SETTINGS));
        for (Button tab : new Button[]{homeTab, videosTab, searchTab, settingsTab}) {
            TvUi.styleButton(tab, 6, Color.TRANSPARENT);
            navigation.addView(tab);
        }
        navigation.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        accountButton = button("Account", v -> { if (signedIn()) navigate(Screen.SETTINGS); else signIn(); });
        accountButton.setMaxWidth(dp(180)); accountButton.setEllipsize(TextUtils.TruncateAt.END);
        navigation.addView(accountButton);
        LinearLayout.LayoutParams navigationSize = new LinearLayout.LayoutParams(-1, dp(40));
        navigationSize.bottomMargin = dp(8);
        root.addView(navigation, navigationSize);
        body = column();
        videos = new VideosScreen(this, account, this::signIn);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.hide(WindowInsets.Type.systemBars());
            insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        showBrowse();
        account.state().observe(this, this::accountChanged);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (resolving) { cancelPlayback(); return; }
                if (screen == Screen.VIDEOS && videos.back()) return;
                if (screen != Screen.HOME) { navigate(Screen.HOME); return; }
                finish();
            }
        });
    }

    private int dp(int value) { return TvUi.dp(this, value); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private Button button(String text, View.OnClickListener action) {
        Button b = TvUi.button(this, text, action);
        b.setTextSize(15); b.setMinHeight(dp(40)); b.setPadding(dp(16), dp(4), dp(16), dp(4));
        return b;
    }
    private boolean signedIn() { return !accountId.isEmpty(); }

    private void accountChanged(AuthViewModel.State state) {
        if (state instanceof AuthViewModel.Ready ready) {
            boolean changed = snapshot != ready.snapshot();
            boolean newlySignedIn = !signedIn();
            if (!accountId.equals(ready.snapshot().validation().optString("user_id"))) {
                streams.clear(); cursor = ""; focusedId = "";
            }
            snapshot = ready.snapshot();
            accountId = snapshot.validation().optString("user_id");
            accountName = snapshot.validation().optString("login");
            accountButton.setText(accountName);
            videos.updateAccount(snapshot);
            if (screen == Screen.VIDEOS) { videos.resume(); return; }
            if (newlySignedIn) following = true;
            if (screen == Screen.SETTINGS) { showSettings(null); return; }
            updateHeader();
            if (changed || newlySignedIn) load(false);
        } else if (state instanceof AuthViewModel.SignedOut) {
            boolean changed = signedIn() || snapshot != null;
            if (changed) { streams.clear(); cursor = ""; focusedId = ""; }
            accountId = ""; accountName = ""; snapshot = null; following = false;
            accountButton.setText("Sign in");
            videos.updateAccount(null);
            if (screen == Screen.VIDEOS) { videos.resume(); return; }
            if (screen == Screen.SETTINGS) { showSettings(null); return; }
            updateHeader();
            if (changed || streams.isEmpty()) load(false);
        } else if (state instanceof AuthViewModel.Working) {
            if (screen != Screen.SETTINGS && screen != Screen.VIDEOS && status != null) status.setText("Loading account…");
        } else if (state instanceof AuthViewModel.Awaiting) accountButton.setText("Sign in");
    }

    private void navigate(Screen destination) {
        cancelPlayback();
        if (screen == Screen.VIDEOS) videos.hide();
        generation++;
        screen = destination;
        cursor = ""; streams.clear(); loading = false;
        if (destination == Screen.VIDEOS) { body.removeAllViews(); body.addView(videos.show(snapshot), new LinearLayout.LayoutParams(-1,-1)); }
        else if (destination == Screen.SETTINGS) showSettings(null);
        else { showBrowse(); load(false); }
        updateNavigation();
    }

    private void updateNavigation() {
        homeTab.setSelected(screen == Screen.HOME);
        videosTab.setSelected(screen == Screen.VIDEOS);
        searchTab.setSelected(screen == Screen.SEARCH);
        settingsTab.setSelected(screen == Screen.SETTINGS);
    }

    private void showBrowse() {
        body.removeAllViews();
        LinearLayout heading = row();
        title = TvUi.text(this, "Streams", 22, Color.WHITE);
        title.setPadding(dp(6), 0, dp(10), 0);
        heading.addView(title);
        feeds = new TvPillGroup(this, new String[]{"Following", "Popular"}, index -> selectFeed(index == 0));
        heading.addView(feeds);
        sort = new TvPillGroup(this, new String[]{"Most viewers", "Relevant"}, index -> selectSort(index == 1));
        LinearLayout.LayoutParams sortSpacing = new LinearLayout.LayoutParams(-2, -2);
        sortSpacing.setMarginStart(dp(24));
        heading.addView(sort, sortSpacing);
        heading.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        language = button("Language", v -> chooseLanguage());
        LinearLayout.LayoutParams languageSpacing = new LinearLayout.LayoutParams(-2, -2);
        languageSpacing.setMarginStart(dp(24)); languageSpacing.setMarginEnd(dp(16));
        heading.addView(language, languageSpacing);
        heading.addView(button("Refresh", v -> refresh()));
        recovery = button("Retry", v -> load(false));
        recovery.setVisibility(View.GONE);
        heading.addView(recovery);
        LinearLayout.LayoutParams headingSize = new LinearLayout.LayoutParams(-1, dp(42));
        body.addView(heading, headingSize);
        if (screen == Screen.SEARCH) {
            LinearLayout inputRow = row();
            search = new EditText(this);
            search.setSingleLine(true); search.setTextSize(19); search.setTextColor(Color.WHITE);
            search.setHintTextColor(TvUi.MUTED); search.setText(query);
            search.setHint(signedIn() ? "Channel or category" : "Exact Twitch channel name");
            search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            search.setOnEditorActionListener((view, action, event) -> { if (action == EditorInfo.IME_ACTION_SEARCH) { submitSearch(); return true; } return false; });
            inputRow.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
            inputRow.addView(button("Search", v -> submitSearch()));
            inputRow.addView(button("Open channel", v -> openManual()));
            body.addView(inputRow);
        }
        status = TvUi.text(this, "Loading…", 13, TvUi.MUTED);
        status.setPadding(dp(6), 0, 0, 0); status.setSingleLine(true);
        LinearLayout statusRow = row();
        opening = new ProgressBar(this);
        opening.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(TvUi.ACCENT));
        opening.setVisibility(View.GONE);
        statusRow.addView(opening, new LinearLayout.LayoutParams(dp(18), dp(18)));
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(statusRow, new LinearLayout.LayoutParams(-1, dp(18)));
        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        grid.setHasFixedSize(true); grid.setItemAnimator(null); grid.setClipToPadding(false);
        grid.setPadding(0, dp(2), 0, dp(2));
        cards = new StreamWallAdapter((getResources().getDisplayMetrics().widthPixels - dp(64)) / 3, this::play);
        grid.setAdapter(cards);
        body.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                if (dy > 0 && !pagingFailed && !streams.isEmpty() && ((GridLayoutManager) view.getLayoutManager()).findLastVisibleItemPosition() >= streams.size() - 4) load(true);
            }
        });
        updateHeader();
    }

    private void updateHeader() {
        updateNavigation();
        if (screen == Screen.SETTINGS || screen == Screen.VIDEOS || title == null) return;
        title.setText(screen == Screen.SEARCH ? "Search" : signedIn() && following ? "Following" : "Popular");
        title.setVisibility(screen == Screen.HOME && signedIn() ? View.GONE : View.VISIBLE);
        feeds.setVisibility(screen == Screen.HOME && signedIn() ? View.VISIBLE : View.GONE);
        feeds.select(following ? 0 : 1);
        sort.setVisibility(signedIn() && following && screen == Screen.HOME ? View.VISIBLE : View.GONE);
        sort.select(history.isRelevant(accountId) ? 1 : 0);
        language.setVisibility(screen == Screen.HOME && (!signedIn() || !following) ? View.VISIBLE : View.GONE);
        language.setText("Language: " + languageLabel());
    }
    private void submitSearch() {
        query = search.getText().toString().trim();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0);
        load(false);
    }
    private void refresh() {
        if (signedIn() && following && screen == Screen.HOME) { status.setText("Refreshing…"); account.refreshData(); }
        else load(false);
    }

    private void load(boolean append) {
        if (screen == Screen.SETTINGS || screen == Screen.VIDEOS || destroyed || (append && (loading || cursor.isEmpty()))) return;
        int request = ++generation;
        if (!append) { seenCursors.clear(); cursor = ""; }
        if (screen == Screen.SEARCH && query.isEmpty()) {
            streams.clear(); cards.submit(streams); status.setText(signedIn() ? "Enter a channel or category." : "Enter an exact Twitch channel name.");
            recovery.setVisibility(View.GONE); loading = false; return;
        }
        if (!append && streams.isEmpty()) cards.showLoading();
        loading = true; pagingFailed = false;
        status.setText(append ? "Loading more…" : "Loading streams…"); recovery.setVisibility(View.GONE);
        String after = cursor, requestedQuery = query, requestedLanguage = languageCode();
        Screen requestedScreen = screen;
        if (signedIn() && snapshot != null) {
            TwitchApi.Snapshot data = snapshot;
            boolean followed = following && screen == Screen.HOME;
            account.request((api, session) -> followed
                    ? new TwitchApi.StreamPage(api.cards(session, data.live()), "")
                    : requestedScreen == Screen.SEARCH ? api.search(session, requestedQuery, after)
                    : api.popular(session, requestedLanguage, after), (page, error) -> complete(request, append, page, error, false));
        } else {
            directory.execute(() -> {
                TwitchApi.StreamPage page = null; String error = null; boolean signInRequired = false;
                try {
                    JSONObject response = new JSONObject(StreamEngine.call(this, "directory", "fetch", requestedLanguage, after,
                            requestedScreen == Screen.SEARCH ? requestedQuery : ""));
                    signInRequired = response.optBoolean("sign_in_required");
                    if (response.has("error")) error = response.getString("error");
                    else page = new TwitchApi.StreamPage(StreamChannel.fromGuest(response.getJSONArray("items")), response.optString("cursor"));
                } catch (Exception exception) { error = "Could not load streams. Retry or sign in."; }
                TwitchApi.StreamPage result = page; String message = error; boolean needsLogin = signInRequired;
                runOnUiThread(() -> complete(request, append, result, message, needsLogin));
            });
        }
    }

    private void complete(int request, boolean append, TwitchApi.StreamPage page, String error, boolean signInRequired) {
        if (destroyed || request != generation || screen == Screen.SETTINGS || screen == Screen.VIDEOS) return;
        loading = false;
        if (error != null) {
            status.setText(error);
            if (streams.isEmpty()) cards.submit(List.of());
            pagingFailed = append;
            recovery.setVisibility(View.VISIBLE);
            if (signInRequired && !signedIn()) {
                cursor = ""; recovery.setText("Sign in"); recovery.setOnClickListener(v -> signIn());
            } else { recovery.setText("Retry"); recovery.setOnClickListener(v -> load(append)); }
            return;
        }
        String selected = cards.focusedId(); boolean restoreFocus = grid.hasFocus();
        if (!append) streams.clear();
        Map<String, StreamChannel> unique = new LinkedHashMap<>();
        for (StreamChannel item : streams) unique.put(item.id(), item);
        for (StreamChannel item : page.items()) unique.putIfAbsent(item.id(), item);
        streams.clear(); streams.addAll(unique.values());
        cursor = page.cursor(); if (!cursor.isEmpty() && !seenCursors.add(cursor)) cursor = "";
        sortStreams(); cards.submit(streams);
        status.setText(streams.isEmpty() ? "No live streams found." : streams.size() + " live streams");
        recovery.setVisibility(View.GONE);
        if (restoreFocus) focusCard(selected);
        RecyclerView target = grid;
        target.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                target.getViewTreeObserver().removeOnPreDrawListener(this);
                if (target == grid && request == generation && !destroyed && !loading && !pagingFailed
                        && !cursor.isEmpty() && !target.canScrollVertically(1)) load(true);
                return true;
            }
        });
        target.invalidate();
    }
    private void sortStreams() {
        Comparator<StreamChannel> byViewers = Comparator.comparingInt(StreamChannel::viewers).reversed().thenComparing(StreamChannel::id);
        if (signedIn() && following && screen == Screen.HOME && history.isRelevant(accountId)) {
            Map<String, Double> scores = new HashMap<>();
            for (StreamChannel item : streams) scores.put(item.id(), history.score(accountId, item.id()));
            streams.sort(Comparator.<StreamChannel>comparingDouble(item -> scores.get(item.id())).reversed().thenComparing(byViewers));
        } else streams.sort(byViewers);
    }
    private void focusCard(String id) {
        int position = cards.indexOf(id); if (position < 0) return;
        RecyclerView target = grid;
        target.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                target.getViewTreeObserver().removeOnPreDrawListener(this);
                if (target != grid || destroyed) return true;
                RecyclerView.ViewHolder holder = target.findViewHolderForAdapterPosition(cards.indexOf(id));
                if (holder != null) holder.itemView.requestFocus();
                return true;
            }
        });
        grid.scrollToPosition(position);
        grid.requestLayout();
    }

    private void play(StreamChannel item) {
        if (resolving) return;
        focusedId = item.id(); resolving = true; int request = ++playGeneration;
        cards.setLoadingChannel(item.login());
        if (item.id().isEmpty()) opening.setVisibility(View.VISIBLE);
        String owner = accountId, proxies = new AdFreeProxy(this).requestJson();
        resolver.execute(() -> {
            String response;
            try { response = StreamEngine.call(this, "resolver", "resolve", item.login(), proxies); }
            catch (Exception exception) { response = "{\"error\":\"Stream engine unavailable. Restart the app.\"}"; }
            String data = response;
            runOnUiThread(() -> completePlayback(request, item, owner, data));
        });
    }
    private void completePlayback(int request, StreamChannel item, String owner, String data) {
        if (destroyed || request != playGeneration) return;
        resolving = false; cards.setLoadingChannel(""); opening.setVisibility(View.GONE);
        try {
            JSONObject stream = new JSONObject(data);
            if (stream.has("error")) { showError(stream.getString("error")); return; }
            if (stream.getJSONArray("variants").length() == 0) throw new IllegalStateException();
            startActivity(new Intent(this, PlayerActivity.class).putExtra("stream", data)
                    .putExtra("account_id", owner).putExtra("broadcaster_id", item.id()).putExtra("display_name", item.name())
                    .putExtra("stream_title", item.title()).putExtra("game_name", item.game()).putExtra("viewer_count", (long) item.viewers())
                    .putExtra("avatar_url", item.avatar()));
            if (!stream.optString("proxy").isEmpty()) Toast.makeText(this, "Ad-free proxy: " + stream.getString("proxy"), Toast.LENGTH_SHORT).show();
            else if (stream.optBoolean("proxy_failed")) Toast.makeText(this, "Ad-free proxies unavailable. Playing directly; ads may appear.", Toast.LENGTH_LONG).show();
        } catch (Exception exception) { showError("The stream response was incomplete. Retry."); }
    }
    private void cancelPlayback() {
        playGeneration++; resolving = false;
        if (cards != null) cards.setLoadingChannel("");
        if (opening != null) opening.setVisibility(View.GONE);
    }
    private void showError(String message) { dialog = new AlertDialog.Builder(this).setTitle("Stream unavailable").setMessage(message).setPositiveButton("Close", null).show(); }
    private void signIn() { cancelPlayback(); startActivity(new Intent(this, LoginActivity.class).putExtra("auto_login", true)); }
    private void openManual() {
        String name = search.getText().toString().trim(); if (name.isEmpty()) { search.requestFocus(); return; }
        play(new StreamChannel("", name, name, "", "", "", "", 0, ""));
    }
    private void selectFeed(boolean followed) {
        if (following == followed || !signedIn()) return;
        cancelPlayback(); following = followed;
        focusedId = ""; streams.clear(); updateHeader(); load(false);
    }
    private void chooseSort() {
        dialog = new AlertDialog.Builder(this).setTitle("Sort followed streams")
                .setSingleChoiceItems(new String[]{"Most viewers", "Relevant"}, history.isRelevant(accountId) ? 1 : 0,
                        (d, index) -> {
                            d.dismiss();
                            selectSort(index == 1);
                        }).setNegativeButton("Cancel", null).show();
    }
    private void selectSort(boolean relevant) {
        if (relevant && !history.isEnabled(accountId)) {
            dialog = new AlertDialog.Builder(this).setTitle("Use viewing history?")
                    .setMessage("Order followed streams by time watched on this TV. History is stored only for this account on this device.")
                    .setPositiveButton("Enable", (a, b) -> applySort(true)).setNegativeButton("Cancel", null).show();
        } else applySort(relevant);
    }
    private void applySort(boolean relevant) {
        history.setRelevant(accountId, relevant);
        if (screen == Screen.SETTINGS) showSettings("sort"); else { sortStreams(); cards.submit(streams); updateHeader(); }
    }
    private static final String[] LANGUAGE_CODES = {"system", "", "en", "fr", "de", "es", "it", "pt", "ja", "ko", "ru", "pl", "tr", "uk", "zh", "nl", "sv", "ar", "hi"};
    private String languageCode() {
        String selected = getSharedPreferences("browse", MODE_PRIVATE).getString("language", "system");
        if (!selected.equals("system")) return selected;
        String system = getResources().getConfiguration().getLocales().get(0).getLanguage();
        if (system.equals("nb") || system.equals("nn")) system = "no";
        return Set.of("en","fr","de","es","it","pt","ja","ko","ru","pl","tr","uk","zh","nl","sv","ar","hi","da","no","fi","cs","hu","ro","el","bg","sk","th","vi","id","tl").contains(system) ? system : "";
    }
    private String languageLabel() { String code = languageCode(); return code.isEmpty() ? "All languages" : Locale.forLanguageTag(code).getDisplayLanguage(); }
    private void chooseLanguage() {
        String[] names = new String[LANGUAGE_CODES.length];
        String selected = getSharedPreferences("browse", MODE_PRIVATE).getString("language", "system"); int current = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = i == 0 ? "System language" : i == 1 ? "All languages" : Locale.forLanguageTag(LANGUAGE_CODES[i]).getDisplayLanguage();
            if (LANGUAGE_CODES[i].equals(selected)) current = i;
        }
        dialog = new AlertDialog.Builder(this).setTitle("Popular stream language").setSingleChoiceItems(names, current, (d, i) -> {
            getSharedPreferences("browse", MODE_PRIVATE).edit().putString("language", LANGUAGE_CODES[i]).apply(); d.dismiss();
            if (screen == Screen.SETTINGS) showSettings("language"); else { streams.clear(); updateHeader(); load(false); }
        }).setNegativeButton("Cancel", null).show();
    }

    private void showSettings(String focus) {
        body.removeAllViews();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content = column(); content.setPadding(dp(8), dp(16), dp(8), dp(18));
        scroll.addView(content); body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        TextView settingsTitle = TvUi.text(this, "Settings", 25, Color.WHITE); settingsTitle.setPadding(0, 0, 0, dp(16)); content.addView(settingsTitle);
        setting(content, "Twitch account", signedIn() ? accountName : "Not signed in", signedIn() ? "Sign out" : "Sign in with QR", "account", v -> {
            if (signedIn()) { history.clear(accountId); account.signOut(); } else signIn();
        });
        if (!signedIn()) setting(content, "Saved login", "Retry session recovery", "Retry", "recover", v -> account.refreshData());
        if (signedIn()) {
            setting(content, "Followed stream order", history.isRelevant(accountId) ? "Relevant" : "Most viewers", "Change", "sort", v -> chooseSort());
            setting(content, "Viewing history", "Stored only on this TV", history.isEnabled(accountId) ? "On" : "Off", "history", v -> {
                history.setEnabled(accountId, !history.isEnabled(accountId)); showSettings("history");
            });
            setting(content, "Clear viewing history", "Reset this account’s relevance order", "Clear", "clear", v -> {
                dialog = new AlertDialog.Builder(this).setTitle("Clear viewing history?").setMessage("Followed channels are unchanged.")
                        .setPositiveButton("Clear", (d, w) -> { history.clear(accountId); showSettings("clear"); }).setNegativeButton("Cancel", null).show();
            });
        }
        setting(content, "Popular stream language", languageLabel(), "Change", "language", v -> chooseLanguage());
        setting(content, "Default quality", PlaybackQuality.preferenceLabel(PlaybackQuality.preference(this)), "Change", "quality", v -> chooseDefaultQuality());
        setting(content, "Ad-free proxy", proxyLabel(new AdFreeProxy(this)), "Change", "ad_proxy", v -> chooseAdFreeProxy());
        setting(content, "App updates", "Source, release channel and installation", "Open", "updates", v -> startActivity(new Intent(this, UpdateActivity.class)));
        if (signedIn()) setting(content, "Video resume positions", "Saved on this TV for this account", "Clear", "video_progress", v -> { new VideoProgress(this).clear(accountId); showSettings("video_progress"); });
        setting(content, "Player test", "HLS sample", "Play", "test", v -> playDemo());
        setting(content, "Account diagnostics", "Profile, follows and API responses", "Open", "diagnostics", v -> startActivity(new Intent(this, LoginActivity.class)));
        setting(content, "SlinkTV " + BuildConfig.VERSION_NAME, "Open-source licenses", "Licenses", "licenses", v -> showLicenses());
        if (focus != null) content.post(() -> { View target = content.findViewWithTag(focus); if (target != null) target.requestFocus(); });
    }
    private void setting(LinearLayout content, String label, String description, String action, String tag, View.OnClickListener listener) {
        LinearLayout row = row(); row.setPadding(dp(16), dp(12), dp(6), dp(12));
        row.setBackground(TvUi.shape(this, 0xFF111B25, 6));
        LinearLayout text = column(); text.addView(TvUi.text(this, label, 19, Color.WHITE)); text.addView(TvUi.text(this, description, 14, TvUi.MUTED));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        Button b = button(action, listener); b.setTag(tag); row.addView(b);
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.bottomMargin = dp(10);
        content.addView(row, spacing);
    }
    private void chooseDefaultQuality() {
        String[] labels = new String[PlaybackQuality.LIMITS.length]; int selected = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = PlaybackQuality.preferenceLabel(PlaybackQuality.LIMITS[i]);
            if (PlaybackQuality.LIMITS[i] == PlaybackQuality.preference(this)) selected = i;
        }
        dialog = new AlertDialog.Builder(this).setTitle("Default stream quality")
                .setSingleChoiceItems(labels, selected, (d, index) -> {
                    PlaybackQuality.savePreference(this, PlaybackQuality.LIMITS[index]);
                    d.dismiss(); showSettings("quality");
                }).setNegativeButton("Cancel", null).show();
    }
    private static String proxyLabel(AdFreeProxy proxy) {
        int count = proxy.servers().size();
        return proxy.enabled() ? "On · " + count + (count == 1 ? " server" : " servers") : "Off";
    }
    private static String proxyMessage(AdFreeProxy proxy) {
        return "Fetches the stream playlist through community servers outside your region. They can see your IP address and the channel you watch. Video still comes from Twitch.\n\nTwitch can still insert an ad. The player then shows a backup stream until the break ends, as Xtra does, sometimes at 360p. If no backup is ad-free, it hides and mutes the ad.\n\nNow: " + proxyLabel(proxy);
    }
    private void chooseAdFreeProxy() {
        AdFreeProxy proxy = new AdFreeProxy(this);
        AlertDialog shown = new AlertDialog.Builder(this).setTitle("Ad-free proxy").setMessage(proxyMessage(proxy))
                .setPositiveButton(proxy.enabled() ? "Turn off" : "Turn on", null).setNeutralButton("Edit servers", (d, w) -> editAdFreeServers())
                .setNegativeButton("Done", null).create();
        dialog = shown;
        shown.setOnDismissListener(d -> { if (!destroyed) showSettings("ad_proxy"); });
        shown.show();
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            proxy.enabled(!proxy.enabled());
            shown.setMessage(proxyMessage(proxy)); ((Button) v).setText(proxy.enabled() ? "Turn off" : "Turn on");
        });
    }
    private void editAdFreeServers() {
        AdFreeProxy proxy = new AdFreeProxy(this);
        EditText input = new EditText(this); input.setText(String.join("\n", proxy.servers())); input.setTextSize(15);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(4); input.setMaxLines(8); input.setGravity(Gravity.TOP | Gravity.START); input.setPadding(dp(20), dp(12), dp(20), dp(12));
        AlertDialog editor = new AlertDialog.Builder(this).setTitle("Ad-free proxy servers").setMessage("One per line, tried in order. $channel is replaced by the channel name.")
                .setView(input).setPositiveButton("Save", null).setNegativeButton("Cancel", null)
                .setNeutralButton("Default", (d, w) -> proxy.restoreDefaults()).create();
        dialog = editor;
        editor.setOnDismissListener(d -> { if (!destroyed) showSettings("ad_proxy"); });
        editor.show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { proxy.servers(input.getText().toString()); editor.dismiss(); } catch (IllegalArgumentException error) { input.setError(error.getMessage()); }
        });
    }
    private void showLicenses() {
        try (java.io.InputStream input = getAssets().open("THIRD_PARTY_NOTICES.txt")) {
            TextView text = TvUi.text(this, new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), 14, Color.WHITE);
            text.setPadding(dp(20), dp(20), dp(20), dp(20)); ScrollView scroll = new ScrollView(this); scroll.setFocusable(true); scroll.addView(text);
            dialog = new AlertDialog.Builder(this).setTitle("Licenses").setView(scroll).setPositiveButton("Close", null).show(); scroll.requestFocus();
        } catch (Exception exception) { showError("Could not open licenses."); }
    }
    private void playDemo() {
        try {
            JSONObject demo = new JSONObject().put("channel", "Player test").put("headers", new JSONObject())
                    .put("variants", new org.json.JSONArray().put(new JSONObject().put("name", "Auto").put("url", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")));
            startActivity(new Intent(this, PlayerActivity.class).putExtra("stream", demo.toString()).putExtra("quality", 0).putExtra("recorded_sample", true));
        } catch (Exception exception) { showError("Could not open the player test."); }
    }
    @Override protected void onResume() {
        super.onResume();
        UpdatePrompt.onHome(this);
        if (screen == Screen.VIDEOS) { videos.resume(); return; }
        if (grid != null && screen != Screen.SETTINGS && !streams.isEmpty() && !focusedId.isEmpty()) {
            sortStreams(); cards.submit(streams); focusCard(focusedId); focusedId = "";
        }
    }
    @Override protected void onStop() { if (resolving) cancelPlayback(); if (videos != null) videos.hide(); super.onStop(); }
    @Override protected void onDestroy() {
        destroyed = true; generation++; playGeneration++;
        if (videos != null) videos.destroy();
        if (dialog != null) dialog.dismiss(); directory.shutdownNow(); resolver.shutdownNow(); super.onDestroy();
    }
}
