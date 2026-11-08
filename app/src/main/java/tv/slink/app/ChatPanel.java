package tv.slink.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.concurrent.Future;

final class ChatPanel {
    enum Mode { HIDDEN, DOCKED, OVERLAY }
    private final ComponentActivity activity;
    private final AuthViewModel account;
    private final SharedPreferences settings;
    private final FrameLayout root;
    private final PlayerView video;
    private final LinearLayout panel;
    private final TextView status;
    private final Button authorize;
    private final RecyclerView messages;
    private final ChatAdapter adapter;
    private final ChatEmotes emotes;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String login;
    private String channel, reader = "";
    private TwitchApi.Snapshot accountData;
    private TwitchChat connection;
    private Future<?> catalog;
    private Mode mode, lastVisible;
    private int opacity, textSize, generation, controlsInset, corner;
    private boolean compact, halfHeight;
    private boolean active, resolvingChannel, animations, consentRequired;
    private AlertDialog options;
    private Runnable presentationChanged = () -> {};

    ChatPanel(ComponentActivity activity, FrameLayout root, PlayerView video, String channel, String login) {
        this.activity = activity; this.root = root; this.video = video; this.channel = channel; this.login = login;
        account = ((SlinkApplication) activity.getApplication()).account();
        settings = activity.getSharedPreferences("chat", 0);
        mode = readMode("mode", Mode.DOCKED); lastVisible = readMode("last_visible", Mode.DOCKED);
        if (lastVisible == Mode.HIDDEN) lastVisible = Mode.DOCKED;
        opacity = Math.max(0, Math.min(90, settings.getInt("opacity", 65)));
        textSize = Math.max(13, Math.min(18, settings.getInt("text_size", 15)));
        animations = settings.getBoolean("animations", true);
        corner = Math.max(0, Math.min(3, settings.getInt("corner", 1)));
        compact = settings.getBoolean("compact", true); halfHeight = settings.getBoolean("half_height", true);
        emotes = ChatEmotes.get(activity); adapter = new ChatAdapter(emotes);
        panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        panel.setClipToOutline(true);
        status = TvUi.text(activity, "", 12, TvUi.MUTED);
        status.setVisibility(View.GONE); panel.addView(status);
        authorize = TvUi.button(activity, "Enable chat", view -> enableChat());
        authorize.setTextSize(14); authorize.setMinHeight(dp(38)); authorize.setVisibility(View.GONE);
        panel.addView(authorize);
        messages = new RecyclerView(activity);
        LinearLayoutManager layout = new LinearLayoutManager(activity); layout.setStackFromEnd(true);
        messages.setLayoutManager(layout); messages.setAdapter(adapter); messages.setItemAnimator(null);
        messages.setFocusable(false); messages.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        messages.setVerticalScrollBarEnabled(false);
        panel.addView(messages, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(panel, new FrameLayout.LayoutParams(dp(288), -1, Gravity.END));
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol || b - t != ob - ot) layout(); });
        layout();
        account.state().observe(activity, state -> {
            if (state instanceof AuthViewModel.Ready ready) {
                String user = ready.snapshot().validation().optString("user_id");
                if (!reader.equals(user) || accountData != null && TwitchApi.canReadChat(accountData.validation()) != TwitchApi.canReadChat(ready.snapshot().validation())) stopSession();
                reader = user; accountData = ready.snapshot(); startSession();
            } else if (state instanceof AuthViewModel.SignedOut) {
                accountData = null; reader = ""; stopSession(); startSession();
            }
        });
    }

    private int dp(int value) { return TvUi.dp(activity, value); }
    private Mode readMode(String key, Mode fallback) {
        try { return Mode.valueOf(settings.getString(key, fallback.name())); } catch (IllegalArgumentException ignored) { return fallback; }
    }
    Mode mode() { return mode; }
    int videoInset() { return ((FrameLayout.LayoutParams) video.getLayoutParams()).getMarginEnd(); }
    int videoInsetStart() { return ((FrameLayout.LayoutParams) video.getLayoutParams()).getMarginStart(); }
    void setControlsInset(int pixels) { if (controlsInset != pixels) { controlsInset = pixels; layout(); } }
    void onPresentationChanged(Runnable listener) { presentationChanged = listener; }
    void toggle() { setMode(mode == Mode.HIDDEN ? lastVisible : Mode.HIDDEN); }
    void setMode(Mode next) {
        mode = next; if (next != Mode.HIDDEN) lastVisible = next;
        settings.edit().putString("mode", mode.name()).putString("last_visible", lastVisible.name()).apply();
        layout();
        if (mode == Mode.HIDDEN) stopSession(); else startSession();
        adapter.setPresentation(active && mode != Mode.HIDDEN, animations, textSize);
        presentationChanged.run();
    }
    void setActive(boolean active) {
        this.active = active;
        if (active) startSession(); else stopSession();
        adapter.setPresentation(active && mode != Mode.HIDDEN, animations, textSize);
    }
    private void layout() {
        int screen = root.getWidth() > 0 ? root.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        int height = root.getHeight() > 0 ? root.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
        int width = Math.max(dp(210), Math.min(dp(compact ? 280 : 360), Math.round(screen * (compact ? .24f : .30f))));
        boolean left = corner % 2 == 0, bottom = corner >= 2, overlay = mode == Mode.OVERLAY;
        int margin = overlay ? dp(12) : 0;
        int available = Math.max(dp(80), height - 2 * margin - (overlay ? controlsInset : 0));
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(width,
                overlay ? Math.min(available, halfHeight ? height / 2 : available) : -1,
                (left ? Gravity.START : Gravity.END) | (bottom && overlay ? Gravity.BOTTOM : Gravity.TOP));
        bounds.topMargin = margin; bounds.bottomMargin = margin + (overlay ? controlsInset : 0);
        bounds.setMarginStart(left ? margin : 0); bounds.setMarginEnd(left ? 0 : margin);
        panel.setLayoutParams(bounds);
        FrameLayout.LayoutParams videoBounds = (FrameLayout.LayoutParams) video.getLayoutParams();
        int start = mode == Mode.DOCKED && left ? width : 0, end = mode == Mode.DOCKED && !left ? width : 0;
        if (videoBounds.getMarginStart() != start || videoBounds.getMarginEnd() != end) {
            videoBounds.setMarginStart(start); videoBounds.setMarginEnd(end); video.setLayoutParams(videoBounds);
        }
        panel.setVisibility(mode == Mode.HIDDEN ? View.GONE : View.VISIBLE);
        int alpha = mode == Mode.DOCKED ? 255 : Math.round(opacity * 2.55f);
        panel.setBackground(TvUi.shape(activity, Color.argb(alpha, 10, 15, 21), overlay ? 10 : 0));
        presentationChanged.run();
    }
    private void notice(String text) {
        status.setText(text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void startSession() {
        if (!active || mode == Mode.HIDDEN) return;
        adapter.setPresentation(true, animations, textSize);
        if (connection != null || resolvingChannel) return;
        boolean allowed = accountData != null && TwitchApi.canReadChat(accountData.validation());
        consentRequired = !allowed;
        authorize.setVisibility(allowed ? View.GONE : View.VISIBLE);
        if (!allowed) { notice(accountData == null ? "Sign in to read chat." : "Chat permission required."); return; }
        if (!channel.matches("[0-9]{1,30}")) {
            resolvingChannel = true; int request = generation;
            notice("");
            account.requestChat((api, session) -> api.channelId(session, login), (id, error) -> {
                if (request != generation) return;
                resolvingChannel = false;
                if (error != null) { notice("Channel unavailable. Retry chat."); return; }
                channel = id; startSession();
            });
            return;
        }
        String user = reader, broadcaster = channel;
        int request = generation;
        connection = new TwitchChat((sessionId, valid, done) -> account.requestChat((api, session) -> {
            try { api.subscribeChat(session, user, broadcaster, sessionId, valid); return new SubscriptionResult(null, false); }
            catch (TwitchApi.ApiException error) {
                if (error.status == 401) throw error;
                return new SubscriptionResult(error.getMessage(), error.status == 403);
            }
        }, (result, error) -> {
            if (request != generation) return;
            done.complete(error != null ? error : result.error(), result != null && result.consent());
        }), (rows, state, needsConsent) -> {
            if (request != generation || !active || mode == Mode.HIDDEN) return;
            adapter.submit(rows);
            if (!rows.isEmpty()) messages.scrollToPosition(rows.size() - 1);
            notice(needsConsent || state.toLowerCase(java.util.Locale.ROOT).contains("failed") ? state : ""); consentRequired = needsConsent;
            authorize.setVisibility(needsConsent ? View.VISIBLE : View.GONE);
        });
        connection.start(); refreshEmotes.run();
    }
    private record SubscriptionResult(String error, boolean consent) {}
    private final Runnable refreshEmotes = new Runnable() {
        @Override public void run() {
            if (!active || mode == Mode.HIDDEN || connection == null) return;
            int request = generation;
            if (catalog == null || catalog.isDone()) catalog = emotes.catalog(channel, result -> {
                if (request != generation || !active || mode == Mode.HIDDEN) return;
                adapter.setEmotes(result.emotes());
            });
            main.postDelayed(this, 5 * 60_000);
        }
    };
    private void stopSession() {
        generation++; resolvingChannel = false;
        if (connection != null) connection.stop(); connection = null;
        if (catalog != null) catalog.cancel(true); catalog = null;
        main.removeCallbacksAndMessages(null);
        adapter.submit(List.of()); adapter.setPresentation(false, animations, textSize);
    }
    private void enableChat() {
        closeOptions();
        activity.startActivity(new Intent(activity, LoginActivity.class).putExtra("auto_login", true).putExtra("enable_chat", true));
    }

    boolean optionsOpen() { return options != null; }
    boolean closeOptions() { if (options == null) return false; options.dismiss(); return true; }
    private LinearLayout optionRow(LinearLayout body, SettingsIcon.Kind icon, String label) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(12), dp(8)); row.setBackground(TvUi.shape(activity, 0xFF182330, 10));
        TextView title = TvUi.text(activity, label, 15, Color.WHITE);
        title.setCompoundDrawablesRelative(new SettingsIcon(icon, dp(22)), null, null, null); title.setCompoundDrawablePadding(dp(12));
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2); space.bottomMargin = dp(8); body.addView(row, space);
        return row;
    }
    private Button choice(LinearLayout row, String value, View.OnClickListener action) {
        Button button = TvUi.button(activity, value, action); button.setTextSize(14); button.setMinHeight(dp(42));
        button.setPadding(dp(12), dp(4), dp(12), dp(4)); row.addView(button); return button;
    }
    void showOptions(Runnable dismissed) {
        if (options != null) return;
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(16), dp(20), dp(12));
        TextView title = TvUi.text(activity, "Chat", 24, Color.WHITE); title.setPadding(0,0,0,dp(16)); body.addView(title);
        SeekBar slider = new SeekBar(activity); slider.setMax(90); slider.setProgress(opacity); slider.setKeyProgressIncrement(5);
        slider.setContentDescription("Overlay background opacity"); slider.setEnabled(mode == Mode.OVERLAY);
        TextView placement = TvUi.text(activity, "Display", 14, TvUi.MUTED); body.addView(placement);
        final TvPillGroup[] modeGroup = new TvPillGroup[1];
        modeGroup[0] = new TvPillGroup(activity, new String[]{"Hidden", "Docked", "Overlay"}, index -> {
            setMode(Mode.values()[index]); slider.setEnabled(mode == Mode.OVERLAY); modeGroup[0].select(index);
        });
        modeGroup[0].select(mode.ordinal()); body.addView(modeGroup[0]);
        View gap = new View(activity); body.addView(gap, new LinearLayout.LayoutParams(1,dp(14)));
        String[] positions = {"Top left", "Top right", "Bottom left", "Bottom right"};
        choice(optionRow(body, SettingsIcon.Kind.POSITION, "Position"), positions[corner], view -> {
            corner = (corner + 1) % 4; settings.edit().putInt("corner", corner).apply(); ((Button)view).setText(positions[corner]); layout();
        });
        choice(optionRow(body, SettingsIcon.Kind.LAYOUT, "Width"), compact ? "Compact" : "Wide", view -> {
            compact = !compact; settings.edit().putBoolean("compact",compact).apply(); ((Button)view).setText(compact ? "Compact" : "Wide"); layout();
        });
        choice(optionRow(body, SettingsIcon.Kind.SIZE, "Overlay height"), halfHeight ? "Half" : "Full", view -> {
            halfHeight = !halfHeight; settings.edit().putBoolean("half_height",halfHeight).apply(); ((Button)view).setText(halfHeight ? "Half" : "Full"); layout();
        });
        LinearLayout background = optionRow(body, SettingsIcon.Kind.OPACITY, "Background");
        TextView opacityLabel = TvUi.text(activity, opacity + "%", 14, TvUi.MUTED); background.addView(opacityLabel);
        body.addView(slider, new LinearLayout.LayoutParams(-1, dp(32)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int value, boolean user) {
                opacity = value; settings.edit().putInt("opacity", opacity).apply(); opacityLabel.setText(opacity + "%"); layout();
            }
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {}
        });
        int[] sizes = {13, 15, 18}; String[] names = {"Small", "Medium", "Large"};
        choice(optionRow(body, SettingsIcon.Kind.TEXT, "Text"), names[textSize == 13 ? 0 : textSize == 18 ? 2 : 1], view -> {
            int index = textSize == 13 ? 1 : textSize == 15 ? 2 : 0;
            textSize = sizes[index]; settings.edit().putInt("text_size",textSize).apply(); ((Button)view).setText(names[index]);
            adapter.setPresentation(active && mode != Mode.HIDDEN, animations, textSize);
        });
        choice(optionRow(body, SettingsIcon.Kind.ANIMATION, "Animated emotes"), animations ? "On" : "Off", view -> {
            animations = !animations; settings.edit().putBoolean("animations", animations).apply(); ((Button)view).setText(animations ? "On" : "Off");
            adapter.setPresentation(active && mode != Mode.HIDDEN, animations, textSize);
        });
        choice(optionRow(body, SettingsIcon.Kind.RETRY, "Connection"), consentRequired ? "Enable chat" : "Retry", view -> {
            if (consentRequired) enableChat(); else { stopSession(); startSession(); }
        });
        ScrollView scroll = new ScrollView(activity); scroll.addView(body); scroll.setClipToPadding(false);
        options = new AlertDialog.Builder(activity).setView(scroll).setPositiveButton("Done", null).create();
        options.setOnDismissListener(dialog -> { options = null; dismissed.run(); });
        options.show();
        options.getWindow().setBackgroundDrawable(TvUi.shape(activity, TvUi.BACKGROUND, 14));
        options.getWindow().setLayout(dp(440), Math.min(activity.getResources().getDisplayMetrics().heightPixels - dp(32), dp(640)));
        options.getWindow().setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        modeGroup[0].getChildAt(mode.ordinal()).requestFocus();
    }
    void destroy() { closeOptions(); stopSession(); adapter.close(); }
}
