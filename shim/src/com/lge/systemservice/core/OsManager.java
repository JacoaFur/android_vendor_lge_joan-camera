package com.lge.systemservice.core;
import android.os.IBinder;
import com.lge.os.ShimLogger;

public class OsManager {
    static { ShimLogger.init(); }
    public OsManager() {}
    public void goToSleepWithForce(long time, int reason) {
        ShimLogger.logD("OsManager", "goToSleepWithForce(" + time + ", " + reason + ")");
    }
    public void makeNonPersistent(IBinder token) {}
    public void makeNonPersistentUsingPackageName(String pkg) {}
    public IBinder makePersistent(String pkg) { return null; }
    public void setSystemProperty(String key, String value) {
        ShimLogger.logI("OsManager", "setSystemProperty(\"" + key + "\", \"" + value + "\")");
    }
}
