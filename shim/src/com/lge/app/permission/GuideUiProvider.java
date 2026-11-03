package com.lge.app.permission;
import android.content.Context;
import android.graphics.drawable.Drawable;
import java.io.Serializable;
public interface GuideUiProvider extends Serializable {
    Drawable getAppIcon(Context c);
    CharSequence getAppName(Context c, String[] perms);
    CharSequence getDisabledFeatures(Context c, String[] perms);
    CharSequence getFullMessageForRequestingPermissions(Context c, String[] perms);
    CharSequence getReasonForRequestingPermissions(Context c, String[] perms);
    Class<?> getRequestPermissionsActivity();
}
