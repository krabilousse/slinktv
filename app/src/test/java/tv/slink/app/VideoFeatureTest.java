package tv.slink.app;

import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34, qualifiers="w960dp-h540dp-land-xhdpi")
public class VideoFeatureTest {
    static JSONObject video(String id,String channel,String date) throws Exception {
        return new JSONObject().put("id",id).put("user_id",channel).put("user_login","channel"+channel).put("user_name","Channel "+channel)
                .put("title","Recent broadcast").put("created_at",date).put("duration","25h6m42s").put("thumbnail_url","https://example.invalid/%{width}x%{height}");
    }
    static JSONArray follows(int count) throws Exception {
        JSONArray rows=new JSONArray();for(int i=1;i<=count;i++)rows.put(new JSONObject().put("broadcaster_id",""+i).put("broadcaster_login","channel"+i).put("broadcaster_name","Channel "+i));return rows;
    }
    @Test public void helixArchivesKeepLongDurationsAndRequestChannelPagination() throws Exception {
        var http=new TwitchApiTest.FakeHttp().reply(200,new JSONObject().put("data",new JSONArray().put(video("123","1","2026-11-08T10:00:00Z"))).put("pagination",new JSONObject().put("cursor","next+page")).toString());
        var page=new TwitchApi(http).videos(new TwitchApi.Session("own-client","access","refresh",0),"1","before+page",5);
        assertEquals("next+page",page.cursor());assertEquals("25:06:42",VideoItem.clock(page.items().get(0).duration()));
        assertEquals("https://example.invalid/640x360",page.items().get(0).thumbnail());
        var request=http.requests.get(0);assertTrue(request.url().contains("type=archive&sort=time&first=5&after=before%2Bpage"));
        assertEquals("own-client",request.headers().get("Client-Id"));assertEquals("Bearer access",request.headers().get("Authorization"));
    }
    @Test public void discoveryRanksOfflineFollowsByObservedOfflineTimeThenRelevance() throws Exception {
        VideoLibrary library=new VideoLibrary();library.follows(follows(500));
        assertTrue(library.offlineOrder(1000,id->0).isEmpty());
        library.live(Set.of("1","2"),1000);
        var ranked=library.offlineOrder(1001,id->id.equals("499")?100:0);
        assertEquals(498,ranked.size());assertEquals("499",ranked.get(0));assertFalse(ranked.contains("1"));
        library.invalidate();library.live(Set.of("2"),2000);
        assertEquals("1",library.offlineOrder(2001,id->id.equals("499")?100:0).get(0));
        assertTrue(library.offlineOrder(302001,id->0).isEmpty());
    }
    @Test public void longArchiveSeeksAccelerateButCorrectiveTapsRemainPrecise() {
        long duration=25*60*60_000L;
        assertEquals(30000,VodTimeBar.seekStep(600,duration));
        assertEquals(300000,VodTimeBar.seekStep(2000,duration));
        assertEquals(600000,VodTimeBar.seekStep(5000,duration));
        assertEquals(1500000,VodTimeBar.seekStep(8000,duration));
        assertEquals(88200000,VideoTime.parse("24:30:00",duration));
        assertEquals(90000,VideoTime.parse("1:30",duration));
        assertThrows(IllegalArgumentException.class,()->VideoTime.parse("25:60:00",duration));
        assertThrows(IllegalArgumentException.class,()->VideoTime.parse("26:00:00",duration));
    }
    @Test public void offlineFilterRequiresCurrentSuccessfulLiveDataAndRecentSortUsesVideoTime() throws Exception {
        VideoLibrary library=new VideoLibrary();library.follows(follows(3));
        library.store("1",List.of(VideoItem.parse(video("1","1","2026-11-07T10:00:00Z"))),1000);
        library.store("2",List.of(VideoItem.parse(video("2","2","2026-11-08T10:00:00Z"))),1000);
        assertTrue(library.recent(true,1001).isEmpty());library.live(Set.of("1"),1000);
        assertEquals("2",library.recent(true,1001).get(0).id());assertEquals("2",library.recent(false,1001).get(0).id());
        assertFalse(library.offline("2",301001));library.failed("3",1000);assertEquals(1,library.failureCount());
        assertEquals("2",library.findChannels("",true,1001).get(0).id());
    }
    @Test public void resumeIsAccountScopedAndGrowingVideoEdgeIsNotMarkedFinished() {
        VideoProgress progress=new VideoProgress(RuntimeEnvironment.getApplication());
        progress.save("a","12",590000,600000,false);assertEquals(590000,progress.position("a","12"));assertEquals(0,progress.position("b","12"));
        progress.save("a","12",590000,600000,true);assertEquals(0,progress.position("a","12"));
        progress.save("b","12",300000,600000,true);progress.clear("a");assertEquals(300000,progress.position("b","12"));
    }
    @Test public void timelineRequiresConfirmationHonorsHoldThresholdAndCancelsWithoutSeeking() {
        VodTimeBar bar=new VodTimeBar(RuntimeEnvironment.getApplication());bar.setDuration(200000);bar.setPosition(50000);
        List<Long> previews=new ArrayList<>(),committed=new ArrayList<>();List<Boolean> cancelled=new ArrayList<>();
        bar.listen(new TimeBar.OnScrubListener(){public void onScrubStart(TimeBar b,long p){previews.add(p);}public void onScrubMove(TimeBar b,long p){previews.add(p);}public void onScrubStop(TimeBar b,long p,boolean c){if(!c)committed.add(p);cancelled.add(c);}});
        bar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(1000,1000,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,0));
        bar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(1000,1599,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,1));
        assertEquals(List.of(60000L),previews);
        bar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(1000,1600,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,2));
        assertEquals(Long.valueOf(90000),previews.get(1));
        bar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(1000,6000,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,3));
        assertEquals(Long.valueOf(150000),previews.get(2));
        bar.onKeyUp(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));assertTrue(committed.isEmpty());
        bar.cancelScrub();assertEquals(List.of(true),cancelled);
        bar.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));
        bar.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals(List.of(40000L),committed);
    }
    @Test public void vodQualityChangeAndActivityRestartPreservePausedPositionAndExcludeLiveChat() throws Exception {
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),PlayerActivity.class).putExtra("vod_id","123").putExtra("account_id","a")
                .putExtra("stream","{\"kind\":\"vod\",\"variants\":[{\"name\":\"1080p60\",\"url\":\"https://example.invalid/high\"},{\"name\":\"720p60\",\"url\":\"https://example.invalid/low\"}]}");
        try(var controller=Robolectric.buildActivity(PlayerActivity.class,intent).create().start().resume().visible()){
            var activity=controller.get();FrameLayout root=(FrameLayout)((FrameLayout)activity.findViewById(android.R.id.content)).getChildAt(0);PlayerView view=(PlayerView)root.getChildAt(0);
            assertNull(button(root,"Catch up to live"));assertNull(button(root,"Hide chat"));assertNotNull(button(root,"Start over"));
            view.getPlayer().pause();view.getPlayer().seekTo(123000);
            button(root,"Quality").performClick();var dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();dialog.getListView().performItemClick(null,1,1);
            assertEquals(123000,view.getPlayer().getCurrentPosition());assertFalse(view.getPlayer().getPlayWhenReady());
            controller.pause().stop().start().resume();
            assertEquals(123000,view.getPlayer().getCurrentPosition());assertFalse(view.getPlayer().getPlayWhenReady());
        }
    }
    static Button button(View view,String label){
        if(view instanceof Button b&&(label.contentEquals(b.getText())||label.contentEquals(b.getContentDescription()==null?"":b.getContentDescription())))return b;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){Button b=button(group.getChildAt(i),label);if(b!=null)return b;}return null;
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void vodTimelineAndIconActionsFitOnTv() throws Exception {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        Intent intent = new Intent(RuntimeEnvironment.getApplication(),PlayerActivity.class).putExtra("vod_id","123")
                .putExtra("display_name","Linkus7").putExtra("stream_title","The Legend of Zelda · Archived broadcast")
                .putExtra("stream","{\"kind\":\"vod\",\"variants\":[{\"name\":\"1080p60\",\"url\":\"https://example.invalid/high\"}]}");
        try(var controller=Robolectric.buildActivity(PlayerActivity.class,intent).create().start().resume().visible()) {
            FrameLayout root=(FrameLayout)((FrameLayout)controller.get().findViewById(android.R.id.content)).getChildAt(0);
            ((PlayerView)root.getChildAt(0)).getPlayer().stop();Shadows.shadowOf(Looper.getMainLooper()).idle();
            var field=PlayerActivity.class.getDeclaredField("timeline");field.setAccessible(true);VodTimeBar timeline=(VodTimeBar)field.get(controller.get());
            timeline.setDuration(90402000);timeline.setPosition(45200000);timeline.setBufferedPosition(45290000);timeline.setEnabled(true);
            var time=PlayerActivity.class.getDeclaredField("timeLabel");time.setAccessible(true);((android.widget.TextView)time.get(controller.get())).setText("12:33:20 / 25:06:42");
            VideosScreenTest.layout(root);button(root,"Pause").requestFocus();root.getViewTreeObserver().dispatchOnPreDraw();
            for(String label:new String[]{"Back 10 seconds","Pause","Forward 10 seconds","Start over","Quality","Retry","Videos"}) {
                Button action=button(root,label);assertNotNull(action);assertTrue(action.getRight()<=((View)action.getParent()).getWidth());
            }
            assertSame(timeline,button(root,"Pause").focusSearch(View.FOCUS_UP));
            VideosScreenTest.capture(root,"player-vod-0.6.0.png");
        }
    }
    @Test public void videosTabIsNextToHomeAndSignedOutViewContainsNoPreviousAccountCards() {
        try(var controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible()){
            View root=controller.get().findViewById(android.R.id.content);Button videos=button(root,"Videos"),home=button(root,"Home");
            ViewGroup navigation=(ViewGroup)home.getParent();assertEquals(navigation.indexOfChild(home)+1,navigation.indexOfChild(videos));
            videos.performClick();assertNotNull(button(root,"Sign in with QR"));assertNull(button(root,"Load more"));
            controller.get().getOnBackPressedDispatcher().onBackPressed();assertTrue(home.isSelected());
        }
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void timelineTooltipTracksPendingTargetAndUpCancelsIt() throws Exception {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),PlayerActivity.class).putExtra("display_name","Linkus7")
                .putExtra("stream_title","Archived broadcast").putExtra("stream","{\"kind\":\"vod\",\"variants\":[{\"name\":\"1080p60\",\"url\":\"https://example.invalid/high\"}]}");
        try(var controller=Robolectric.buildActivity(PlayerActivity.class,intent).create().start().resume().visible()) {
            PlayerActivity activity=controller.get();View root=activity.findViewById(android.R.id.content);
            var playerField=PlayerActivity.class.getDeclaredField("player");playerField.setAccessible(true);Object real=playerField.get(activity);
            Object simulated=java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{androidx.media3.exoplayer.ExoPlayer.class},(proxy,method,args)->switch(method.getName()){
                case "getDuration" -> 90_402_000L;
                case "getCurrentPosition","getBufferedPosition" -> 45_200_000L;
                case "isCurrentMediaItemSeekable","getPlayWhenReady" -> true;
                default -> {
                    if(method.getReturnType()==boolean.class)yield false;
                    if(method.getReturnType()==long.class)yield 0L;
                    if(method.getReturnType()==int.class)yield 0;
                    yield null;
                }
            });
            try {
                playerField.set(activity,simulated);VideosScreenTest.layout(root);button(root,"Seek").performClick();VideosScreenTest.layout(root);
                var field=PlayerActivity.class.getDeclaredField("timeline");field.setAccessible(true);VodTimeBar bar=(VodTimeBar)field.get(activity);
                bar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));
                var tooltipField=PlayerActivity.class.getDeclaredField("seekTooltip");tooltipField.setAccessible(true);var tooltip=(android.widget.TextView)tooltipField.get(activity);
                assertEquals("12:33:30",tooltip.getText().toString());assertEquals(View.VISIBLE,tooltip.getVisibility());
                assertTrue(tooltip.getTranslationX()>0);assertTrue(tooltip.getTranslationX()+tooltip.getWidth()<=((View)tooltip.getParent()).getWidth());
                VideosScreenTest.capture(root,"vod-seek-tooltip-0.6.0.png");
                View playerRoot=((android.widget.FrameLayout)root).getChildAt(0);
                playerRoot.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP));
                assertEquals(View.GONE,((View)button(root,"Pause").getParent().getParent()).getVisibility());
            } finally { playerField.set(activity,real); }
        }
    }
}
