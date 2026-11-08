package tv.slink.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;
import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

final class ChatEmoteSpan extends ReplacementSpan {
    final List<ChatEmote> emotes;
    final List<Drawable> images = new ArrayList<>();
    private final int height, maxWidth;
    ChatEmoteSpan(List<ChatEmote> emotes, int height, int maxWidth) { this.emotes = emotes; this.height = height; this.maxWidth = maxWidth; }
    private boolean loaded() { return !images.isEmpty() && images.get(0) != null; }
    private int width(Drawable drawable) {
        return Math.max(1, Math.min(maxWidth, Math.round(height * (float) Math.max(1, drawable.getIntrinsicWidth()) / Math.max(1, drawable.getIntrinsicHeight()))));
    }
    @Override public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        if (!loaded()) return Math.min(maxWidth, (int) Math.ceil(paint.measureText(emotes.get(0).name())));
        if (fm != null) { fm.ascent = Math.min(fm.ascent, -height + fm.descent); fm.top = Math.min(fm.top, fm.ascent); }
        return width(images.get(0)) + 2;
    }
    @Override public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
        if (!loaded()) {
            canvas.save(); canvas.clipRect(x, top, x + maxWidth, bottom);
            canvas.drawText(emotes.get(0).name(), x, y, paint); canvas.restore(); return;
        }
        int baseWidth = width(images.get(0));
        for (Drawable image : images) if (image != null) {
            int w = width(image);
            int h = Math.min(height, Math.max(1, Math.round(w * (float) Math.max(1, image.getIntrinsicHeight()) / Math.max(1, image.getIntrinsicWidth()))));
            canvas.save();
            canvas.translate(x + (baseWidth - w) / 2f, y + paint.getFontMetricsInt().descent - h);
            image.setBounds(0, 0, w, h); image.draw(canvas); canvas.restore();
        }
    }
}
