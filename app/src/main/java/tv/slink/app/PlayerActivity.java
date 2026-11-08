package tv.slink.app;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlayerActivity extends ComponentActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Integer> wakeKeys = new HashSet<>();
    private ExoPlayer player;
    private PlayerView playerView;
    private FrameLayout root;
    private PlayerChrome controls;
    private VodTimeBar timeline;
    private TextView timeLabel, seekTooltip;
    private FrameLayout seekTrack;
    private long shownPosition;
    private AlertDialog timeDialog;
    private boolean vod, scrubbing, scrubWasPlaying, vodPlayWhenReady = true;
    private long resumePosition;
    private VideoProgress videoProgress;
    private final java.util.concurrent.ExecutorService videoResolver = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int resolveGeneration;
    private TextView status;
    private TextView metadata;
    private PlayerChrome.Action pause;
    private PlayerChrome.Action retry;
    private PlayerChrome.Action qualityButton;
    private PlayerChrome.Action chatToggle, chatOptions;
    private ChatPanel chat;
    private boolean chatHoldHandled;
    private JSONObject stream;
    private JSONArray variants;
    private int quality;
    private boolean failed;
    private boolean resumed;
    private boolean receiverRegistered;
    private WatchHistory.Tracker watchTracker;
    private AlertDialog qualityDialog;
    private final Runnable hideControls = () -> {
        if (player != null && player.isPlaying() && !failed && !scrubbing && qualityDialog == null && timeDialog == null && (chat == null || !chat.optionsOpen())) {
            controls.setVisibility(View.GONE);
            if (chat != null) chat.setControlsInset(0);
            root.requestFocus();
        }
    };
    private static final long AD_CHECK_MS = 250;
    private TextView adNotice;
    private AdBypass adBypass;
    private final Runnable adCheck = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            boolean backup = adBypass.update();
            boolean ad = !backup && StitchedAds.under(player, AD_CHECK_MS);
            adNotice.setVisibility(ad ? View.VISIBLE : View.GONE);
            player.setVolume(ad || backup ? 0f : 1f);
            handler.postDelayed(this, AD_CHECK_MS);
        }
    };
    private final Runnable checkpoint = new Runnable() {
        @Override public void run() {
            updateViewing(); saveVideoProgress();
            if (resumed) handler.postDelayed(this, 15_000);
        }
    };
    private final BroadcastReceiver screenState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if (watchTracker != null) watchTracker.stop();
                if (chat != null) chat.setActive(false);
            } else { updateViewing(); if (chat != null) chat.setActive(resumed); }
        }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        try {
            stream = new JSONObject(getIntent().getStringExtra("stream"));
            variants = PlaybackQuality.landscapeVariants(stream.getJSONArray("variants"));
            quality = savedState == null ? PlaybackQuality.choose(variants, PlaybackQuality.preference(this)) : savedState.getInt("quality");
            variants.getJSONObject(quality).getString("url");
        } catch (Exception exception) {
            finish();
            return;
        }
        vod = "vod".equals(stream.optString("kind")) || getIntent().getBooleanExtra("recorded_sample", false);
        videoProgress = new VideoProgress(this);
        resumePosition = savedState != null ? savedState.getLong("video_position") : videoProgress.position(extra("account_id"), extra("vod_id"));
        if (savedState != null) vodPlayWhenReady = savedState.getBoolean("video_playing", true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                return handlePlayerKey(event) || super.dispatchKeyEvent(event);
            }
        };
        root.setBackgroundColor(Color.BLACK);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        if (!vod && new AdFreeProxy(this).enabled()) {
            adNotice = TvUi.text(this, "Ad hidden. The stream continues after the break.", 20, TvUi.MUTED);
            adNotice.setGravity(Gravity.CENTER); adNotice.setBackgroundColor(Color.BLACK); adNotice.setVisibility(View.GONE);
            root.addView(adNotice, new FrameLayout.LayoutParams(-1, -1));
        }
        if (!vod)
            chat = new ChatPanel(this, root, playerView, extra("broadcaster_id"), stream.optString("channel"));
        String displayName = extra("display_name");
        controls = new PlayerChrome(this, displayName.isEmpty() ? stream.optString("channel") : displayName,
                extra("stream_title"), vod ? "VIDEO" : "● LIVE");
        metadata = controls.metadata; status = controls.status;
        if (vod) controls.action(PlayerChrome.Icon.BACKWARD, "Back 10 seconds", false, view -> jump(-10_000));
        pause = controls.action(PlayerChrome.Icon.PAUSE, "Pause", true, view -> togglePlayback());
        if (vod) controls.action(PlayerChrome.Icon.FORWARD, "Forward 10 seconds", false, view -> jump(10_000));
        else controls.action(PlayerChrome.Icon.LIVE, "Catch up to live", false, view -> {
            if (player != null) { player.seekToDefaultPosition(); player.play(); } showControls();
        });
        if (vod) {
            controls.action(PlayerChrome.Icon.SEEK, "Seek", false, view -> { showControls(); updateTimeline(); timeline.requestFocus(); });
            controls.action(PlayerChrome.Icon.CLOCK, "Jump to time", false, view -> jumpToTime());
        }
        controls.separateActions();
        if (vod) controls.action(PlayerChrome.Icon.START, "Start over", false, view -> {
            if (player != null) { player.seekTo(0); player.play(); } showControls();
        });
        qualityButton = controls.action(PlayerChrome.Icon.QUALITY, "Quality", false, view -> chooseQuality());
        if (chat != null) {
            chatToggle = controls.action(PlayerChrome.Icon.CHAT, "Hide chat", false, view -> chat.toggle());
            chatOptions = controls.action(PlayerChrome.Icon.CHAT_OPTIONS, "Chat options", false, view -> showChatOptions());
        }
        retry = controls.action(PlayerChrome.Icon.RETRY, "Retry", false, view -> retryPlayback());
        controls.action(PlayerChrome.Icon.EXIT, vod ? "Videos" : "Channels", false, view -> finish());
        if (vod) createTimeline();
        FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(controls, panel);
        if (chat != null) {
            chat.onPresentationChanged(this::updateChatControls);
            controls.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> chat.setControlsInset(controls.getVisibility() == View.VISIBLE ? b - t : 0));
            updateChatControls();
        }
        setContentView(root);
        WindowInsetsController insets = getWindow().getInsetsController();
        insets.hide(WindowInsets.Type.systemBars());
        insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (timeDialog != null) timeDialog.dismiss();
                else if (qualityDialog != null) qualityDialog.dismiss();
                else if (chat != null && chat.closeOptions()) { }
                else if (scrubbing) timeline.cancelScrub();
                else finish();
            }
        });
        refreshMetadata();
        pause.requestFocus();
    }

    private int dp(int value) { return TvUi.dp(this, value); }

    private String extra(String name) {
        String value = getIntent().getStringExtra(name);
        return value == null ? "" : value;
    }

    private TextView line(String value, int size, int color) {
        TextView text = TvUi.text(this, value, size, color);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
        return text;
    }

    private void createTimeline() {
        LinearLayout track = new LinearLayout(this); track.setOrientation(LinearLayout.VERTICAL);
        timeLabel = line("0:00 / —", 12, TvUi.MUTED); track.addView(timeLabel, new LinearLayout.LayoutParams(-1, dp(18)));
        timeline = new VodTimeBar(this); timeline.setId(View.generateViewId());
        seekTrack = new FrameLayout(this);
        seekTrack.addView(timeline, new FrameLayout.LayoutParams(-1, dp(28), Gravity.BOTTOM));
        seekTooltip = line("0:00", 12, TvUi.BACKGROUND); seekTooltip.setShadowLayer(0,0,0,0);
        seekTooltip.setGravity(Gravity.CENTER); seekTooltip.setBackground(TvUi.shape(this,TvUi.ACCENT,6));
        seekTooltip.setVisibility(View.INVISIBLE);
        seekTrack.addView(seekTooltip, new FrameLayout.LayoutParams(dp(100), dp(24), Gravity.TOP | Gravity.LEFT));
        seekTrack.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> positionTooltip());
        track.addView(seekTrack, new LinearLayout.LayoutParams(-1, dp(56))); controls.timeline(track);
        timeline.listen(new androidx.media3.ui.TimeBar.OnScrubListener() {
            @Override public void onScrubStart(androidx.media3.ui.TimeBar bar, long position) {
                if (player == null) return;
                scrubbing = true; scrubWasPlaying = player.getPlayWhenReady(); player.pause();
                handler.removeCallbacks(hideControls); showTime(position);
                controls.caption.setText("Seek · OK to confirm · Back to cancel");
            }
            @Override public void onScrubMove(androidx.media3.ui.TimeBar bar, long position) { showTime(position); }
            @Override public void onScrubStop(androidx.media3.ui.TimeBar bar, long position, boolean cancelled) {
                scrubbing = false;
                if (player == null) return;
                if (!cancelled && player.isCurrentMediaItemSeekable() && player.getDuration() > 0) player.seekTo(Math.max(0, Math.min(position, player.getDuration())));
                player.setPlayWhenReady(scrubWasPlaying); scheduleHide(); updateTimeline();
                controls.caption.setText("Seek · Left / Right · Hold to move faster");
            }
        });
        timeline.setOnFocusChangeListener((view, focused) -> {
            seekTooltip.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            if (focused && !scrubbing) { controls.caption.setText("Seek · Left / Right · Hold to move faster"); updateTimeline(); }
        });
        for (int i = 0; i < controls.actions.getChildCount(); i++) {
            View action = controls.actions.getChildAt(i);
            if (action.isFocusable()) action.setNextFocusUpId(timeline.getId());
        }
        timeline.setNextFocusDownId(pause.getId());
    }
    private final Runnable progressTick = new Runnable() {
        @Override public void run() { updateTimeline(); if (resumed && vod) handler.postDelayed(this, 500); }
    };
    private void updateTimeline() {
        if (timeline == null || player == null) return;
        long duration = player.getDuration();
        timeline.setDuration(duration); timeline.setEnabled(duration > 0 && player.isCurrentMediaItemSeekable());
        timeline.setBufferedPosition(player.getBufferedPosition());
        if (!scrubbing) { timeline.setPosition(player.getCurrentPosition()); showTime(player.getCurrentPosition()); }
    }
    private void showTime(long position) {
        shownPosition = position;
        timeLabel.setText(VideoItem.clock(position) + " / " + (player != null && player.getDuration() > 0 ? VideoItem.clock(player.getDuration()) : "—"));
        seekTooltip.setText(VideoItem.clock(position)); positionTooltip();
    }
    private void positionTooltip() {
        long duration = player == null ? 0 : player.getDuration();
        if (seekTooltip == null || duration <= 0) return;
        float center = dp(8) + (seekTrack.getWidth() - dp(16)) * Math.max(0, Math.min(1f, (float)shownPosition / duration));
        seekTooltip.setTranslationX(Math.max(0, Math.min(seekTrack.getWidth() - seekTooltip.getWidth(), center - seekTooltip.getWidth()/2f)));
    }
    private void jumpToTime() {
        if (player == null || !player.isCurrentMediaItemSeekable() || player.getDuration() <= 0) { status.setText("Video duration unavailable."); return; }
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true); input.setHint("HH:MM:SS"); input.setText(VideoItem.clock(player.getCurrentPosition())); input.selectAll();
        input.setInputType(android.text.InputType.TYPE_CLASS_DATETIME | android.text.InputType.TYPE_DATETIME_VARIATION_TIME);
        input.setPadding(dp(24),dp(12),dp(24),dp(12));
        timeDialog = new AlertDialog.Builder(this).setTitle("Jump to time").setView(input).setPositiveButton("Jump",null).setNegativeButton("Cancel",null).create();
        timeDialog.setOnDismissListener(d -> { timeDialog = null; showControls(); });
        timeDialog.show(); handler.removeCallbacks(hideControls);
        timeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { long target = VideoTime.parse(input.getText().toString(),player.getDuration()); player.seekTo(target); timeDialog.dismiss(); updateTimeline(); }
            catch (IllegalArgumentException error) { input.setError(error.getMessage()); }
        });
    }
    private void jump(long delta) {
        if (player != null && player.isCurrentMediaItemSeekable()) player.seekTo(Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + delta)));
        showControls(); updateTimeline();
    }
    private void saveVideoProgress() {
        if (!vod || player == null) return;
        resumePosition = player.getCurrentPosition();
        vodPlayWhenReady = scrubbing ? scrubWasPlaying : player.getPlayWhenReady();
        videoProgress.save(extra("account_id"), extra("vod_id"), resumePosition, player.getDuration(), !player.isCurrentMediaItemDynamic() && !player.isCurrentMediaItemLive());
    }

    private void updateChatControls() {
        chatToggle.update(PlayerChrome.Icon.CHAT, chat.mode() == ChatPanel.Mode.HIDDEN ? "Show chat" : "Hide chat");
        chatToggle.setSelected(chat.mode() != ChatPanel.Mode.HIDDEN);
        FrameLayout.LayoutParams size = (FrameLayout.LayoutParams) controls.getLayoutParams();
        if (size.getMarginEnd() != chat.videoInset() || size.getMarginStart() != chat.videoInsetStart()) {
            size.setMarginStart(chat.videoInsetStart()); size.setMarginEnd(chat.videoInset()); controls.setLayoutParams(size);
        }
    }

    private void showChatOptions() {
        if (chat == null) return;
        showControls(); handler.removeCallbacks(hideControls);
        chat.showOptions(() -> { showControls(); chatOptions.requestFocus(); });
    }

    private void retryPlayback() {
        if (!vod || extra("vod_id").isEmpty()) { prepare(); return; }
        int request = ++resolveGeneration;
        String name = variants.optJSONObject(quality).optString("name");
        retry.setEnabled(false); qualityButton.setEnabled(false); status.setText("Refreshing video…");
        videoResolver.execute(() -> {
            String data;
            try { data = StreamEngine.call(this, "resolver", "resolve_video", extra("vod_id")); }
            catch (Exception error) { data = "{\"error\":\"Video engine unavailable. Retry.\"}"; }
            String result = data;
            runOnUiThread(() -> {
                if (!resumed || request != resolveGeneration) return;
                retry.setEnabled(true); qualityButton.setEnabled(true);
                try {
                    JSONObject response = new JSONObject(result);
                    if (response.has("error")) { status.setText(response.getString("error")); return; }
                    JSONArray refreshed = PlaybackQuality.landscapeVariants(response.getJSONArray("variants"));
                    int selected = PlaybackQuality.choose(refreshed, PlaybackQuality.preference(this));
                    for (int i=0;i<refreshed.length();i++) if (name.equals(refreshed.getJSONObject(i).optString("name"))) selected=i;
                    refreshed.getJSONObject(selected).getString("url");
                    variants = refreshed; quality = selected; prepare();
                } catch (Exception error) { status.setText("Could not refresh video. Retry."); }
            });
        });
    }

    @Override protected void onStart() {
        super.onStart();
        if (isFinishing() || stream == null) return;
        retry.setEnabled(true); qualityButton.setEnabled(true);
        if (!vod) watchTracker = new WatchHistory(this).tracker(extra("account_id"), extra("broadcaster_id"));
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenState, filter, Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        Map<String, String> headers = new HashMap<>();
        JSONObject jsonHeaders = stream.optJSONObject("headers");
        if (jsonHeaders != null) {
            Iterator<String> keys = jsonHeaders.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                headers.put(key, jsonHeaders.optString(key));
            }
        }
        String userAgent = "SlinkTV/" + BuildConfig.VERSION_NAME + " (Android TV)";
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(15000).setReadTimeoutMs(15000);
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http)).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                updateViewing();
                if (failed) return;
                if (state == Player.STATE_BUFFERING) status.setText("Buffering…");
                if (state == Player.STATE_READY) status.setText(player.getPlayWhenReady() ? "" : "Paused");
                if (state == Player.STATE_ENDED) {
                    status.setText(vod ? "Video ended" : "Stream ended. Retry or return to channels.");
                    saveVideoProgress();
                    showControls();
                }
            }

            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                pause.update(ready ? PlayerChrome.Icon.PAUSE : PlayerChrome.Icon.PLAY, ready ? "Pause" : "Play");
                if (!failed) {
                    status.setText(!ready ? "Paused" : player.getPlaybackState() == Player.STATE_BUFFERING ? "Buffering…" : "");
                    if (!ready) showControls();
                }
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                updateViewing();
                if (playing) { status.setText(""); scheduleHide(); }
            }

            @Override public void onPlayerError(PlaybackException error) {
                failed = true;
                updateViewing();
                status.setText("Playback failed. Retry or choose a lower quality.");
                showControls();
                retry.requestFocus();
            }
        });
        prepare();
        if (adNotice != null) {
            adBypass = new AdBypass(this, playerView, player, stream.optString("channel"), userAgent, headers, http, videoResolver);
            handler.post(adCheck);
        }
    }

    private void refreshMetadata() {
        String game = extra("game_name");
        metadata.setText((game.isEmpty() ? "" : game + "  ·  ") + PlaybackQuality.label(variants, quality));
    }

    private void prepare() {
        if (player == null) return;
        failed = false;
        status.setText("Loading…");
        refreshMetadata();
        MediaItem item = new MediaItem.Builder().setUri(variants.optJSONObject(quality).optString("url"))
                .setMimeType(MimeTypes.APPLICATION_M3U8).build();
        boolean playing = !vod || (player.getMediaItemCount() == 0 ? vodPlayWhenReady : player.getPlayWhenReady());
        long position = vod && player.getMediaItemCount() > 0 ? player.getCurrentPosition() : resumePosition;
        player.setMediaItem(item, vod ? position : C.TIME_UNSET);
        player.prepare();
        player.setPlayWhenReady(playing);
        showControls();
    }

    private void chooseQuality() {
        String[] names = new String[variants.length()];
        for (int i = 0; i < names.length; i++) names[i] = PlaybackQuality.label(variants, i);
        qualityDialog = new AlertDialog.Builder(this).setTitle("Stream quality")
                .setSingleChoiceItems(names, quality, (dialog, index) -> {
                    quality = index;
                    dialog.dismiss();
                    prepare();
                }).setNegativeButton("Back", null).create();
        qualityDialog.setOnDismissListener(dialog -> {
            qualityDialog = null;
            showControls();
            qualityButton.requestFocus();
        });
        qualityDialog.show();
        handler.removeCallbacks(hideControls);
    }

    private void togglePlayback() {
        if (player == null) return;
        if (player.getPlayWhenReady()) player.pause(); else player.play();
        showControls();
    }

    private void showControls() {
        boolean hidden = controls.getVisibility() != View.VISIBLE;
        controls.setVisibility(View.VISIBLE);
        if (chat != null) chat.setControlsInset(controls.getHeight());
        if (hidden) pause.requestFocus();
        scheduleHide();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideControls);
        if (resumed) handler.postDelayed(hideControls, 5000);
    }

    private boolean handlePlayerKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (chat != null && (key == KeyEvent.KEYCODE_PROG_YELLOW || key == KeyEvent.KEYCODE_C || key == KeyEvent.KEYCODE_MENU)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (key == KeyEvent.KEYCODE_MENU) showChatOptions(); else chat.toggle();
            }
            return true;
        }
        if (wakeKeys.contains(key)) {
            if (chat != null && key == KeyEvent.KEYCODE_DPAD_CENTER && !chatHoldHandled && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() > 0 && event.getEventTime() - event.getDownTime() >= 600) {
                chat.toggle(); chatHoldHandled = true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) { wakeKeys.remove(key); chatHoldHandled = false; }
            return true;
        }
        if (player == null) return false;
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (scrubbing) timeline.cancelScrub();
                handler.removeCallbacks(hideControls); controls.setVisibility(View.GONE);
                if (chat != null) chat.setControlsInset(0);
                root.requestFocus(); wakeKeys.add(key);
            }
            return true;
        }
        if (vod && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && (key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
            jump(key == KeyEvent.KEYCODE_MEDIA_REWIND ? -10_000 : 10_000); return true;
        }
        boolean mediaKey = key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY
                || key == KeyEvent.KEYCODE_MEDIA_PAUSE;
        if (mediaKey) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY) player.play();
                else if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) player.pause();
                else togglePlayback();
                showControls();
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_CENTER
                || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_SPACE
                || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (controls.getVisibility() != View.VISIBLE) {
                if (vod && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    showControls(); updateTimeline(); timeline.requestFocus(); return timeline.dispatchKeyEvent(event);
                }
                wakeKeys.add(key);
                showControls();
                return true;
            }
            scheduleHide();
        }
        return false;
    }

    private void updateViewing() {
        if (watchTracker == null) return;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        boolean visible = resumed && getWindow().getDecorView().getWindowVisibility() == View.VISIBLE;
        watchTracker.update(player != null && player.isPlaying() && !failed, visible, power.isInteractive());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        updateViewing();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (chat != null) chat.setActive(true);
        updateViewing();
        handler.postDelayed(checkpoint, 15_000);
        if (controls != null) scheduleHide();
        if (vod) handler.post(progressTick);
    }

    @Override protected void onPause() {
        resumed = false;
        if (scrubbing) timeline.cancelScrub();
        saveVideoProgress(); handler.removeCallbacks(progressTick);
        if (chat != null) chat.setActive(false);
        if (watchTracker != null) watchTracker.stop();
        handler.removeCallbacks(checkpoint);
        handler.removeCallbacks(hideControls);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        saveVideoProgress();
        state.putLong("video_position", resumePosition);
        state.putBoolean("video_playing", vodPlayWhenReady);
        state.putInt("quality", quality);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStop() {
        resolveGeneration++;
        saveVideoProgress();
        if (chat != null) { chat.closeOptions(); chat.setActive(false); }
        if (watchTracker != null) watchTracker.stop();
        if (qualityDialog != null) qualityDialog.dismiss();
        handler.removeCallbacksAndMessages(null);
        wakeKeys.clear();
        if (receiverRegistered) { unregisterReceiver(screenState); receiverRegistered = false; }
        if (adBypass != null) { adBypass.stop(); adBypass = null; }
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        super.onStop();
    }

    @Override protected void onDestroy() { resolveGeneration++; videoResolver.shutdownNow(); if (chat != null) chat.destroy(); super.onDestroy(); }
}
