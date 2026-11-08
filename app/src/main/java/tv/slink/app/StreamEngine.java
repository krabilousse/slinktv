package tv.slink.app;

import android.content.Context;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

final class StreamEngine {
    static String call(Context context, String module, String method, Object... args) {
        synchronized (StreamEngine.class) {
            if (!Python.isStarted()) Python.start(new AndroidPlatform(context.getApplicationContext()));
        }
        return Python.getInstance().getModule(module).callAttr(method, args).toString();
    }
    private StreamEngine() {}
}
