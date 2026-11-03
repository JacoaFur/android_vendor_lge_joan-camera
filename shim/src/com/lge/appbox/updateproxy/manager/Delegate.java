package com.lge.appbox.updateproxy.manager;
import com.lge.appbox.updateproxy.AppInfo;
import com.lge.appbox.updateproxy.RequestForm;
public class Delegate {
    public void downloadAndInstallAppAsync(AppInfo info) {}
    public void registeSelfUpdateCallback(ISelfUpdateServiceCallback cb) {}
    public void retrieveAppListAsync(RequestForm form) {}
    public void unregisteSelfUpdateCallback(ISelfUpdateServiceCallback cb) {}
}
