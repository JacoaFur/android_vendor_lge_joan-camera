package com.lge.content.pm;
import android.content.Context;
import android.graphics.drawable.Drawable;
public class PackageManagerEx {
    private static PackageManagerEx sInstance;
    PackageManagerEx() {}
    public static synchronized PackageManagerEx getDefault() {
        if (sInstance == null) sInstance = new PackageManagerEx();
        return sInstance;
    }
    public Drawable getIconDrawableAsIconFrameTheme(Context c, Drawable d, String pkg, int user) { return d; }
}
