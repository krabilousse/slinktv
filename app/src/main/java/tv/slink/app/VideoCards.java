package tv.slink.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
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
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import java.util.List;
import java.util.function.Consumer;

final class VideoCards extends RecyclerView.Adapter<VideoCards.Holder> {
    private List<VideoItem> items = List.of();
    private final Consumer<VideoItem> open, channel, focus;
    private final VideoProgress progress;
    private final VideoLibrary library;
    private final String owner;
    private boolean loading;
    private long liveRevision;
    private String opening = "", focused = "";
    VideoCards(Context context, String owner, VideoLibrary library, Consumer<VideoItem> open, Consumer<VideoItem> channel, Consumer<VideoItem> focus) {
        this.owner = owner; this.library = library; this.open = open; this.channel = channel; this.focus = focus; progress = new VideoProgress(context);
        setHasStableIds(true);
    }
    void loading() { loading = true; items = List.of(); notifyDataSetChanged(); }
    void submit(List<VideoItem> next) {
        List<VideoItem> old = items;
        long revision = library.liveRevision(System.currentTimeMillis());
        boolean statusChanged = revision != liveRevision; liveRevision = revision;
        if (loading) { loading = false; items = List.copyOf(next); notifyDataSetChanged(); return; }
        var changes = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int a, int b) { return old.get(a).id().equals(next.get(b).id()); }
            @Override public boolean areContentsTheSame(int a, int b) { return !statusChanged && old.get(a).equals(next.get(b)); }
        });
        items = List.copyOf(next); changes.dispatchUpdatesTo(this);
    }
    void opening(String id) { String old = opening; opening = id; for (int i=0;i<items.size();i++) if (items.get(i).id().equals(old) || items.get(i).id().equals(id)) notifyItemChanged(i); }
    String focused() { return focused; }
    int index(String id) { for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(id)) return i; return -1; }
    @Override public long getItemId(int position) { return loading ? -position - 2 : Long.parseLong(items.get(position).id()); }
    @Override public int getItemCount() { return loading ? 9 : items.size(); }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        Context c = parent.getContext(); int width = (parent.getResources().getDisplayMetrics().widthPixels - TvUi.dp(c,64)) / 3;
        LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(TvUi.dp(c,6),TvUi.dp(c,6),TvUi.dp(c,6),TvUi.dp(c,6));
        card.setFocusable(true); card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        StateListDrawable surface = new StateListDrawable(); var focus = TvUi.shape(c, 0xFF17212D, 8); focus.setStroke(TvUi.dp(c,2),TvUi.ACCENT);
        surface.addState(new int[]{android.R.attr.state_focused},focus); surface.addState(new int[]{},TvUi.shape(c,TvUi.BACKGROUND,8)); card.setBackground(surface);
        RecyclerView.LayoutParams bounds = new RecyclerView.LayoutParams(-1,-2); bounds.setMargins(TvUi.dp(c,3),TvUi.dp(c,3),TvUi.dp(c,3),TvUi.dp(c,3)); card.setLayoutParams(bounds);
        FrameLayout preview = new FrameLayout(c); preview.setBackground(TvUi.shape(c,0xFF202C38,6)); preview.setClipToOutline(true);
        card.addView(preview,new LinearLayout.LayoutParams(-1,Math.round((width-TvUi.dp(c,18))*8.3f/16)));
        ImageView image = new ImageView(c); image.setScaleType(ImageView.ScaleType.CENTER_CROP); preview.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView duration = TvUi.text(c,"",11,Color.WHITE); duration.setPadding(TvUi.dp(c,6),TvUi.dp(c,3),TvUi.dp(c,6),TvUi.dp(c,3)); duration.setBackground(TvUi.shape(c,0xDD0A0F15,4));
        FrameLayout.LayoutParams stamp = new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END); stamp.setMargins(0,0,TvUi.dp(c,6),TvUi.dp(c,10)); preview.addView(duration,stamp);
        ProgressBar opening = new ProgressBar(c); opening.setIndeterminateTintList(ColorStateList.valueOf(TvUi.ACCENT)); preview.addView(opening,new FrameLayout.LayoutParams(TvUi.dp(c,32),TvUi.dp(c,32),Gravity.CENTER));
        TextView name = line(c,15), title = line(c,13), details = line(c,11);
        name.setPadding(0,TvUi.dp(c,4),0,0); name.setTypeface(null,android.graphics.Typeface.BOLD);
        card.addView(name,new LinearLayout.LayoutParams(-1,TvUi.dp(c,22))); card.addView(title,new LinearLayout.LayoutParams(-1,TvUi.dp(c,18)));
        details.setTextColor(Color.WHITE);details.setPadding(TvUi.dp(c,5),TvUi.dp(c,3),TvUi.dp(c,5),TvUi.dp(c,3));
        FrameLayout.LayoutParams facts = new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START);
        facts.setMargins(TvUi.dp(c,6),0,TvUi.dp(c,76),TvUi.dp(c,10));preview.addView(details,facts);
        ProgressBar resume = new ProgressBar(c,null,android.R.attr.progressBarStyleHorizontal); resume.setMax(1000);
        LayerDrawable bar = new LayerDrawable(new Drawable[]{new ColorDrawable(0xCC0A0F15),new ClipDrawable(new ColorDrawable(TvUi.ACCENT),Gravity.START,ClipDrawable.HORIZONTAL)});
        bar.setId(0,android.R.id.background); bar.setId(1,android.R.id.progress); resume.setProgressDrawable(bar);
        preview.addView(resume,new FrameLayout.LayoutParams(-1,TvUi.dp(c,5),Gravity.BOTTOM));
        return new Holder(card,image,duration,name,title,details,opening,resume);
    }
    private TextView line(Context c,int size) { TextView t=TvUi.text(c,"",size,size==15?Color.WHITE:TvUi.MUTED); t.setMaxLines(1); t.setEllipsize(TextUtils.TruncateAt.END);return t; }
    @Override public void onBindViewHolder(@NonNull Holder h,int position) {
        Glide.with(h.image).clear(h.image); h.image.setImageDrawable(null); h.itemView.setFocusable(!loading);
        h.itemView.setOnClickListener(null); h.itemView.setOnLongClickListener(null); h.opening.setVisibility(View.GONE);
        h.resume.setVisibility(View.INVISIBLE);
        if (loading) {
            h.name.setText("");h.title.setText("");h.details.setVisibility(View.GONE);h.duration.setVisibility(View.GONE);
            for (TextView text: new TextView[]{h.name,h.title}) text.setBackground(TvUi.shape(text.getContext(),0xFF1A2430,3)); return;
        }
        VideoItem item = items.get(position); long now = System.currentTimeMillis();
        for (TextView text: new TextView[]{h.name,h.title}) text.setBackgroundColor(Color.TRANSPARENT);
        h.details.setVisibility(View.VISIBLE);h.details.setBackground(TvUi.shape(h.details.getContext(),0xDD0A0F15,4));
        if (!item.thumbnail().isEmpty()) Glide.with(h.image).load(item.thumbnail()).centerCrop().into(h.image);
        h.name.setText(item.name()); h.title.setText(item.title()); h.duration.setVisibility(View.VISIBLE); h.duration.setText(VideoItem.clock(item.duration()));
        h.details.setText(item.age(now)+" · "+library.channelState(item.channelId(),now));
        h.opening.setVisibility(item.id().equals(opening)?View.VISIBLE:View.GONE);
        long saved=progress.position(owner,item.id()); int watched=saved>0&&item.duration()>0?(int)Math.min(1000,saved*1000/item.duration()):0;
        if (watched>0) { h.resume.setVisibility(View.VISIBLE);h.resume.setProgress(watched); }
        h.itemView.setContentDescription(item.name()+", "+item.title()+", "+item.age(now)+", "+VideoItem.clock(item.duration())+(watched>0?", "+watched/10+"% watched":""));
        h.itemView.setOnClickListener(v->open.accept(item));h.itemView.setOnLongClickListener(v->{channel.accept(item);return true;});
        h.itemView.setOnFocusChangeListener((v,focused)->{if(focused){this.focused=item.id();focus.accept(item);}});
    }
    @Override public void onViewRecycled(@NonNull Holder h) { Glide.with(h.image).clear(h.image); }
    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image; final TextView duration,name,title,details; final ProgressBar opening,resume;
        Holder(View view,ImageView image,TextView duration,TextView name,TextView title,TextView details,ProgressBar opening,ProgressBar resume) {
            super(view);this.image=image;this.duration=duration;this.name=name;this.title=title;this.details=details;this.opening=opening;this.resume=resume;
        }
    }
}
