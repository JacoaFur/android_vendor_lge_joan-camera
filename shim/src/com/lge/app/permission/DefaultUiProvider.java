package com.lge.app.permission;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.RemoteViews;

public class DefaultUiProvider implements AppWidgetUiProvider, GuideUiProvider {
    private static final long serialVersionUID = 1L;
    public DefaultUiProvider() {}
    public Drawable getAppIcon(Context c) { return c.getApplicationInfo().loadIcon(c.getPackageManager()); }
    public CharSequence getAppName(Context c, String[] perms) { return c.getApplicationInfo().loadLabel(c.getPackageManager()); }
    public CharSequence getDisabledFeatures(Context c, String[] perms) { return ""; }
    public CharSequence getFullMessageForRequestingPermissions(Context c, String[] perms) { return ""; }
    public CharSequence getGuideMessageForAppWidget(Context c, String[] perms) { return ""; }
    public CharSequence getReasonForRequestingPermissions(Context c, String[] perms) { return ""; }
    public RemoteViews getRemoteViews(Context c, String[] perms, PendingIntent pi, CharSequence msg) { return null; }
    public Class<?> getRequestPermissionsActivity() { return RequestPermissionsActivity.class; }
}
