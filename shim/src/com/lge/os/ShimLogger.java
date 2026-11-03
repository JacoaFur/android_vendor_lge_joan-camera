package com.lge.os;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Central diagnostic logger and uncaught exception trapper for the LG Camera stage-1 shim.
 */
public final class ShimLogger {
    public static final String TAG = "LGShim";
    public static final String CRASH_TAG = "LGShimCrash";
    private static volatile boolean sInitialized = false;

    private static final String[] LOG_PATHS = new String[] {
        "/data/user/0/com.lge.camera/files/lgcamera_shim.log",
        "/sdcard/Download/lgcamera_shim.log",
        "/data/local/tmp/lgcamera_shim.log"
    };

    private ShimLogger() {}

    public static synchronized void init() {
        if (sInitialized) return;
        sInitialized = true;
        try {
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        e.printStackTrace(pw);
                        String trace = sw.toString();
                        String msg = "=== UNCAUGHT EXCEPTION in thread [" + t.getName() + "] ===\n" + trace;
                        Log.e(CRASH_TAG, msg);
                        writeToFile(msg);
                    } catch (Throwable ignored) {}
                    if (prev != null) {
                        prev.uncaughtException(t, e);
                    }
                }
            });
            logI(TAG, "ShimLogger initialized (pid=" + android.os.Process.myPid()
                    + ", uid=" + android.os.Process.myUid()
                    + ", sdk=" + android.os.Build.VERSION.SDK_INT + ")");
        } catch (Throwable t) {
            Log.e(TAG, "ShimLogger init error", t);
        }
    }

    public static String getCallerInfo() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 3; i < stack.length; i++) {
                String cls = stack[i].getClassName();
                if (!cls.startsWith("com.lge.os.ShimLogger")
                        && !cls.startsWith("com.lge.os.PropertyUtils")
                        && !cls.startsWith("com.lge.media.CamcorderProfileEx")) {
                    String shortCls = cls.startsWith("com.lge.camera.") ? cls.substring("com.lge.camera.".length()) : cls;
                    return shortCls + "." + stack[i].getMethodName() + ":" + stack[i].getLineNumber();
                }
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }

    public static void logD(String subTag, String msg) {
        init();
        String line = "[" + subTag + "] " + msg;
        Log.d(TAG, line);
        writeToFile("D/" + line);
    }

    public static void logI(String subTag, String msg) {
        init();
        String line = "[" + subTag + "] " + msg;
        Log.i(TAG, line);
        writeToFile("I/" + line);
    }

    public static void logW(String subTag, String msg) {
        init();
        String line = "[" + subTag + "] " + msg;
        Log.w(TAG, line);
        writeToFile("W/" + line);
    }

    public static void logE(String subTag, String msg, Throwable t) {
        init();
        String trace = "";
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            trace = "\n" + sw.toString();
        }
        String line = "[" + subTag + "] " + msg + trace;
        Log.e(TAG, line);
        writeToFile("E/" + line);
    }

    private static synchronized void writeToFile(String text) {
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        byte[] data = (ts + " " + text + "\n").getBytes();
        for (String path : LOG_PATHS) {
            try {
                File f = new File(path);
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                FileOutputStream fos = new FileOutputStream(f, true);
                fos.write(data);
                fos.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
