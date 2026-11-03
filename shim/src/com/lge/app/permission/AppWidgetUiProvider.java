package com.lge.app.permission;
import android.app.PendingIntent;
import android.content.Context;
import android.widget.RemoteViews;
public interface AppWidgetUiProvider {
    CharSequence getGuideMessageForAppWidget(Context c, String[] perms);
    RemoteViews getRemoteViews(Context c, String[] perms, PendingIntent pi, CharSequence msg);
}
