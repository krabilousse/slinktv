package tv.slink.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdatePrompt {
    private static final AtomicBoolean checking=new AtomicBoolean();
    static void onHome(Activity activity) {
        UpdateSettings settings=new UpdateSettings(activity);
        long now=System.currentTimeMillis();
        if(!settings.automatic()||now-settings.preferences.getLong("last_check",0)<6*60*60_000L||!checking.compareAndSet(false,true))return;
        settings.preferences.edit().putLong("last_check",now).apply();
        WeakReference<Activity> owner=new WeakReference<>(activity);
        Thread thread=new Thread(()->{
            try{
                var release=new UpdateClient(settings.source(),settings.token()).check(settings.previews(),BuildConfig.VERSION_CODE,android.os.Build.VERSION.SDK_INT);
                Activity current=owner.get();
                if(release!=null&&current!=null)current.runOnUiThread(()->{
                    if(current.isFinishing()||current.isDestroyed()||!current.hasWindowFocus())return;
                    new AlertDialog.Builder(current).setTitle("SlinkTV "+release.manifest().version()).setMessage("An update is available.")
                            .setPositiveButton("View update",(d,w)->current.startActivity(new Intent(current,UpdateActivity.class).putExtra("check",true)))
                            .setNegativeButton("Later",null).show();
                });
            }catch(Exception ignored){}finally{checking.set(false);}
        },"slink-update-check");thread.start();
    }
    private UpdatePrompt(){}
}
