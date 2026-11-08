package tv.slink.app;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

final class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Row> {
    private final ChatEmotes assets;
    private final Set<Row> rows = new HashSet<>();
    private final Set<Drawable> animating = new HashSet<>();
    private List<ChatMessage> messages = List.of();
    private Map<String, ChatEmote> emotes = Map.of();
    private boolean visible, animations = true;
    private int textSize = 15;

    ChatAdapter(ChatEmotes assets) { this.assets = assets; }
    void setPresentation(boolean visible, boolean animations, int size) {
        boolean rebind = textSize != size;
        this.visible = visible; this.animations = animations; textSize = size;
        for (Row row : rows) syncAnimations(row);
        if (rebind) notifyDataSetChanged();
    }
    void setEmotes(Map<String, ChatEmote> emotes) {
        if (this.emotes.equals(emotes)) return;
        this.emotes = emotes; notifyDataSetChanged();
    }
    void submit(List<ChatMessage> next) {
        List<ChatMessage> old = messages;
        DiffUtil.DiffResult changes = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int a, int b) { return old.get(a).id().equals(next.get(b).id()); }
            @Override public boolean areContentsTheSame(int a, int b) { return old.get(a).equals(next.get(b)); }
        });
        messages = next; changes.dispatchUpdatesTo(this);
    }
    @Override public int getItemCount() { return messages.size(); }
    @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        TextView text = TvUi.text(parent.getContext(), "", textSize, Color.WHITE);
        text.setPadding(0, TvUi.dp(parent.getContext(), 4), 0, TvUi.dp(parent.getContext(), 4));
        text.setLineSpacing(TvUi.dp(parent.getContext(), 3), 1);
        text.setMaxLines(8); text.setEllipsize(TextUtils.TruncateAt.END);
        text.setShadowLayer(2, 0, 1, Color.BLACK);
        text.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        Row row = new Row(text); rows.add(row); return row;
    }
    @Override public void onBindViewHolder(@NonNull Row row, int position) {
        release(row);
        ChatMessage message = messages.get(position);
        int revision = row.revision;
        row.text.setTextSize(textSize);
        SpannableStringBuilder content = new SpannableStringBuilder();
        if (!message.badge().isEmpty()) {
            content.append(message.badge()).append(' ');
            content.setSpan(new ForegroundColorSpan(0xFFB8D0FF), 0, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int nameStart = content.length();
        content.append(message.name());
        content.setSpan(new StyleSpan(Typeface.BOLD), nameStart, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        content.setSpan(new ForegroundColorSpan(readableColor(message.color())), nameStart, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!message.sourceName().isEmpty()) content.append(" @").append(message.sourceName());
        content.append(": ");
        List<ChatEmote> requested = new ArrayList<>();
        int imageHeight = TvUi.dp(row.text.getContext(), textSize + 7);
        for (ChatText.Run run : ChatText.runs(message, emotes)) {
            if (run instanceof ChatText.Text text) content.append(text.text());
            else if (run instanceof ChatText.Images images) {
                ChatEmoteSpan span = new ChatEmoteSpan(images.layers(), imageHeight, TvUi.dp(row.text.getContext(), 200));
                int start = content.length(); content.append('\uFFFC');
                content.setSpan(span, start, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                row.spans.add(span); requested.addAll(images.layers());
            }
        }
        row.text.setText(content);
        StringBuilder accessible = new StringBuilder(message.name()).append(": ");
        for (ChatMessage.Fragment fragment : message.fragments()) accessible.append(fragment.text());
        row.text.setContentDescription(accessible.toString());
        if (!requested.isEmpty()) row.loading = assets.load(requested, imageHeight, loaded -> {
            if (row.revision != revision) return;
            int i = 0;
            for (ChatEmoteSpan span : row.spans) for (int j = 0; j < span.emotes.size(); j++) {
                Drawable drawable = loaded.get(i++); span.images.add(drawable);
                if (drawable != null) drawable.setCallback(row.callback);
            }
            // Loaded dimensions can differ from a text fallback or Twitch's nominal square size.
            row.text.setText(content); row.text.requestLayout(); row.text.invalidate();
            syncAnimations(row);
        });
    }
    static int readableColor(String raw) {
        int color;
        try { color = raw.matches("#[A-Fa-f0-9]{6}") ? Color.parseColor(raw) : TvUi.ACCENT; }
        catch (IllegalArgumentException ignored) { color = TvUi.ACCENT; }
        while (ColorUtils.calculateContrast(color, TvUi.BACKGROUND) < 5) color = ColorUtils.blendARGB(color, Color.WHITE, .2f);
        return color;
    }
    @Override public void onViewAttachedToWindow(@NonNull Row row) { row.attached = true; syncAnimations(row); }
    @Override public void onViewDetachedFromWindow(@NonNull Row row) { row.attached = false; syncAnimations(row); }
    @Override public void onViewRecycled(@NonNull Row row) { release(row); }
    void close() { for (Row row : rows) release(row); rows.clear(); }
    private void syncAnimations(Row row) {
        for (ChatEmoteSpan span : row.spans) for (Drawable drawable : span.images) {
            if (!(drawable instanceof Animatable animation)) continue;
            boolean allowed = visible && animations && row.attached;
            if (allowed && !animating.contains(drawable) && animating.size() < 12) { animating.add(drawable); animation.start(); }
            else if (!allowed) { animation.stop(); animating.remove(drawable); }
        }
    }
    private void release(Row row) {
        row.revision++;
        if (row.loading != null) row.loading.cancel(true);
        row.loading = null;
        for (ChatEmoteSpan span : row.spans) for (Drawable drawable : span.images) if (drawable != null) {
            if (drawable instanceof Animatable animation) animation.stop();
            animating.remove(drawable); drawable.setCallback(null);
        }
        row.spans.clear();
    }
    static final class Row extends RecyclerView.ViewHolder {
        final TextView text;
        final List<ChatEmoteSpan> spans = new ArrayList<>();
        final Drawable.Callback callback;
        Future<?> loading;
        int revision;
        boolean attached;
        Row(TextView text) {
            super(text); this.text = text;
            callback = new Drawable.Callback() {
                @Override public void invalidateDrawable(@NonNull Drawable who) { if (attached) text.invalidate(); }
                @Override public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
                    text.postDelayed(what, Math.max(0, when - SystemClock.uptimeMillis()));
                }
                @Override public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) { text.removeCallbacks(what); }
            };
        }
    }
}
