package tv.slink.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class TvUi {
    static final int BACKGROUND = Color.rgb(10, 15, 21);
    static final int ACCENT = Color.rgb(213, 255, 115);
    static final int MUTED = Color.rgb(171, 186, 204);

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    static Button button(Context context, String label, View.OnClickListener action) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(17);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setLetterSpacing(0);
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setBackgroundTintList(null);
        button.setId(View.generateViewId());
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
        space.setMarginEnd(dp(context, 10));
        button.setLayoutParams(space);
        styleButton(button, 6, Color.rgb(27, 37, 49));
        button.setOnClickListener(action);
        return button;
    }

    static void styleButton(Button button, int radius, int surface) {
        Context context = button.getContext();
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, shape(context, 0xFF151D27, radius));
        background.addState(new int[]{android.R.attr.state_pressed}, shape(context, ACCENT, radius));
        background.addState(new int[]{android.R.attr.state_focused}, shape(context, Color.WHITE, radius));
        background.addState(new int[]{android.R.attr.state_selected}, shape(context, 0xFF2C3827, radius));
        background.addState(new int[]{}, shape(context, surface, radius));
        button.setBackground(background);
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_pressed},
                {android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{MUTED, BACKGROUND, BACKGROUND, ACCENT, Color.WHITE}));
    }

    static GradientDrawable rounded(Context context, int color) {
        return shape(context, color, 10);
    }

    static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        return drawable;
    }

    private TvUi() {}
}
