package tv.slink.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Cards and skeletons share identical bounds; loading never changes the grid geometry. */
final class StreamWallAdapter extends RecyclerView.Adapter<StreamWallAdapter.Holder> {
    private static final int PLACEHOLDER = Color.rgb(32, 42, 54);
    private final List<StreamChannel> items = new ArrayList<>();
    private final Consumer<StreamChannel> select;
    private boolean skeleton = true;
    private int cardWidth;
    private int focusedPosition;
    private String loadingLogin = "";

    StreamWallAdapter(int width, Consumer<StreamChannel> select) {
        this.cardWidth = width;
        this.select = select;
    }

    void showLoading() { skeleton = true; items.clear(); notifyDataSetChanged(); }
    void submit(List<StreamChannel> data) { skeleton = false; items.clear(); items.addAll(data); notifyDataSetChanged(); }
    int indexOf(String id) { for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(id)) return i; return -1; }
    String focusedId() { return focusedPosition >= 0 && focusedPosition < items.size() ? items.get(focusedPosition).id() : ""; }
    void setLoadingChannel(String login) {
        String previous = loadingLogin;
        loadingLogin = login;
        if (skeleton) return;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).login().equals(previous) || items.get(i).login().equals(login)) notifyItemChanged(i, "loading");
        }
    }
    @Override public int getItemCount() { return skeleton ? 12 : items.size(); }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        Context c = parent.getContext();
        int inset = TvUi.dp(c, 6);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(inset, inset, inset, inset);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        StateListDrawable background = new StateListDrawable();
        GradientDrawable focus = TvUi.rounded(c, Color.rgb(18, 25, 33));
        focus.setStroke(TvUi.dp(c, 2), TvUi.ACCENT);
        background.addState(new int[]{android.R.attr.state_focused}, focus);
        background.addState(new int[]{}, TvUi.rounded(c, TvUi.BACKGROUND));
        card.setBackground(background);
        RecyclerView.LayoutParams size = new RecyclerView.LayoutParams(-1, -2);
        size.setMargins(TvUi.dp(c, 3), TvUi.dp(c, 3), TvUi.dp(c, 3), TvUi.dp(c, 3));
        card.setLayoutParams(size);

        FrameLayout frame = new FrameLayout(c);
        frame.setBackground(TvUi.rounded(c, PLACEHOLDER));
        frame.setClipToOutline(true);
        int imageHeight = Math.round((cardWidth - inset * 2 - TvUi.dp(c, 6)) * 8.4f / 16f);
        card.addView(frame, new LinearLayout.LayoutParams(-1, imageHeight));
        ImageView thumbnail = new ImageView(c);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
        TextView live = TvUi.text(c, "● LIVE", 11, Color.WHITE);
        android.text.SpannableString liveText = new android.text.SpannableString("● LIVE");
        liveText.setSpan(new android.text.style.ForegroundColorSpan(Color.rgb(255, 79, 96)), 0, 1, 0);
        live.setText(liveText);
        live.setPadding(TvUi.dp(c, 6), TvUi.dp(c, 3), TvUi.dp(c, 6), TvUi.dp(c, 3));
        live.setBackground(TvUi.rounded(c, 0xCC0A0F15));
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        badge.setMargins(TvUi.dp(c, 7), TvUi.dp(c, 7), 0, 0);
        frame.addView(live, badge);
        TextView viewers = TvUi.text(c, "", 12, Color.WHITE);
        viewers.setPadding(TvUi.dp(c, 6), TvUi.dp(c, 3), TvUi.dp(c, 6), TvUi.dp(c, 3));
        viewers.setBackground(TvUi.rounded(c, 0xCC0A0F15));
        FrameLayout.LayoutParams audience = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        audience.setMargins(0, 0, TvUi.dp(c, 7), TvUi.dp(c, 7));
        frame.addView(viewers, audience);
        FrameLayout loading = new FrameLayout(c);
        loading.setBackgroundColor(0x990A0F15);
        loading.setVisibility(View.GONE);
        loading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ProgressBar spinner = new ProgressBar(c);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(TvUi.ACCENT));
        loading.addView(spinner, new FrameLayout.LayoutParams(TvUi.dp(c, 42), TvUi.dp(c, 42), Gravity.CENTER));
        frame.addView(loading, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout info = new LinearLayout(c);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(0, TvUi.dp(c, 4), 0, 0);
        card.addView(info, new LinearLayout.LayoutParams(-1, TvUi.dp(c, 40)));
        ImageView avatar = new ImageView(c);
        GradientDrawable avatarBackground = new GradientDrawable();
        avatarBackground.setShape(GradientDrawable.OVAL); avatarBackground.setColor(PLACEHOLDER);
        avatar.setBackground(avatarBackground); avatar.setClipToOutline(true);
        info.addView(avatar, new LinearLayout.LayoutParams(TvUi.dp(c, 30), TvUi.dp(c, 30)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(TvUi.dp(c, 10), 0, 0, 0);
        info.addView(words, new LinearLayout.LayoutParams(0, -1, 1));
        TextView name = TvUi.text(c, "", 16, Color.WHITE);
        TextView game = TvUi.text(c, "", 12, TvUi.MUTED);
        name.setSingleLine(); name.setEllipsize(TextUtils.TruncateAt.END);
        game.setSingleLine(); game.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name, new LinearLayout.LayoutParams(-1, TvUi.dp(c, 21)));
        words.addView(game, new LinearLayout.LayoutParams(-1, TvUi.dp(c, 15)));
        return new Holder(card, thumbnail, avatar, name, game, viewers, live, loading);
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.stream().allMatch("loading"::equals)) bindLoading(h, position);
        else onBindViewHolder(h, position);
    }

    private void bindLoading(Holder h, int position) {
        boolean busy = !skeleton && !loadingLogin.isEmpty() && items.get(position).login().equals(loadingLogin);
        h.loading.setVisibility(busy ? View.VISIBLE : View.GONE);
        h.itemView.setStateDescription(busy ? "Opening stream" : null);
    }

    @Override public void onBindViewHolder(@NonNull Holder h, int position) {
        bindLoading(h, position);
        h.itemView.setOnClickListener(null);
        h.itemView.setOnFocusChangeListener((view, focused) -> { if (focused) focusedPosition = h.getBindingAdapterPosition(); });
        h.itemView.setFocusable(!skeleton);
        if (skeleton) {
            Glide.with(h.itemView).clear(h.thumbnail);
            Glide.with(h.itemView).clear(h.avatar);
            h.thumbnail.setImageDrawable(new ColorDrawable(PLACEHOLDER));
            h.avatar.setImageDrawable(new ColorDrawable(PLACEHOLDER));
            h.name.setText(" "); h.game.setText(" ");
            h.name.setBackgroundColor(PLACEHOLDER); h.game.setBackgroundColor(0xFF19232F);
            h.live.setVisibility(View.INVISIBLE); h.viewers.setVisibility(View.INVISIBLE);
            h.itemView.setContentDescription("Loading stream");
            return;
        }
        StreamChannel item = items.get(position);
        h.name.setBackgroundColor(Color.TRANSPARENT); h.game.setBackgroundColor(Color.TRANSPARENT);
        h.name.setText(item.name()); h.game.setText(item.game());
        h.viewers.setText(java.text.NumberFormat.getIntegerInstance().format(item.viewers()) + " viewers");
        h.live.setVisibility(View.VISIBLE); h.viewers.setVisibility(View.VISIBLE);
        h.itemView.setContentDescription(item.name() + ", live, " + item.viewers() + " viewers, " + item.game() + ", " + item.title());
        h.itemView.setOnClickListener(view -> select.accept(item));
        Glide.with(h.itemView).load(https(item.thumbnail())).placeholder(new ColorDrawable(PLACEHOLDER))
                .signature(new ObjectKey(System.currentTimeMillis() / 300_000)).diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .centerCrop().dontAnimate().into(h.thumbnail);
        Glide.with(h.itemView).load(https(item.avatar())).placeholder(new ColorDrawable(PLACEHOLDER)).circleCrop().dontAnimate().into(h.avatar);
    }

    private static String https(String url) { return url.startsWith("https://") ? url : null; }
    @Override public void onViewRecycled(@NonNull Holder holder) {
        Glide.with(holder.itemView).clear(holder.thumbnail);
        Glide.with(holder.itemView).clear(holder.avatar);
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumbnail, avatar;
        final TextView name, game, viewers, live;
        final FrameLayout loading;
        Holder(View root, ImageView thumbnail, ImageView avatar, TextView name, TextView game, TextView viewers, TextView live, FrameLayout loading) {
            super(root); this.thumbnail = thumbnail; this.avatar = avatar; this.name = name; this.game = game; this.viewers = viewers; this.live = live;
            this.loading = loading;
        }
    }
}
