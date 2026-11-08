package tv.slink.app;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.function.IntConsumer;

final class TvPillGroup extends LinearLayout {
    TvPillGroup(Context context, String[] labels, IntConsumer select) {
        super(context);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(TvUi.dp(context, 3), TvUi.dp(context, 3), TvUi.dp(context, 3), TvUi.dp(context, 3));
        setBackground(TvUi.shape(context, 0xFF151F2A, 24));
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            Button segment = TvUi.button(context, labels[i], view -> select.accept(index));
            segment.setTextSize(14);
            segment.setMinHeight(TvUi.dp(context, 36));
            segment.setPadding(TvUi.dp(context, 16), 0, TvUi.dp(context, 16), 0);
            TvUi.styleButton(segment, 20, Color.TRANSPARENT);
            LayoutParams size = new LayoutParams(-2, TvUi.dp(context, 36));
            if (i > 0) size.setMarginStart(TvUi.dp(context, 4));
            addView(segment, size);
        }
    }

    void select(int index) {
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setSelected(i == index);
            getChildAt(i).setStateDescription(i == index ? "Selected" : "Not selected");
        }
    }
}
