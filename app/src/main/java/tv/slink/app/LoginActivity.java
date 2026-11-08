package tv.slink.app;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LoginActivity extends ComponentActivity {
    private AuthViewModel model;
    private LinearLayout content;
    private boolean loginRequested;
    private boolean showLive;
    private AlertDialog rawDialog;
    private TextView pendingMessage;
    private String displayedCode;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(40), dp(22), dp(40), dp(22));
        content.setBackgroundColor(TvUi.BACKGROUND);
        setContentView(content);
        model = ((SlinkApplication) getApplication()).account();
        model.state().observe(this, this::render);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (loginRequested && !(model.state().getValue() instanceof AuthViewModel.Ready)) model.cancelLogin();
                finish();
            }
        });
    }

    private int dp(int value) { return TvUi.dp(this, value); }
    private TextView text(String value, int size) { return TvUi.text(this, value, size, Color.WHITE); }

    private void render(AuthViewModel.State state) {
        if (getIntent().getBooleanExtra("auto_login", false)) {
            if (state instanceof AuthViewModel.Ready ready) {
                if (!getIntent().getBooleanExtra("enable_chat", false)) { finish(); return; }
                if (!loginRequested) { loginRequested = true; model.signIn(BuildConfig.TWITCH_CLIENT_ID); return; }
                if (TwitchApi.canReadChat(ready.snapshot().validation())) { finish(); return; }
            }
            if (state instanceof AuthViewModel.SignedOut && !loginRequested) {
                loginRequested = true;
                model.signIn(BuildConfig.TWITCH_CLIENT_ID);
            }
        }
        if (state instanceof AuthViewModel.Awaiting awaiting && awaiting.code().userCode().equals(displayedCode)) {
            pendingMessage.setText(pendingText(awaiting));
            return;
        }
        if (rawDialog != null && !(state instanceof AuthViewModel.Ready)) rawDialog.dismiss();
        content.removeAllViews();
        displayedCode = null;
        TextView title = text(getIntent().getBooleanExtra("enable_chat", false) ? "Enable chat" : "Twitch account", 25);
        title.setPadding(0, 0, 0, dp(10));
        content.addView(title);
        if (state instanceof AuthViewModel.SignedOut signedOut) renderSignedOut(signedOut.message());
        else if (state instanceof AuthViewModel.Working working) content.addView(text(working.message(), 19));
        else if (state instanceof AuthViewModel.Awaiting awaiting) renderCode(awaiting);
        else if (state instanceof AuthViewModel.Ready ready) {
            if (getIntent().getBooleanExtra("enable_chat", false)) renderSignedOut(ready.message());
            else renderData(ready);
        }
    }

    private void renderSignedOut(String message) {
        content.addView(text(message, 18));
        LinearLayout buttons = row();
        Button login = addButton(buttons, "Sign in with QR", view -> {
            loginRequested = true;
            model.signIn(BuildConfig.TWITCH_CLIENT_ID);
        });
        addButton(buttons, "Retry saved login", view -> model.refreshData());
        addButton(buttons, "Sign out / clear", view -> model.signOut());
        content.addView(buttons);
        content.addView(text("Permissions: read followed channels and chat. Approval happens on Twitch on your phone.", 16));
        login.requestFocus();
    }

    private String pendingText(AuthViewModel.Awaiting awaiting) {
        long seconds = Math.max(0, (awaiting.code().expiresAt() - System.currentTimeMillis()) / 1000);
        return awaiting.message() + "\nCode expires in " + seconds / 60 + "m " + seconds % 60 + "s.";
    }

    private void renderCode(AuthViewModel.Awaiting awaiting) {
        displayedCode = awaiting.code().userCode();
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView qr = new ImageView(this);
        qr.setContentDescription("Twitch sign-in QR code. Alternatively visit twitch.tv/activate and enter the displayed code.");
        qr.setImageBitmap(qrBitmap(awaiting.code().verificationUri()));
        row.addView(qr, new LinearLayout.LayoutParams(dp(220), dp(220)));
        LinearLayout instructions = new LinearLayout(this);
        instructions.setOrientation(LinearLayout.VERTICAL);
        instructions.setPadding(dp(24), 0, 0, 0);
        instructions.addView(text("Scan this QR code with your phone", 21));
        instructions.addView(text("Or open twitch.tv/activate and enter:", 17));
        instructions.addView(text(awaiting.code().userCode(), 32));
        pendingMessage = text(pendingText(awaiting), 17);
        instructions.addView(pendingMessage);
        row.addView(instructions, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(row);
        Button cancel = TvUi.button(this, "Cancel login", view -> { model.cancelLogin(); if (getIntent().getBooleanExtra("auto_login", false)) finish(); });
        content.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
        cancel.requestFocus();
    }

    static Bitmap qrBitmap(String uri) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(uri, BarcodeFormat.QR_CODE, 660, 660,
                    Map.of(EncodeHintType.MARGIN, 4));
            int[] pixels = new int[660 * 660];
            for (int y = 0; y < 660; y++) for (int x = 0; x < 660; x++) pixels[y * 660 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(pixels, 660, 660, Bitmap.Config.ARGB_8888);
        } catch (com.google.zxing.WriterException exception) { throw new IllegalArgumentException("Cannot create QR code", exception); }
    }

    private void renderData(AuthViewModel.Ready ready) {
        content.addView(text(ready.message(), 17));
        LinearLayout actions = row();
        addButton(actions, "All followed", view -> { showLive = false; render(ready); });
        addButton(actions, "Live followed", view -> { showLive = true; render(ready); });
        Button raw = addButton(actions, "Raw JSON", view -> chooseRaw(ready.snapshot()));
        addButton(actions, "Refresh", view -> model.refreshData());
        addButton(actions, "Sign out", view -> model.signOut());
        content.addView(actions);
        try {
            List<String> lines = channelLines(ready.snapshot(), showLive);
            content.addView(text((showLive ? "Live followed streams" : "All followed channels") + " — " + lines.size(), 19));
            if (lines.isEmpty()) content.addView(text(showLive ? "None of your followed channels are live right now." : "This account does not follow any channels.", 17));
            else {
                ListView list = new ListView(this);
                list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, lines));
                content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            }
        } catch (Exception exception) { content.addView(text("Could not display the channel list. Inspect Raw JSON or refresh.", 17)); }
        raw.requestFocus();
    }

    static List<String> channelLines(TwitchApi.Snapshot data, boolean onlyLive) throws Exception {
        JSONArray live = data.live();
        Map<String, JSONObject> byId = new HashMap<>();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < live.length(); i++) {
            JSONObject stream = live.getJSONObject(i);
            byId.put(stream.getString("user_id"), stream);
            if (onlyLive) lines.add("LIVE  " + stream.optString("user_name") + "  |  " + stream.optString("game_name")
                    + "  |  " + stream.optInt("viewer_count") + " viewers\n" + stream.optString("title"));
        }
        if (!onlyLive) {
            JSONArray follows = data.followed();
            for (int i = 0; i < follows.length(); i++) {
                JSONObject follow = follows.getJSONObject(i);
                boolean isLive = byId.containsKey(follow.getString("broadcaster_id"));
                lines.add((isLive ? "LIVE  " : "Offline  ") + follow.optString("broadcaster_name") + "  (@" + follow.optString("broadcaster_login")
                        + ")\nFollowed: " + follow.optString("followed_at") + "  |  ID: " + follow.optString("broadcaster_id"));
            }
        }
        return lines;
    }

    private void chooseRaw(TwitchApi.Snapshot snapshot) {
        String[] options = {"Token validation / granted scopes", "My profile — GET /users", "All followed pages — GET /channels/followed", "All live pages — GET /streams/followed"};
        rawDialog = new AlertDialog.Builder(this).setTitle("Raw Twitch API responses (credentials excluded)")
                .setItems(options, (dialog, index) -> {
                    try {
                        String raw = switch (index) {
                            case 0 -> snapshot.validation().toString(2);
                            case 1 -> snapshot.profile().toString(2);
                            case 2 -> snapshot.followedPages().toString(2);
                            default -> snapshot.livePages().toString(2);
                        };
                        showRaw(options[index], raw);
                    } catch (Exception exception) { showRaw("Error", "Could not format the response."); }
                }).setNegativeButton("Close", null).create();
        rawDialog.show();
    }

    private void showRaw(String title, String value) {
        TextView json = text(value, 14);
        json.setTypeface(Typeface.MONOSPACE);
        json.setPadding(dp(16), dp(16), dp(16), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(true);
        scroll.addView(json);
        rawDialog = new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close", null).create();
        rawDialog.show();
        scroll.requestFocus();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(8), 0, dp(8));
        return row;
    }

    private Button addButton(LinearLayout row, String label, View.OnClickListener action) {
        Button button = TvUi.button(this, label, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginEnd(dp(8));
        row.addView(button, params);
        return button;
    }

    @Override protected void onDestroy() { if (rawDialog != null) rawDialog.dismiss(); super.onDestroy(); }
}
