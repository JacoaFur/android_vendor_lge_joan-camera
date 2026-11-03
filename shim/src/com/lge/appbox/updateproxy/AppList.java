package com.lge.appbox.updateproxy;
import java.util.ArrayList;
import java.util.Iterator;
public class AppList implements Iterable<AppInfo> {
    public boolean isSuccess() { return false; }
    public Iterator<AppInfo> iterator() { return new ArrayList<AppInfo>().iterator(); }
}
