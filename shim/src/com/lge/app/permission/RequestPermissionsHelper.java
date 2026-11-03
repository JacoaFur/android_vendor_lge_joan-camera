package com.lge.app.permission;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import com.lge.os.ShimLogger;

import java.util.ArrayList;
import java.util.Arrays;

public class RequestPermissionsHelper {
    private static final String TAG = "LGPermHelper";
    private static volatile boolean sRequestedOnce = false;
    static { ShimLogger.init(); }

    private RequestPermissionsHelper() {}

    private static boolean isLegacyStoragePerm(String p) {
        return "android.permission.WRITE_EXTERNAL_STORAGE".equals(p)
                || "android.permission.READ_EXTERNAL_STORAGE".equals(p);
    }

    public static boolean hasPermissions(Context ctx, String[] perms) {
        if (perms == null || perms.length == 0) return true;
        boolean allGranted = true;
        StringBuilder sb = new StringBuilder("hasPermissions check: ");
        for (String p : perms) {
            int state = ctx.checkSelfPermission(p);
            sb.append(p).append("=").append(state == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED").append(" ");
            if (state != PackageManager.PERMISSION_GRANTED) {
                // On Android 13+ (SDK 33+), if we already prompted once and CAMERA is granted,
                // do not loop endlessly if the ROM's PermissionController leaves legacy storage denied.
                if (Build.VERSION.SDK_INT >= 33 && isLegacyStoragePerm(p) && sRequestedOnce
                        && ctx.checkSelfPermission("android.permission.CAMERA") == PackageManager.PERMISSION_GRANTED) {
                    sb.append("(ignored after prompt on SDK ").append(Build.VERSION.SDK_INT).append(") ");
                    continue;
                }
                allGranted = false;
            }
        }
        ShimLogger.logI(TAG, sb.toString() + " -> " + allGranted);
        return allGranted;
    }

    /** @return true if a permission request was started (caller should wait), false if nothing to do. */
    public static boolean requestPermissionsIfNeeded(Activity activity, String[] perms, GuideUiProvider ui) {
        ShimLogger.logI(TAG, "requestPermissionsIfNeeded called with perms=" + Arrays.toString(perms));
        if (hasPermissions(activity, perms)) {
            return false;
        }
        ArrayList<String> missing = new ArrayList<String>();
        for (String p : perms) {
            if (activity.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= 33 && isLegacyStoragePerm(p) && sRequestedOnce
                        && activity.checkSelfPermission("android.permission.CAMERA") == PackageManager.PERMISSION_GRANTED) {
                    continue;
                }
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            return false;
        }
        sRequestedOnce = true;
        ShimLogger.logW(TAG, "Requesting missing runtime permissions: " + missing);
        activity.requestPermissions(missing.toArray(new String[0]), 0x4c47 /* 'LG' */);
        return true;
    }
}
