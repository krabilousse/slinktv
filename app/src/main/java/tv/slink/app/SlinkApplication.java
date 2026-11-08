package tv.slink.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** One account worker owns all refreshes, login and account-data requests in this process. */
public class SlinkApplication extends Application {
    private AuthViewModel account;
    private int visibleActivities;

    public AuthViewModel account() {
        if (account == null) account = new AuthViewModel(this);
        return account;
    }

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {
                visibleActivities++;
                if (account != null) account.setForeground(true);
            }
            @Override public void onActivityStopped(Activity activity) {
                visibleActivities--;
                if (account != null && visibleActivities == 0) account.setForeground(false);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    @Override public void onTerminate() {
        if (account != null) account.onCleared();
        super.onTerminate();
    }
}
