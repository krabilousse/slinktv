package tv.slink.app;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import androidx.activity.ComponentActivity;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34,qualifiers="w960dp-h540dp-land-xhdpi")
public class VideosScreenTest {
    static class Service implements TwitchApi.Transport {
        final int follows;
        final List<String> videos=new CopyOnWriteArrayList<>();
        boolean limited;
        final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        volatile java.util.concurrent.Semaphore gate;
        java.util.Set<String> live=java.util.Set.of();
        Service(int follows){this.follows=follows;}
        @Override public TwitchApi.Reply request(String method,String url,Map<String,String> headers,String form)throws Exception{
            JSONObject result=new JSONObject().put("data",new JSONArray()).put("pagination",new JSONObject());
            if(url.endsWith("validate"))return new TwitchApi.Reply(200,new JSONObject().put("client_id","client").put("user_id","42").put("login","viewer").put("scopes",new JSONArray().put("user:read:follows")),0);
            if(url.contains("channels/followed"))result.put("data",VideoFeatureTest.follows(follows));
            if(url.contains("streams/followed")){JSONArray rows=new JSONArray();for(String id:live)rows.put(new JSONObject().put("user_id",id));result.put("data",rows);}
            if(url.contains("/videos?")){
                int call=calls.incrementAndGet();videos.add(url);if(gate!=null)gate.acquire();if(limited&&call==2)return new TwitchApi.Reply(429,new JSONObject(),120);
                String channel=url.split("user_id=")[1].split("&")[0];
                String id=""+(Long.parseLong(channel)*100+(url.contains("after=")?2:1));
                JSONObject video=VideoFeatureTest.video(id,channel,"2026-11-08T10:00:00Z");video.put("thumbnail_url","");
                result.put("data",new JSONArray().put(video));
                if(url.contains("first=25")&&!url.contains("after="))result.put("pagination",new JSONObject().put("cursor","older"));
            }
            return new TwitchApi.Reply(200,result,0);
        }
    }
    private AuthViewModel account(Service service)throws Exception{
        SessionStore store=new SessionStore(){
            public TwitchApi.Session load(){return new TwitchApi.Session("client","test-access","test-refresh",System.currentTimeMillis()+3_600_000);}
            public void save(TwitchApi.Session session){}public void clear(){}
        };
        AuthViewModel account=new AuthViewModel(RuntimeEnvironment.getApplication(),new TwitchApi(service),store);
        await(()->account.state().getValue() instanceof AuthViewModel.Ready);return account;
    }
    private void await(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));if(condition.getAsBoolean())return;Thread.sleep(3);}
        fail("Timed out waiting for UI state");
    }
    private RecyclerView grid(View root){if(root instanceof RecyclerView list)return list;if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){RecyclerView list=grid(group.getChildAt(i));if(list!=null)return list;}return null;}
    private ListView list(View root){if(root instanceof ListView list)return list;if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){ListView list=list(group.getChildAt(i));if(list!=null)return list;}return null;}
    @Test public void discoveryRefreshesEveryOfflineFollowOnceWithoutLoadMore()throws Exception{
        Service service=new Service(70);service.live=java.util.Set.of("1","2","3");AuthViewModel account=account(service);
        try(var activity=Robolectric.buildActivity(ComponentActivity.class).setup()){
            VideosScreen screen=new VideosScreen(activity.get(),account,()->{});
            try{
                View root=screen.show(((AuthViewModel.Ready)account.state().getValue()).snapshot());activity.get().setContentView(root);
                await(()->grid(root).getAdapter().getItemCount()==67);
                assertEquals(67,service.videos.size());assertEquals(67,new java.util.HashSet<>(service.videos).size());
                assertTrue(service.videos.stream().allMatch(url->url.contains("first=1")));
                assertTrue(service.videos.stream().noneMatch(url->service.live.contains(url.split("user_id=")[1].split("&")[0])));
                assertNull(VideoFeatureTest.button(root,"Load more"));assertEquals(View.GONE,VideoFeatureTest.button(root,"Older videos").getVisibility());
            }finally{screen.destroy();}
        }finally{account.onCleared();}
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void cardsStayPutUnderTheCursorAndBackJumpsToNewVideosAbove()throws Exception{
        Service service=new Service(20);service.gate=new java.util.concurrent.Semaphore(8);AuthViewModel account=account(service);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        try(var activity=Robolectric.buildActivity(ComponentActivity.class).setup()){
            VideosScreen screen=new VideosScreen(activity.get(),account,()->{});
            try{
                View content=screen.show(((AuthViewModel.Ready)account.state().getValue()).snapshot());
                FrameLayout root=new FrameLayout(activity.get());root.setBackgroundColor(TvUi.BACKGROUND);root.setPadding(64,32,64,16);root.addView(content);activity.get().setContentView(root);
                await(()->grid(root).getAdapter().getItemCount()==8);layout(root);
                RecyclerView cards=grid(root);cards.getChildAt(1).requestFocus();long id=cards.getChildViewHolder(cards.getChildAt(1)).getItemId();
                service.gate.release(8);await(()->service.videos.size()==20);
                assertEquals(8,cards.getAdapter().getItemCount());
                service.gate.release(4);await(()->cards.getAdapter().getItemCount()==20);layout(root);
                assertEquals(id,cards.getChildViewHolder(cards.getFocusedChild()).getItemId());
                android.widget.Button newer=VideoFeatureTest.button(root,"↑ 11 new · press Back");assertEquals(View.VISIBLE,newer.getVisibility());
                capture(root,"videos-new-above-0.10.1.png");
                assertTrue(screen.back());layout(root);
                assertEquals(0,cards.getChildAdapterPosition(cards.getFocusedChild()));assertEquals(View.GONE,newer.getVisibility());
                assertFalse(screen.back());
            }finally{service.gate.release(100);screen.destroy();}
        }finally{account.onCleared();}
    }
    @Test public void channelPickerCanFetchOlderPagesAndLogoutRemovesTheCatalog()throws Exception{
        Service service=new Service(3);AuthViewModel account=account(service);
        try(var activity=Robolectric.buildActivity(ComponentActivity.class).setup()){
            VideosScreen screen=new VideosScreen(activity.get(),account,()->{});
            try{
                View root=screen.show(((AuthViewModel.Ready)account.state().getValue()).snapshot());activity.get().setContentView(root);
                await(()->grid(root).getAdapter().getItemCount()==3);
                VideoFeatureTest.button(root,"Channels").performClick();
                var dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();ListView channels=list(dialog.getWindow().getDecorView());assertEquals(3,channels.getCount());
                channels.performItemClick(null,0,0);
                await(()->VideoFeatureTest.button(root,"Older videos").isEnabled());
                assertEquals(1,grid(root).getAdapter().getItemCount());VideoFeatureTest.button(root,"Older videos").performClick();
                await(()->grid(root).getAdapter().getItemCount()==2);
                assertTrue(service.videos.get(service.videos.size()-1).contains("after=older"));
                screen.updateAccount(null);screen.resume();assertNull(grid(root));assertNotNull(VideoFeatureTest.button(root,"Sign in with QR"));
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertNull(grid(root));
            }finally{screen.destroy();}
        }finally{account.onCleared();}
    }
    @Test public void rateLimitRetainsKnownCardsAndWaitsBeforeRetrying()throws Exception{
        Service service=new Service(3);service.limited=true;AuthViewModel account=account(service);
        try(var activity=Robolectric.buildActivity(ComponentActivity.class).setup()){
            VideosScreen screen=new VideosScreen(activity.get(),account,()->{});
            try{
                View root=screen.show(((AuthViewModel.Ready)account.state().getValue()).snapshot());activity.get().setContentView(root);
                await(()->service.videos.size()==3&&grid(root).getAdapter().getItemCount()==2);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));assertEquals(3,service.videos.size());
                assertTrue(((android.widget.TextView)((ViewGroup)root).getChildAt(1)).getText().toString().startsWith("Twitch rate limit · resuming in"));
            }finally{screen.destroy();}
        }finally{account.onCleared();}
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void videoWallFitsTvAndFocusReturnsToTheSameVideo()throws Exception{
        Service service=new Service(6);AuthViewModel account=account(service);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        try(var activity=Robolectric.buildActivity(ComponentActivity.class).setup()){
            VideosScreen screen=new VideosScreen(activity.get(),account,()->{});
            try{
                VideoProgress progress=new VideoProgress(activity.get());
                progress.save("42","201",45_201_000,90_402_000,true);progress.save("42","401",9_040_200,90_402_000,true);
                View content=screen.show(((AuthViewModel.Ready)account.state().getValue()).snapshot());
                FrameLayout root=new FrameLayout(activity.get());root.setBackgroundColor(TvUi.BACKGROUND);root.setPadding(64,32,64,16);root.addView(content);activity.get().setContentView(root);
                await(()->grid(root).getAdapter().getItemCount()==6);
                layout(root);
                RecyclerView cards=grid(root);assertEquals(6,cards.getChildCount());
                for(int i=0;i<cards.getChildCount();i++){
                    var holder=(VideoCards.Holder)cards.getChildViewHolder(cards.getChildAt(i));
                    holder.image.setImageDrawable(new android.graphics.drawable.ColorDrawable(i%2==0?0xFF3A4A6B:0xFF5B3A4A));
                    assertTrue(holder.itemView.getBottom()<=cards.getHeight());
                }
                assertTrue(VideoFeatureTest.button(root,"Refresh").getRight()<=((View)VideoFeatureTest.button(root,"Refresh").getParent()).getWidth());
                var half=(VideoCards.Holder)cards.getChildViewHolder(cards.getChildAt(1));
                assertEquals(View.VISIBLE,half.resume.getVisibility());assertEquals(500,half.resume.getProgress());
                assertSame(half.image.getParent(),half.resume.getParent());assertTrue(half.resume.getHeight()>=TvUi.dp(activity.get(),4));
                assertTrue(half.itemView.getContentDescription().toString().endsWith("50% watched"));
                assertEquals(View.INVISIBLE,((VideoCards.Holder)cards.getChildViewHolder(cards.getChildAt(0))).resume.getVisibility());
                cards.getChildAt(1).requestFocus();long id=cards.getChildViewHolder(cards.getChildAt(1)).getItemId();
                screen.hide();screen.resume();layout(root);
                assertEquals(id,cards.getChildViewHolder(cards.getFocusedChild()).getItemId());
                capture(root,"videos-wall-0.6.0.png");
            }finally{screen.destroy();}
        }finally{account.onCleared();}
    }
    static void layout(View root){root.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY));root.layout(0,0,1920,1080);root.getViewTreeObserver().dispatchOnPreDraw();}
    static void capture(View root,String name)throws Exception{
        var bitmap=android.graphics.Bitmap.createBitmap(1920,1080,android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(bitmap));
        var path=java.nio.file.Path.of("build/reports/ui/"+name);java.nio.file.Files.createDirectories(path.getParent());try(var output=java.nio.file.Files.newOutputStream(path)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}bitmap.recycle();
    }
}
