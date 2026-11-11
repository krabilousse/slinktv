package tv.slink.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Videos owns its catalog requests independently of the live wall. All callbacks are generation-scoped. */
final class VideosScreen {
    private final ComponentActivity activity;
    private final AuthViewModel account;
    private final Runnable signIn;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final int PARALLEL = 8;
    private final ExecutorService resolver = Executors.newSingleThreadExecutor(), fetchers = Executors.newFixedThreadPool(PARALLEL);
    private VideoLibrary library = new VideoLibrary();
    private TwitchApi.Snapshot snapshot;
    private String owner = "", selectedChannel = "", cursor = "", returnFocus = "";
    private final List<VideoItem> channelVideos = new ArrayList<>();
    private final Set<String> cursors = new java.util.HashSet<>();
    private LinearLayout root;
    private TextView heading, status;
    private Button more, channels, refresh, newer;
    private List<String> discovery = List.of();
    private List<VideoItem> shown = List.of();
    private final Set<String> fresh = new java.util.HashSet<>();
    private RecyclerView grid;
    private VideoCards cards;
    private AlertDialog dialog;
    private int generation, playGeneration;
    private boolean active, busy, opening;
    private long retryAfter;

    VideosScreen(ComponentActivity activity, AuthViewModel account, Runnable signIn) { this.activity=activity;this.account=account;this.signIn=signIn; }
    View show(TwitchApi.Snapshot data) {
        updateAccount(data); active=true;
        build(); resume(); return root;
    }
    void updateAccount(TwitchApi.Snapshot data) {
        String next = data == null ? "" : data.validation().optString("user_id");
        boolean changed = !owner.equals(next);
        if (changed) {
            hide(); owner=next; library=new VideoLibrary(); selectedChannel=""; channelVideos.clear(); cursor=""; returnFocus=""; discovery=List.of(); retryAfter=0;
        }
        if (snapshot != data) { generation++; busy=false; }
        snapshot=data;
        try { if (data!=null) {
            library.follows(data.followed());
            WatchHistory history=new WatchHistory(activity);
            discovery=library.offlineOrder(System.currentTimeMillis(),id->history.isEnabled(owner)?history.score(owner,id):0);
        } } catch (Exception ignored) { }
        if (root!=null && changed) build();
    }
    private int dp(int size) { return TvUi.dp(activity,size); }
    private LinearLayout column() { LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view; }
    private Button button(String label, View.OnClickListener action) { Button button=TvUi.button(activity,label,action);button.setTextSize(14);button.setMinHeight(dp(40));button.setPadding(dp(14),dp(4),dp(14),dp(4));return button; }
    private void build() {
        if (root==null) root=column(); else root.removeAllViews();
        shown=List.of();fresh.clear();
        if (owner.isEmpty()) {
            cards=null;grid=null;newer=null;
            TextView title=TvUi.text(activity,"Videos",24,Color.WHITE);title.setPadding(dp(6),dp(12),0,dp(16));root.addView(title);
            root.addView(TvUi.text(activity,"Sign in to see videos from channels you follow.",18,TvUi.MUTED));
            Button login=button("Sign in with QR",v->signIn.run());root.addView(login);return;
        }
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        heading=TvUi.text(activity,"Videos",22,Color.WHITE);heading.setMaxLines(1);heading.setMaxWidth(dp(230));heading.setEllipsize(android.text.TextUtils.TruncateAt.END);heading.setPadding(dp(6),0,dp(16),0);row.addView(heading);
        row.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));
        newer=button("",v->top());newer.setSelected(true);newer.setVisibility(View.GONE);row.addView(newer);
        channels=button("Channels",v->chooseChannel());row.addView(channels);
        refresh=button("Refresh",v->{
            if (busy) return;
            if (!selectedChannel.isEmpty()) loadChannel(false);
            else { generation++;busy=false;library.invalidate();status.setText("Refreshing follows…");account.refreshData(); }
        });row.addView(refresh);
        more=button("Older videos",v->{ if(!busy) loadChannel(true); });row.addView(more);
        root.addView(row,new LinearLayout.LayoutParams(-1,dp(42)));
        status=TvUi.text(activity,"",12,TvUi.MUTED);status.setMaxLines(1);status.setEllipsize(android.text.TextUtils.TruncateAt.END);status.setPadding(dp(6),0,0,0);
        root.addView(status,new LinearLayout.LayoutParams(-1,dp(18)));
        grid=new RecyclerView(activity);grid.setLayoutManager(new GridLayoutManager(activity,3));grid.setItemAnimator(null);grid.setHasFixedSize(true);grid.setClipToPadding(false);
        cards=new VideoCards(activity,owner,library,this::play,item->openChannel(item.channelId()),item->announce(item.id()));grid.setAdapter(cards);
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        publish();
    }
    void resume() {
        active=true;if(owner.isEmpty()||root==null)return;
        if (!returnFocus.isEmpty()) { cards.notifyDataSetChanged();restoreFocus(returnFocus);returnFocus=""; }
        if (!selectedChannel.isEmpty()) { if(channelVideos.isEmpty())loadChannel(false); return; }
        if (!library.liveKnown(System.currentTimeMillis())) refreshLive(); else scan();
    }
    void hide() { active=false;generation++;busy=false;main.removeCallbacksAndMessages(null);cancelOpening();if(dialog!=null)dialog.dismiss(); }
    void destroy() { hide();resolver.shutdownNow();fetchers.shutdownNow(); }
    boolean back() {
        if(opening){cancelOpening();return true;}
        if(!selectedChannel.isEmpty()){generation++;busy=false;selectedChannel="";channelVideos.clear();cursor="";heading.setText("Videos");publish();resume();return true;}
        if(newer!=null&&newer.getVisibility()==View.VISIBLE){top();return true;}
        return false;
    }
    private void refreshLive() {
        if (!active||busy||owner.isEmpty())return;
        busy=true;int request=generation;String user=owner;
        if(library.recent(false,System.currentTimeMillis()).isEmpty())cards.loading();status.setText("Checking followed channels…");
        account.request((api,session)->api.liveFollowedIds(session,user),(live,error)->{
            if(!active||request!=generation)return;busy=false;
            if(error==null){
                long now=System.currentTimeMillis(); library.live(live,now);
                WatchHistory history=new WatchHistory(activity);
                discovery=library.offlineOrder(now,id->history.isEnabled(owner)?history.score(owner,id):0);
            }
            publish();
            if(error!=null){status.setText("Live status unavailable. Refresh to retry.");return;}
            scan();
        });
    }
    private record Lookup(TwitchApi.VideoPage page,String error,int status,int retrySeconds) {}
    private Lookup lookup(TwitchApi api,TwitchApi.Session session,String channel,String cursor,int count) throws Exception {
        try{return new Lookup(api.videos(session,channel,cursor,count),null,200,0);}
        catch(TwitchApi.ApiException error){if(error.status==401)throw error;return new Lookup(null,error.getMessage(),error.status,error.retrySeconds);}
        catch(Exception error){return new Lookup(null,"Request failed",0,0);}
    }
    private void scan() {
        if(!active||busy||owner.isEmpty()||!selectedChannel.isEmpty())return;
        long now=System.currentTimeMillis();
        List<String> stale=discovery.stream().filter(id->library.offline(id,now)&&!library.fresh(id,now)).toList();
        List<String> batch=stale.subList(0,Math.min(PARALLEL,stale.size()));
        // While the cursor is on the cards they move once, when the whole pass is in.
        if(batch.isEmpty()||now<retryAfter||!grid.hasFocus())publish();
        if(batch.isEmpty())return;
        if(now<retryAfter){
            status.setText("Twitch rate limit · resuming in "+Math.max(1,(retryAfter-now)/1000)+"s");
            main.removeCallbacksAndMessages(null);main.postDelayed(this::resume,retryAfter-now);return;
        }
        busy=true;int request=generation;
        if(library.recent(false,now).isEmpty())cards.loading();
        status.setText("Refreshing videos · "+(discovery.size()-stale.size())+" / "+discovery.size()+" channels");
        account.request((api,session)->{
            List<java.util.concurrent.Callable<Lookup>> calls=new ArrayList<>();
            for(String id:batch)calls.add(()->lookup(api,session,id,"",1));
            List<Lookup> results=new ArrayList<>();
            try{for(var call:fetchers.invokeAll(calls))results.add(call.get());}
            catch(java.util.concurrent.ExecutionException failure){throw failure.getCause() instanceof Exception cause?cause:failure;}
            return results;
        },(results,error)->{
            if(!active||request!=generation)return;busy=false;
            long done=System.currentTimeMillis();boolean reached=false;
            for(int i=0;i<batch.size();i++){
                Lookup result=results==null?null:results.get(i);
                if(result!=null&&result.error()==null){library.store(batch.get(i),result.page().items(),done);reached=true;}
                // A rate-limited channel stays stale, so the pass picks it up again after the wait.
                else if(result!=null&&result.status()==429)retryAfter=Math.max(retryAfter,done+Math.max(60,result.retrySeconds())*1000L);
                else library.failed(batch.get(i),done);
            }
            if(!reached&&done>=retryAfter){publish();status.setText("Could not load videos. Refresh to retry.");return;}
            scan();
        });
    }
    private void publish() {
        if(cards==null||owner.isEmpty())return;
        String focused=cards.focused();boolean hadFocus=grid.hasFocus();
        List<VideoItem> result=selectedChannel.isEmpty()?library.recent(true,System.currentTimeMillis()):List.copyOf(channelVideos);
        cards.submit(result);
        more.setVisibility(selectedChannel.isEmpty()?View.GONE:View.VISIBLE);more.setEnabled(!busy&&!cursor.isEmpty());
        if(selectedChannel.isEmpty()){
            // Videos that arrived above the cursor are out of sight, so they are counted on a button.
            Set<String> known=shown.stream().map(VideoItem::id).collect(java.util.stream.Collectors.toSet());
            if(hadFocus)for(VideoItem item:result){if(item.id().equals(focused))break;if(!known.contains(item.id()))fresh.add(item.id());}
            shown=result;announce(focused);
            heading.setText("Videos");channels.setText("Channels");
            status.setText(library.channels.isEmpty()?"No followed channels.":
                    (result.isEmpty()?"No archived broadcasts found · ":"")+"Offline followed · "+discovery.size()+" channels"
                    +(library.failureCount()>0?" · "+library.failureCount()+" unavailable":""));
        }else {
            VideoLibrary.Channel channel=library.channels.get(selectedChannel);
            heading.setText(channel==null?"Channel videos":channel.name());
            status.setText(result.isEmpty()?"No archived broadcasts available.":result.size()+" broadcasts · Hold OK: channel videos");
        }
        if(hadFocus&&!focused.isEmpty())restoreFocus(focused);
    }
    private void announce(String id) {
        List<String> ids=shown.stream().map(VideoItem::id).toList();
        long above=ids.subList(0,Math.max(0,ids.indexOf(id))).stream().filter(fresh::contains).count();
        if(above==0)fresh.clear();
        newer.setText("↑ "+above+" new · press Back");newer.setVisibility(above>0?View.VISIBLE:View.GONE);
    }
    private void top() { fresh.clear();newer.setVisibility(View.GONE);if(!shown.isEmpty())restoreFocus(shown.get(0).id()); }
    private void restoreFocus(String id) {
        int position=cards.index(id);if(position<0)return;RecyclerView target=grid;grid.scrollToPosition(position);
        grid.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
            @Override public boolean onPreDraw(){target.getViewTreeObserver().removeOnPreDrawListener(this);if(target==grid&&active){var row=target.findViewHolderForAdapterPosition(cards.index(id));if(row!=null)row.itemView.requestFocus();}return true;}
        });grid.requestLayout();
    }
    private void chooseChannel() {
        LinearLayout body=column();body.setPadding(dp(20),dp(8),dp(20),dp(8));
        if(!selectedChannel.isEmpty())body.addView(button("All followed videos",v->{dialog.dismiss();back();}));
        EditText search=new EditText(activity);search.setSingleLine(true);search.setHint("Find followed channel");search.setTextColor(Color.WHITE);search.setTextSize(17);body.addView(search);
        ListView list=new ListView(activity);body.addView(list,new LinearLayout.LayoutParams(-1,dp(280)));
        List<VideoLibrary.Channel> results=new ArrayList<>();
        Runnable update=()->{
            results.clear();results.addAll(library.findChannels(search.getText().toString(),false,System.currentTimeMillis()));
            List<String> labels=new ArrayList<>();long now=System.currentTimeMillis();
            for(var c:results){long latest=library.latest(c.id());String age=latest==0?"Videos not checked": "Latest video: "+Math.max(0,(now-latest)/3_600_000)+"h ago";labels.add(c.name()+"\n"+library.channelState(c.id(),now)+" · "+age);}
            list.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,labels));
        };
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){update.run();}public void afterTextChanged(Editable s){}});
        list.setOnItemClickListener((parent,view,position,id)->{String channel=results.get(position).id();dialog.dismiss();openChannel(channel);});update.run();
        dialog=new AlertDialog.Builder(activity).setTitle("Followed channels").setView(body).setNegativeButton("Back",null).show();
        list.requestFocus();
    }
    private void openChannel(String id) {
        if(!library.channels.containsKey(id))return;
        cancelOpening();generation++;busy=false;selectedChannel=id;cursor="";channelVideos.clear();cursors.clear();fresh.clear();newer.setVisibility(View.GONE);
        heading.setText(library.channels.get(id).name());channels.setText("Channels");loadChannel(false);
    }
    private void loadChannel(boolean append) {
        if(!active||busy||selectedChannel.isEmpty()||append&&cursor.isEmpty())return;
        if(System.currentTimeMillis()<retryAfter){status.setText("Twitch rate limit. Retry shortly.");return;}
        busy=true;int request=generation;String channel=selectedChannel,after=append?cursor:"";
        if(!append&&channelVideos.isEmpty())cards.loading();status.setText(append?"Loading older videos…":"Loading channel videos…");more.setEnabled(false);
        account.request((api,session)->lookup(api,session,channel,after,25),(result,error)->{
            if(!active||request!=generation)return;busy=false;
            if(error!=null||result.error()!=null){
                if(result!=null&&result.status()==429)retryAfter=System.currentTimeMillis()+Math.max(60,result.retrySeconds())*1000L;
                publish();status.setText("Could not load videos. "+(append?"Retry Older videos.":"Refresh to retry."));return;
            }
            if(!append){channelVideos.clear();cursors.clear();library.store(channel,result.page().items(),System.currentTimeMillis());}
            LinkedHashMap<String,VideoItem> unique=new LinkedHashMap<>();for(var item:channelVideos)unique.put(item.id(),item);for(var item:result.page().items())unique.putIfAbsent(item.id(),item);
            channelVideos.clear();channelVideos.addAll(unique.values());cursor=result.page().cursor();if(!cursor.isEmpty()&&!cursors.add(cursor))cursor="";publish();
        });
    }
    private void play(VideoItem item) {
        if(opening)return;opening=true;int request=++playGeneration;String user=owner;returnFocus=item.id();cards.opening(item.id());
        resolver.execute(()->{
            String data;
            try{data=StreamEngine.call(activity,"resolver","resolve_video",item.id());}catch(Exception error){data="{\"error\":\"Video engine unavailable. Retry.\"}";}
            String result=data;
            activity.runOnUiThread(()->{
                if(!active||request!=playGeneration)return;opening=false;cards.opening("");
                try{
                    JSONObject response=new JSONObject(result);if(response.has("error")){error(response.getString("error"));return;}
                    response.getJSONArray("variants").getJSONObject(0).getString("url");
                    activity.startActivity(new Intent(activity,PlayerActivity.class).putExtra("stream",result).putExtra("vod_id",item.id())
                            .putExtra("account_id",user).putExtra("broadcaster_id",item.channelId()).putExtra("display_name",item.name()).putExtra("stream_title",item.title()));
                }catch(Exception error){error("The video response was incomplete. Retry.");}
            });
        });
    }
    private void error(String message){dialog=new AlertDialog.Builder(activity).setTitle("Video unavailable").setMessage(message).setPositiveButton("Close",null).show();}
    private void cancelOpening(){playGeneration++;opening=false;if(cards!=null)cards.opening("");}
}
