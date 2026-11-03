package com.lge.appbox.updateproxy.manager;
import com.lge.appbox.updateproxy.AppList;
public abstract class ISelfUpdateServiceCallback {
    public ISelfUpdateServiceCallback() {}
    public abstract void onDownloadStatus(String pkg, int a, int b, long c, long d);
    public abstract void onInstallStatus(String pkg, int a, int b);
    public abstract void onReceiveAppList(AppList list);
}
