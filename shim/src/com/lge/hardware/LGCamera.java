package com.lge.hardware;

import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import com.lge.os.ParamDefaults;
import com.lge.os.ShimLogger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stage-1+ shim of LG's framework class com.lge.hardware.LGCamera (LG V30, Android 9).
 */
public class LGCamera {
    public static final String TAG = "LGCamera";
    static { ShimLogger.init(); }

    public static final int CAMERA_META_DATA_FLASH_INDICATOR = 8;
    public static final int CAMERA_META_DATA_HDR_INDICATOR = 4;
    public static final int CAMERA_META_DATA_LG_DUALCAM = 32;
    public static final int CAMERA_META_DATA_LG_LUX_INDEX = 64;
    public static final int CAMERA_META_DATA_MANUAL_MODE_INDICATOR = 16;
    public static final int CAMERA_META_DATA_NR_INDICATOR = 128;

    public static final int CAMERA_MSG_LGE_STATS_DATA = 65536;
    public static final int CAMERA_MSG_LGE_META_DATA = 131072;
    public static final int CAMERA_MSG_LGE_OBT_DATA = 16384;
    public static final int CAMERA_MSG_LGE_PROXY_DATA = 524288;
    public static final int CAMERA_MSG_LGE_BW_DATA = 1048576;
    public static final int CAMERA_MSG_LGE_FINGER_DETECTION_DATA = 1179648;
    public static final int CAMERA_MSG_LGE_DUAL_CAM_DATA = 1310720;
    public static final int CAMERA_MSG_LGE_EVCALLBACK_DATA = 1572864;
    public static final int CAMERA_MSG_LGE_CINEZOOM_STATE = 2097152;
    public static final int CAMERA_MSG_LGE_OUTFOCUS_DATA = 4194304;

    private final Camera mCamera;
    private final int mCameraId;
    private boolean mWarned;
    private static volatile boolean sOpenLegacyUnsupported = false;
    private static final Set<Integer> sLoggedCameras = new HashSet<Integer>();

    private CameraDataCallback mCameraDataCallback;
    private ProxyDataListener mProxyDataListener;
    private ObtDataListener mObtDataListener;
    private BwDataListener mBwDataListener;
    private FingerDetectionDataListener mFingerDetectionDataListener;
    private EVCallbackListener mEVCallbackListener;
    private CineZoomStateListener mCineZoomStateListener;
    private CameraMetaDataCallback mHdrMetaDataCallback, mFlashMetaDataCallback, mNRMetaDataCallback,
            mManualModeMetaDataCallback, mDualCamMetaDataCallback, mLuxIndexMetaDataCallback,
            mOutfocusMetaDataCallback;

    private final Handler mCineZoomHandler = new Handler(Looper.getMainLooper());
    private Runnable mCineZoomRunnable;
    private int mCineZoomDir = 0; // 1 = zoom in, 2 = zoom out, 0 = stopped
    private int mCineZoomDelayMs = 60;

    public LGCamera(int cameraId) {
        ShimLogger.logI(TAG, "[LGSF] LGCamera(cameraId=" + cameraId + ") via Camera.open");
        mCamera = openWithFallback(cameraId, -1);
        mCameraId = cameraId;
    }

    public LGCamera(int cameraId, int halVersion) {
        ShimLogger.logI(TAG, "[LGSF] LGCamera(cameraId=" + cameraId + ", halVersion=0x" + Integer.toHexString(halVersion) + ")");
        mCamera = openWithFallback(cameraId, halVersion);
        mCameraId = cameraId;
    }

