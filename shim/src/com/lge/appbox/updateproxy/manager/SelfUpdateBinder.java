package com.lge.appbox.updateproxy.manager;
import android.content.Context;
/** LG AppBox self-update: never connects, so the camera/octopus update paths stay dormant. */
public class SelfUpdateBinder {
    public interface ISelfupdateConnection {}
    private static SelfUpdateBinder sInstance;
    private SelfUpdateBinder() {}
    public static synchronized SelfUpdateBinder getInstance(Context c) {
        if (sInstance == null) sInstance = new SelfUpdateBinder();
        return sInstance;
    }
    public boolean bind(ISelfupdateConnection connection) { return false; }
}
