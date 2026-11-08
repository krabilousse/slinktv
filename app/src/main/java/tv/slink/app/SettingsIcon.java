package tv.slink.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Small original line icons for the TV settings rows. */
final class SettingsIcon extends Drawable {
    enum Kind { LAYOUT, POSITION, SIZE, OPACITY, TEXT, ANIMATION, RETRY }
    private final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    SettingsIcon(Kind kind, int pixels) { this.kind = kind; setBounds(0, 0, pixels, pixels); }
    @Override public void draw(Canvas canvas) {
        canvas.save(); canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        paint.setColor(TvUi.ACCENT); paint.setStrokeWidth(1.7f); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        switch (kind) {
            case LAYOUT -> { canvas.drawRoundRect(2,4,22,20,2,2,paint); canvas.drawLine(15,4,15,20,paint); }
            case POSITION -> { canvas.drawRoundRect(2,3,22,21,2,2,paint); paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(13,12,20,19,1,1,paint); }
            case SIZE -> { canvas.drawRect(3,3,21,21,paint); canvas.drawLine(3,12,21,12,paint); }
            case OPACITY -> { canvas.drawCircle(12,12,9,paint); paint.setStyle(Paint.Style.FILL); canvas.drawArc(3,3,21,21,-90,180,true,paint); }
            case TEXT -> { canvas.drawLine(4,5,20,5,paint); canvas.drawLine(12,5,12,20,paint); canvas.drawLine(8,20,16,20,paint); }
            case ANIMATION -> { canvas.drawCircle(12,12,10,paint); android.graphics.Path p=new android.graphics.Path();p.moveTo(9,7);p.lineTo(17,12);p.lineTo(9,17);p.close();canvas.drawPath(p,paint); }
            case RETRY -> { canvas.drawArc(4,4,20,20,-45,300,false,paint);canvas.drawLine(15,3,21,3,paint);canvas.drawLine(21,3,21,9,paint); }
        }
        canvas.restore();
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
