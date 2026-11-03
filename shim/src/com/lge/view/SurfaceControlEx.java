package com.lge.view;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.lge.os.ShimLogger;

/**
 * Replacement for removed Android 9 hidden API android.view.SurfaceControl.screenshot(...).
 * Used by com.lge.camera.util.Utils.getScreenShot() during mode switches, video start,
 * wide-angle lens switching, and front/rear camera switching.
 */
public class SurfaceControlEx {
    private static final String TAG = "SurfaceControlEx";
    static { ShimLogger.init(); }

    public SurfaceControlEx() {}

    public static Bitmap screenshot(Rect sourceCrop, int width, int height, int rotation) {
        return createSafeBitmap(sourceCrop, width, height, rotation);
    }

    public static Bitmap screenshot(Rect sourceCrop, int width, int height,
                                    int minLayer, int maxLayer, boolean useIdentityTransform, int rotation) {
        return createSafeBitmap(sourceCrop, width, height, rotation);
    }

    public static Bitmap screenshot(Rect sourceCrop, int width, int height, boolean useIdentityTransform) {
        return createSafeBitmap(sourceCrop, width, height, 0);
    }

    public static Bitmap screenshot(int width, int height) {
        return createSafeBitmap(null, width, height, 0);
    }

    private static Bitmap createSafeBitmap(Rect sourceCrop, int width, int height, int rotation) {
        int w = width;
        int h = height;
        if ((w <= 0 || h <= 0) && sourceCrop != null) {
            w = sourceCrop.width();
            h = sourceCrop.height();
        }
        if (w <= 0) w = 360;
        if (h <= 0) h = 640;
        if (w < 16) w = 16;
        if (h < 16) h = 16;
        if (w > 1080) w = 1080;
        if (h > 2160) h = 2160;
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(0xFF181818);
            ShimLogger.logI(TAG, "screenshot(crop=" + sourceCrop + ", " + width + "x" + height
                    + ", rot=" + rotation + ") -> synthetic " + w + "x" + h + " ARGB_8888 bitmap");
            return bmp;
        } catch (Throwable t) {
            ShimLogger.logE(TAG, "createSafeBitmap failed", t);
            return Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        }
    }
}