    private static Camera openWithFallback(int cameraId, int halVersion) {
        int count = 3;
        try {
            count = Camera.getNumberOfCameras();
        } catch (Throwable t) {
            ShimLogger.logE(TAG, "Camera.getNumberOfCameras() failed", t);
        }

        int targetId = ParamDefaults.remapCameraId(cameraId);

        // Give cameraserver / mm-qcamera-daemon time to finish tearing down the previous camera session
        ParamDefaults.awaitCameraSettleBeforeOpen(targetId);

        // 1. Try openLegacy only once per process; Android 15 cameraserver rejects HAL1 (0x100)
        if (halVersion >= 0 && !sOpenLegacyUnsupported) {
            try {
                Method m = Camera.class.getMethod("openLegacy", int.class, int.class);
                Camera c = (Camera) m.invoke(null, targetId, halVersion);
                if (c != null) {
                    ShimLogger.logI(TAG, "openLegacy(id=" + targetId + ", hal=0x" + Integer.toHexString(halVersion) + ") SUCCEEDED!");
                    ParamDefaults.onCameraOpened(targetId, c);
                    logInitialParameters(targetId, c);
                    return c;
                }
            } catch (InvocationTargetException e) {
                sOpenLegacyUnsupported = true;
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                ShimLogger.logI(TAG, "openLegacy(hal=0x" + Integer.toHexString(halVersion)
                        + ") unsupported on Android 15 (" + cause.getMessage() + "); using standard Camera.open(" + targetId + ")");
            } catch (Throwable t) {
                sOpenLegacyUnsupported = true;
            }
        }

        // 2. Standard Camera.open(targetId) with retries to handle mm-qcamera-daemon async close latency
        Throwable lastErr = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                Camera c = Camera.open(targetId);
                ShimLogger.logI(TAG, "Camera.open(id=" + targetId + " [req=" + cameraId + "], attempt=" + attempt + ") SUCCEEDED!");
                ParamDefaults.onCameraOpened(targetId, c);
                logInitialParameters(targetId, c);
                return c;
            } catch (Throwable t) {
                lastErr = t;
                ShimLogger.logW(TAG, "Camera.open(id=" + targetId + " [req=" + cameraId + "], attempt=" + attempt + "/4) failed: " + t.getMessage());
                if (attempt < 4) {
                    try { Thread.sleep(220); } catch (InterruptedException ignored) {}
                }
            }
        }

        ShimLogger.logE(TAG, "Camera.open(id=" + targetId + ") FAILED after 4 attempts (count=" + count + ")", lastErr);
        if (targetId != 0 && count > 0 && !ParamDefaults.isAnyCameraOpen()) {
            try {
                ShimLogger.logW(TAG, "Attempting emergency fallback Camera.open(0) for failed id=" + targetId);
                Camera c = Camera.open(0);
                ParamDefaults.onCameraOpened(0, c);
                logInitialParameters(0, c);
                return c;
            } catch (Throwable t2) {
                ShimLogger.logE(TAG, "Emergency fallback Camera.open(0) also failed", t2);
            }
        }
        if (lastErr instanceof RuntimeException) throw (RuntimeException) lastErr;
        throw new RuntimeException(lastErr);
    }

    private static void logInitialParameters(int cameraId, Camera c) {
        if (c == null) return;
        boolean first;
        synchronized (sLoggedCameras) { first = sLoggedCameras.add(cameraId); }
        if (!first) return;
        try {
            Camera.Parameters p = c.getParameters();
            if (p != null) {
                String flat = p.flatten();
                ShimLogger.logI(TAG, "Camera " + cameraId + " initial parameters length=" + (flat != null ? flat.length() : 0)
                        + " [focal-length=" + p.get("focal-length")
                        + ", horizontal-view-angle=" + p.get("horizontal-view-angle")
                        + ", focus-mode-values=" + p.get("focus-mode-values")
                        + ", flash-mode-values=" + p.get("flash-mode-values")
                        + ", preview-fps-range-values=" + p.get("preview-fps-range-values") + "]");
            }
        } catch (Throwable t) {
            ShimLogger.logE(TAG, "Failed reading initial parameters for camera " + cameraId, t);
        }
    }

    static String sysprop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable t) {
            return "";
        }
    }

    public Camera getCamera() { return mCamera; }

    public LGParameters getLGParameters() { return new LGParameters(); }

    private void noNative(String what) {
        if (!mWarned) {
            mWarned = true;
            ShimLogger.logW(TAG, "LG native camera callbacks are stubbed (stage 1 shim): " + what);
        }
    }

    public void setHistogramMode(CameraDataCallback cb) { mCameraDataCallback = cb; noNative("histogram"); }
    public void setProxyDataListener(ProxyDataListener l) { mProxyDataListener = l; noNative("proxy"); }
    public void setObtDataListener(ObtDataListener l) { mObtDataListener = l; noNative("obt"); }
    public void setBwDataListener(BwDataListener l) { mBwDataListener = l; noNative("bw"); }
    public void setFingerDetectionDataListener(FingerDetectionDataListener l) { mFingerDetectionDataListener = l; noNative("finger"); }
    public void setEVCallbackDataListener(EVCallbackListener l) { mEVCallbackListener = l; noNative("ev"); }
    public void setCineZoomStateListener(CineZoomStateListener l) {
        mCineZoomStateListener = l;
        if (l == null) {
            stopCineZoomStepper();
        }
    }
    public void setHdrMetadataCb(CameraMetaDataCallback cb) { mHdrMetaDataCallback = cb; noNative("hdr meta"); }
    public void setFlashMetadataCb(CameraMetaDataCallback cb) { mFlashMetaDataCallback = cb; noNative("flash meta"); }
    public void setNRMetadataCb(CameraMetaDataCallback cb) { mNRMetaDataCallback = cb; noNative("nr meta"); }
    public void setManualCameraMetadataCb(CameraMetaDataCallback cb) { mManualModeMetaDataCallback = cb; noNative("manual meta"); }
    public void setDualCamMetadataCb(CameraMetaDataCallback cb) { mDualCamMetaDataCallback = cb; noNative("dualcam meta"); }
    public void setLuxIndexMetadataCb(CameraMetaDataCallback cb) { mLuxIndexMetaDataCallback = cb; noNative("lux meta"); }
    public void setOutfocusMetadataCb(CameraMetaDataCallback cb) { mOutfocusMetaDataCallback = cb; noNative("outfocus meta"); }

    private void stopCineZoomStepper() {
        mCineZoomDir = 0;
        if (mCineZoomRunnable != null) {
            mCineZoomHandler.removeCallbacks(mCineZoomRunnable);
            mCineZoomRunnable = null;
        }
    }

    private void notifyCineZoomState(int state) {
        final CineZoomStateListener l = mCineZoomStateListener;
        if (l == null) return;
        final CineZoomData d = new CineZoomData();
        d.state = state;
        d.left = 0;
        d.top = 0;
        d.width = 1920;
        d.height = 1080;
        try {
            l.onCineZoomListen(d);
        } catch (Throwable t) {
            ShimLogger.logW(TAG, "onCineZoomListen error: " + t);
        }
    }

    private void handleCineZoomCommand(int cmd, float left, float top, float width, float height, int speed) {
        if (mCamera == null) return;
        // speed from CineZoomManagerBase: 100 (fast), 200 (medium), 300 (slow)
        if (speed <= 120) mCineZoomDelayMs = 35;
        else if (speed <= 220) mCineZoomDelayMs = 65;
        else mCineZoomDelayMs = 105;

        if (cmd == 3) {
            // Pause Point Zoom at current level
            stopCineZoomStepper();
            return;
        }
        if (cmd == 4) {
            // Reset Point Zoom to 0
            stopCineZoomStepper();
            try {
                Camera.Parameters p = ParamDefaults.safeGetParameters(mCamera);
                if (p != null && p.isZoomSupported()) {
                    p.setZoom(0);
                    ParamDefaults.safeSetParameters(mCamera, p);
                }
            } catch (Throwable ignored) {}
            notifyCineZoomState(2);
            return;
        }
        if (cmd != 1 && cmd != 2) {
            return;
        }

        mCineZoomDir = cmd;
        if (mCineZoomRunnable != null) {
            mCineZoomHandler.removeCallbacks(mCineZoomRunnable);
        }
        mCineZoomRunnable = new Runnable() {
            @Override
            public void run() {
                if (mCamera == null || mCineZoomDir == 0) return;
                try {
                    Camera.Parameters p = ParamDefaults.safeGetParameters(mCamera);
                    if (p == null || !p.isZoomSupported()) {
                        stopCineZoomStepper();
                        return;
                    }
                    int cur = p.getZoom();
                    int max = p.getMaxZoom();
                    int targetMax = Math.max(1, (max * 3) / 4);
                    if (mCineZoomDir == 1) {
                        if (cur < targetMax) {
                            p.setZoom(cur + 1);
                            ParamDefaults.safeSetParameters(mCamera, p);
                            notifyCineZoomState(3);
                            mCineZoomHandler.postDelayed(this, mCineZoomDelayMs);
                        } else {
                            stopCineZoomStepper();
                            notifyCineZoomState(1);
                        }
                    } else if (mCineZoomDir == 2) {
                        if (cur > 0) {
                            p.setZoom(cur - 1);
                            ParamDefaults.safeSetParameters(mCamera, p);
                            notifyCineZoomState(3);
                            mCineZoomHandler.postDelayed(this, mCineZoomDelayMs);
                        } else {
                            stopCineZoomStepper();
                            notifyCineZoomState(2);
                        }
                    }
                } catch (Throwable t) {
                    ShimLogger.logW(TAG, "CineZoom step error: " + t);
                    stopCineZoomStepper();
                }
            }
        };
        mCineZoomHandler.post(mCineZoomRunnable);
    }

    public interface CameraDataCallback { void onCameraData(int[] data, Camera camera); }
    public interface CameraMetaDataCallback { void onCameraMetaData(byte[] data, Camera camera); }
    public interface CineZoomStateListener { void onCineZoomListen(CineZoomData data); }
    public interface EVCallbackListener { void onDataListen(EVCallbackData data, Camera camera); }
    public interface FingerDetectionDataListener { void onDataListen(FingerDetectionData data, Camera camera); }
    public interface ObtDataListener { void onDataListen(ObtData data, Camera camera); }
    public interface ProxyDataListener { void onDataListen(ProxyData data, Camera camera); }
    public interface BwDataListener {
        void onDataListen(BwData data, Camera camera);
        void onDataListen(byte[] data, Camera camera);
    }

    public static class BwData { public byte[] edge; public BwData() {} }
    public static class CineZoomData { public int height, left, state, top, width; public CineZoomData() {} }
    public static class EVCallbackData { public byte[] result; public EVCallbackData() {} }
    public static class FingerDetectionData { public int fingerAxisX, fingerAxisY; public FingerDetectionData() {} }
    public static class ObtData { public int height, left, top, width; public ObtData() {} }
    public static class ProxyData { public int amb, conv, raw, sig, val; public ProxyData() {} }

    public class LGParameters {
        public static final String CAPTURE_MODE_BURST_SHOT = "burstshot";
        public static final String CAPTURE_MODE_PANORAMA_SHOT = "panoramashot";
        public static final String KEY_BACKLIGHT_CONDITION = "backlight-condition";
        public static final String KEY_BEAUTY = "beautyshot";
        public static final String KEY_CINEZOOM_PARAM = "cinezoom-parameters";
        public static final String KEY_FLASH_MODE = "flash-mode";
        public static final String KEY_FLASH_STATUS = "flash-status";
        public static final String KEY_FOCUS_MODE_OBJECT_TRACKING = "object-tracking";
        public static final String KEY_FOCUS_PEAKING = "focus-peaking";
        public static final String KEY_HDR_MODE = "hdr-mode";
        public static final String KEY_LG_MULTI_WINDOW_FOCUS_AREA = "multi-window-focus-area";
        public static final String KEY_LUMINANCE_CONDITION = "luminance-condition";
        public static final String KEY_MAX_ZOOM = "max-zoom";
        public static final String KEY_PANORAMA = "panorama-shot";
        public static final String KEY_QC_SCENE_DETECT = "scene-detect";
        public static final String KEY_SUPERZOOM = "superzoom";
        public static final String KEY_ZOOM = "zoom";
        public static final String SCENE_MODE_AUTO = "auto";
        public static final String SCENE_MODE_NIGHT = "night";

        private Camera.Parameters mParameters;
        private String mShotMode, mIsBeauty, mHDRstatus, mFlashStatus, mCurrentFlash,
                mLuminanceCondition, mBacklightCondition;
        private boolean mIsCurrentFlash, mIsFlashAuto, mIsFlashOff, mIsFlashOn, mIsHDRAuto, mIsHDROff,
                mIsHDROn, mIsHighBackLight, mIsLuminanceEis, mIsLuminanceHigh, mIsSuperZoomEnabled;
        private int mMaxZoom, mSuperZoomStatus;

        LGParameters() {
            if (mCamera == null) {
                ShimLogger.logE(TAG, "Camera hardware is not opened! open camera first.", null);
                return;
            }
            mParameters = ParamDefaults.safeGetParameters(mCamera);
            if (mParameters == null) ShimLogger.logE(TAG, "didn't get native parameters.", null);
        }

        private void safeSetParameters(Camera.Parameters p) {
            ParamDefaults.safeSetParameters(mCamera, p);
        }

        public Camera.Parameters getParameters() { return mParameters; }

        public void setParameters(Camera.Parameters p) {
            mParameters = p;
            safeSetParameters(mParameters);
        }

        private boolean getParamStatus(String value, String expected) {
            return value != null && expected != null && expected.equals(value);
        }

        public void setCineZoomParameters(int a, float b, float c, float d, float e, int f, int g) {
            if (mParameters == null) return;
            mParameters.set(KEY_CINEZOOM_PARAM, a + "," + b + "," + c + "," + d + "," + e + "," + f + "," + g);
            safeSetParameters(mParameters);
            handleCineZoomCommand(a, b, c, d, e, f);
        }

        public void setCineZoomParameters(Camera.Parameters p, int a, float b, float c, float d, float e, int f, int g) {
            mParameters = p;
            setCineZoomParameters(a, b, c, d, e, f, g);
        }

        public List<Camera.Area> getMultiWindowFocusAreas() {
            try {
                if (mParameters == null) return null;
                String s = mParameters.get(KEY_LG_MULTI_WINDOW_FOCUS_AREA);
                if (s == null || s.equals("(0,0,0,0,0)")) return null;
                ArrayList<Camera.Area> out = new ArrayList<Camera.Area>();
                int depth = 0, start = -1;
                for (int i = 0; i < s.length(); i++) {
                    char ch = s.charAt(i);
                    if (ch == '(') { if (depth++ == 0) start = i + 1; }
                    else if (ch == ')' && --depth == 0 && start >= 0) {
                        String[] t = s.substring(start, i).split(",");
                        if (t.length == 5) {
                            out.add(new Camera.Area(new Rect(Integer.parseInt(t[0].trim()),
                                    Integer.parseInt(t[1].trim()), Integer.parseInt(t[2].trim()),
                                    Integer.parseInt(t[3].trim())), Integer.parseInt(t[4].trim())));
                        }
                    }
                }
                return out.isEmpty() ? null : out;
            } catch (Exception e) {
                ShimLogger.logE(TAG, "getMultiWindowFocusAreas error", e);
                return null;
            }
        }

        public Camera.Parameters setSuperZoom(Camera.Parameters p) {
            mParameters = p;
            if (mParameters == null) return null;
            String sz = mParameters.get(KEY_SUPERZOOM);
            if (sz == null) {
                mIsSuperZoomEnabled = false;
                safeSetParameters(mParameters);
                return mParameters;
            }
            updateSuperZoomState();
            mParameters.set(KEY_SUPERZOOM, mIsSuperZoomEnabled ? "on" : "off");
            safeSetParameters(mParameters);
            return mParameters;
        }

        private void updateSuperZoomState() {
            String zoom = mParameters.get(KEY_ZOOM);
            String maxZoom = mParameters.get(KEY_MAX_ZOOM);
            mSuperZoomStatus = zoom == null ? 0 : mParameters.getInt(KEY_ZOOM);
            mMaxZoom = maxZoom == null ? 0 : mParameters.getInt(KEY_MAX_ZOOM);
            if (mMaxZoom > 900) mIsSuperZoomEnabled = mSuperZoomStatus > 673;
            else if (mMaxZoom > 100) mIsSuperZoomEnabled = mSuperZoomStatus > 439;
            else mIsSuperZoomEnabled = mSuperZoomStatus > 54;
        }

        public Camera.Parameters setNightandHDRorAuto(Camera.Parameters p, String shotMode, boolean useDefault) {
            mParameters = p;
            if (mParameters == null) return null;
            mShotMode = (shotMode != null) ? shotMode : "mode_normal";
            final String beauty = "mode_beauty";

            if (mShotMode.equals("mode_normal")) mParameters.set(KEY_PANORAMA, "0");

            String chip = com.lge.os.PropertyUtils.getInstance().get(1804 /* RO_CHIP_VENDOR */, "");
            if (chip.equals("mtk")) {
                mParameters.set("mtk-cam-mode", "1");
                mParameters.set(CAPTURE_MODE_BURST_SHOT, mShotMode.equals("mode_burst") ? "1" : "0");
                mParameters.set(CAPTURE_MODE_PANORAMA_SHOT, mShotMode.contains("mode_panorama") ? "1" : "0");
            }
            if (sysprop("ro.board.platform").contains("msm")) {
                mParameters.set(CAPTURE_MODE_BURST_SHOT, mShotMode.equals("mode_burst") ? "1" : "0");
            }
            if (useDefault) {
                setDefaultParam();
                safeSetParameters(mParameters);
                return mParameters;
            }

            updateSuperZoomState();
            String sz = mParameters.get(KEY_SUPERZOOM);
            if (sz != null && sz.equals("off")) mIsSuperZoomEnabled = false;

            mLuminanceCondition = mParameters.get(KEY_LUMINANCE_CONDITION);
            mIsLuminanceHigh = getParamStatus(mLuminanceCondition, "high");
            mIsLuminanceEis = getParamStatus(mLuminanceCondition, "eis");
            mBacklightCondition = mParameters.get(KEY_BACKLIGHT_CONDITION);
            mIsHighBackLight = getParamStatus(mBacklightCondition, "high");
            mFlashStatus = mParameters.get(KEY_FLASH_MODE);
            mIsFlashOff = getParamStatus(mFlashStatus, "off");

            if (!mIsHighBackLight && mIsLuminanceHigh && !mIsSuperZoomEnabled && mIsFlashOff
                    && mShotMode.equals("mode_normal")) {
                safeSetParameters(mParameters);
                return mParameters;
            }

            mHDRstatus = mParameters.get(KEY_HDR_MODE);
            mCurrentFlash = mParameters.get(KEY_FLASH_STATUS);
            if (mShotMode.length() > beauty.length()) mIsBeauty = mShotMode.substring(0, beauty.length());
            mIsFlashOn = getParamStatus(mFlashStatus, "on");
            mIsFlashAuto = getParamStatus(mFlashStatus, "auto");
            mIsHDROff = getParamStatus(mHDRstatus, "0");
            mIsHDROn = getParamStatus(mHDRstatus, "1");
            mIsHDRAuto = getParamStatus(mHDRstatus, "2");
            mIsCurrentFlash = getParamStatus(mCurrentFlash, "on");

            if (!mIsHighBackLight && mIsLuminanceHigh && !mIsSuperZoomEnabled && mIsFlashOff
                    && mShotMode.equals("mode_normal")) {
                // no-op
            } else {
                setLGParameters();
            }
            safeSetParameters(mParameters);
            mParameters.remove(KEY_PANORAMA);
            return mParameters;
        }

        private void setLGParameters() {
            if (!mShotMode.equals("mode_normal")) {
                if (mShotMode.equals("mode_burst")) {
                    setDefaultParam();
                } else if ("mode_beauty".equals(mIsBeauty) || mShotMode.equals("mode_front_outfocus")) {
                    checkLuminanceStatus();
                } else if (mShotMode.equals("mode_panorama")) {
                    mParameters.set(KEY_PANORAMA, "1");
                } else if (mShotMode.equals("mode_panorama_preview")) {
                    mParameters.set(KEY_PANORAMA, "1");
                } else if (mShotMode.equals("mode_panorama_saving")) {
                    mParameters.set(KEY_PANORAMA, "2");
                }
                return;
            }
            mParameters.set(KEY_PANORAMA, "0");
            checkFlashStatus();
        }

        private void checkFlashStatus() {
            if (mIsFlashOn || (mIsFlashAuto && mIsCurrentFlash)) setDefaultParam();
            else checkLuminanceStatus();
        }

        private void checkLuminanceStatus() {
            if (mIsLuminanceHigh || mIsLuminanceEis) {
                checkHDRStatus();
            } else {
                mParameters.set(KEY_HDR_MODE, "0");
                checkSuperZoomStatus();
            }
        }

        private void checkHDRStatus() {
            if (mIsHDRAuto) {
                checkBacklightStatus();
                return;
            }
            if (mIsHDROn) {
                if (mIsSuperZoomEnabled) mParameters.set(KEY_HDR_MODE, "0");
                else setHDROnParam();
            } else {
                mParameters.set(KEY_HDR_MODE, "0");
            }
            checkSceneStatus();
        }

        private void checkBacklightStatus() {
            if (mIsHighBackLight) {
                if (mIsSuperZoomEnabled) mParameters.set(KEY_HDR_MODE, "0");
                else setHDROnParam();
            } else {
                mParameters.set(KEY_HDR_MODE, "0");
            }
            checkSceneStatus();
        }

        private void checkSceneStatus() {
            if (mIsLuminanceEis) checkSuperZoomStatus();
            else mParameters.setSceneMode(SCENE_MODE_AUTO);
        }

        private void checkSuperZoomStatus() {
            if (mIsSuperZoomEnabled) {
                mParameters.set(KEY_SUPERZOOM, "on");
                mParameters.setSceneMode(SCENE_MODE_AUTO);
            } else {
                mParameters.set(KEY_SUPERZOOM, "off");
                mParameters.setSceneMode(SCENE_MODE_NIGHT);
            }
        }

        private void setDefaultParam() {
            mParameters.set(KEY_SUPERZOOM, "off");
            mParameters.set(KEY_HDR_MODE, "0");
            mParameters.setSceneMode(SCENE_MODE_AUTO);
        }

        private void setHDROnParam() {
            mParameters.set(KEY_HDR_MODE, "1");
            mParameters.set(KEY_SUPERZOOM, "off");
            mParameters.setSceneMode(SCENE_MODE_AUTO);
        }
    }
}
