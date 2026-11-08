package tv.slink.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One remote-navigation row, with labels outside the icon buttons. */
final class PlayerChrome extends LinearLayout {
    enum Icon { PLAY, PAUSE, LIVE, QUALITY, CHAT, CHAT_OPTIONS, START, RETRY, EXIT, BACKWARD, FORWARD, SEEK, CLOCK }
    final TextView metadata, status, caption;
    final LinearLayout actions;

    PlayerChrome(Context context, String name, String title, String badge) {
        super(context);
        setOrientation(VERTICAL); setClipChildren(false); setClipToPadding(false);
        setPadding(dp(30), dp(30), dp(30), dp(18));
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00080D14, 0xCC080D14, 0xF2080D14}));
        LinearLayout heading = new LinearLayout(context); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView channel = text(name, 20, Color.WHITE); channel.setTypeface(null, Typeface.BOLD);
        heading.addView(channel, new LinearLayout.LayoutParams(0, dp(28), 1));
        TextView label = text(badge, 11, badge.contains("LIVE") ? 0xFFFF6C7D : TvUi.MUTED);
        label.setPadding(dp(12), 0, 0, 0); heading.addView(label); addView(heading);
        TextView description = text(title, 15, 0xFFD7E0EA);
        description.setVisibility(title.isEmpty() ? GONE : VISIBLE); addView(description, new LayoutParams(-1, dp(24)));
        metadata = text("", 12, TvUi.MUTED); addView(metadata, new LayoutParams(-1, dp(20)));
        status = text("", 13, Color.WHITE); addView(status, new LayoutParams(-1, dp(22)));
        actions = new LinearLayout(context); actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setClipChildren(false); actions.setClipToPadding(false);
        addView(actions, new LayoutParams(-1, dp(60)));
        caption = text("Pause", 12, 0xFFD7E0EA); caption.setGravity(Gravity.CENTER_VERTICAL);
        addView(caption, new LayoutParams(-1, dp(22)));
    }
    private int dp(int size) { return TvUi.dp(getContext(), size); }
    private TextView text(String value, int size, int color) {
        TextView text = TvUi.text(getContext(), value, size, color);
        text.setMaxLines(1); text.setEllipsize(TextUtils.TruncateAt.END); return text;
    }
    Action action(Icon icon, String label, boolean primary, OnClickListener click) {
        Action action = new Action(getContext(), icon, label, primary);
        LayoutParams size = new LayoutParams(dp(primary ? 52 : 44), dp(primary ? 52 : 44));
        size.setMarginEnd(dp(10)); actions.addView(action, size);
        action.setOnFocusChangeListener((view, focused) -> {
            if (focused) caption.setText(action.label);
            action.animate().scaleX(focused ? 1.07f : 1f).scaleY(focused ? 1.07f : 1f).setDuration(120).start();
        });
        action.setOnClickListener(click); action.changed = () -> { if (action.hasFocus()) caption.setText(action.label); };
        return action;
    }
    void separateActions() { actions.addView(new View(getContext()), new LayoutParams(0, 1, 1)); }
    void timeline(View view) { addView(view, indexOfChild(actions), new LayoutParams(-1, dp(74))); }

    static final class Action extends Button {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Icon icon;
        private String label;
        private Runnable changed = () -> {};
        Action(Context context, Icon icon, String label, boolean primary) {
            super(context); this.icon = icon; this.label = label;
            setText(""); setContentDescription(label); setId(View.generateViewId());
            setMinWidth(0); setMinHeight(0); setMinimumWidth(0); setMinimumHeight(0); setPadding(0, 0, 0, 0);
            setBackgroundTintList(null); setStateListAnimator(null); setElevation(0);
            TvUi.styleButton(this, primary ? 26 : 12, primary ? 0xFF35432E : 0x66253443);
        }
        void update(Icon next, String description) { icon = next; label = description; setContentDescription(label); invalidate(); changed.run(); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.save(); float size = TvUi.dp(getContext(), 25);
            canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2); canvas.scale(size / 24, size / 24);
            paint.setColor(!isEnabled() ? TvUi.MUTED : isFocused() || isPressed() ? TvUi.BACKGROUND : isSelected() ? TvUi.ACCENT : Color.WHITE);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            switch (icon) {
                case PLAY -> { paint.setStyle(Paint.Style.FILL); path(canvas, 8,4, 20,12, 8,20, 8,4); }
                case PAUSE -> { paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(6,4,10,20,1,1,paint); canvas.drawRoundRect(14,4,18,20,1,1,paint); }
                case LIVE -> { canvas.drawCircle(12,12,3,paint); canvas.drawArc(4,4,20,20,-55,110,false,paint); canvas.drawArc(4,4,20,20,125,110,false,paint); }
                case QUALITY -> { canvas.drawRoundRect(3,5,21,19,2,2,paint); paint.setStyle(Paint.Style.FILL); paint.setTypeface(Typeface.DEFAULT_BOLD); paint.setTextSize(8); canvas.drawText("HD",6,15,paint); }
                case CHAT, CHAT_OPTIONS -> { path(canvas, 3,4, 21,4, 21,17, 10,17, 5,21, 5,17, 3,17, 3,4); if (icon == Icon.CHAT) { canvas.drawLine(7,9,17,9,paint); canvas.drawLine(7,13,14,13,paint); } else { for (int x : new int[]{7,12,17}) canvas.drawCircle(x,10,0.7f,paint); } }
                case START -> { canvas.drawLine(5,5,5,19,paint); paint.setStyle(Paint.Style.FILL); path(canvas, 19,5, 8,12, 19,19, 19,5); }
                case CLOCK -> { canvas.drawCircle(12,12,9,paint); path(canvas, 12,6, 12,12, 16,14); }
                case SEEK -> { canvas.drawLine(3,12,21,12,paint); canvas.drawCircle(10,12,3,paint); canvas.drawLine(3,8,3,16,paint); canvas.drawLine(21,8,21,16,paint); }
                case RETRY -> { canvas.drawArc(4,4,20,20,-45,300,false,paint); path(canvas, 15,3, 20,3, 20,8); }
                case EXIT -> { path(canvas, 14,4, 5,4, 5,20, 14,20); path(canvas, 13,12, 23,12, 19,8); path(canvas, 23,12, 19,16); }
                case BACKWARD, FORWARD -> {
                    if (icon == Icon.FORWARD) { canvas.translate(24,0); canvas.scale(-1,1); }
                    path(canvas, 12,5, 4,12, 12,19); path(canvas, 20,5, 12,12, 20,19);
                }
            }
            canvas.restore();
        }
        private void path(Canvas canvas, float... points) {
            Path path = new Path(); path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i+1]); canvas.drawPath(path, paint);
        }
    }
}
