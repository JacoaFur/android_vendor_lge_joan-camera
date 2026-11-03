package com.lge.os;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;
import android.widget.Toast;
import com.lge.media.CamcorderProfileEx;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Central compatibility hooks injected into AiXAiCam.apk via tools/patch_params1.sh:
 * 1. Parameters1.get(String) -> ParamDefaults.get(Camera.Parameters, String)
 * 2. Camera.setParameters(Parameters) -> ParamDefaults.safeSetParameters(Camera, Camera.Parameters)
 * 3. Camera.getParameters() -> ParamDefaults.safeGetParameters(Camera)
 * 4. Camera.release() -> ParamDefaults.safeReleaseCamera(Camera)
 * 5. Camera.getCameraInfo(int, CameraInfo) -> ParamDefaults.getCameraInfo(int, CameraInfo)
 * 6. Camera$Parameters.setExposureCompensation(int) -> ParamDefaults.setExposureCompensation(Camera.Parameters, int)
 * 7. CamcorderProfile.get(int, int) -> ParamDefaults.getCamcorderProfile(int, int)
 * 8. Activity.startActivity(Intent) -> ParamDefaults.safeStartActivity(Activity, Intent)
 * 9. Resources.getBoolean / getInteger / getDimension* / getDrawable -> safe fallbacks for missing lge-res (0x02 / type 0x7)
 * 10. ContentResolver.insert / update / query / delete -> strips LG-proprietary MediaStore columns (camera_mode, burst_id, etc.)
 *     rejected by Android 11-15 SQLiteQueryBuilder.enforceStrictColumns
 * 11. ConfigurationParsingUtil.getConfigFilePath & self-contained APK LUT/config asset extraction for rootless/sideloaded installs
 * 12. FilmEmulatorRendererPreview.calcFragCoordForCrop(float, float) -> center-crops HAL preview textures when aspect ratio differs
 *     from the requested viewport (fixes squished Front Camera preview in 18:9 / 1:1 modes)
 */
public final class ParamDefaults {
    private static final String TAG = "ParamDefaults";
    private static final Map<String, String> DEFAULTS = new HashMap<String, String>();
    private static final Set<String> sSeen = new HashSet<String>();
    private static final Set<Integer> sSeenRes = new HashSet<Integer>();
    private static final Map<Camera, Camera.Parameters> sCachedParams = new WeakHashMap<Camera, Camera.Parameters>();
    private static final Map<Camera, String> sLastAppliedFocusZone = new WeakHashMap<Camera, String>();
    private static final Map<Camera, Integer> sOpenCameras = new WeakHashMap<Camera, Integer>();

    private static volatile boolean sLoggedCameraTable = false;
    private static volatile boolean sSwapCam1And2 = false;
    private static volatile Camera sActiveCamera = null;
    private static volatile int sActiveCameraId = -1;
    private static volatile int sJogZoomSpeed = 0;
    private static volatile long sLastReleaseTimeMs = 0L;
    private static volatile int sUserEvIndex = 0;
    private static volatile boolean sAssetsChecked = false;
    private static volatile float sRequestedPreviewAspect = 1.3333334f;
    private static volatile float sActualPreviewAspect = 1.3333334f;

    // LG-proprietary or read-only MediaStore columns rejected by Android 11-15 MediaProvider enforceStrictColumns
    private static final Set<String> LG_MEDIA_COLUMNS = new HashSet<String>(Arrays.asList(
            "camera_mode",
            "burst_id",
            "project_id",
            "video_datetaken",
            "storage_type",
            "bg_camera_mode",
            "protection",
            "is_favorite",
            "group_id",
            "rel_path",
            "use_panorama_viewer",
            "projection_type",
            "cropped_area_image_width_pixels",
            "cropped_area_image_height_pixels",
            "full_pano_width_pixels",
            "full_pano_height_pixels",
            "cropped_area_left_pixels",
            "cropped_area_top_pixels",
            "initial_horizontal_fov_degrees",
            "format",
            "mini_thumb_magic",
            "MiniThumbMagic"
    ));

    // Minimal guaranteed-safe MediaStore columns across all Android 11-15 ROMs for fallback retry
    private static final Set<String> CORE_MEDIA_COLUMNS = new HashSet<String>(Arrays.asList(
            "_data",
            "_display_name",
            "title",
            "mime_type",
            "datetaken",
            "date_added",
            "date_modified",
            "orientation",
            "_size",
            "width",
            "height",
            "duration",
            "resolution",
            "description",
            "latitude",
            "longitude"
    ));

    static {
        ShimLogger.init();
        // CameraDeviceUtils.isVideoHDRSupported() -> boolean[] via value.split(","); NPE if null.
        DEFAULTS.put("video-hdr-supported", "0,0,0,0");
        // Video live snapshot size list queried when starting video recording.
        DEFAULTS.put("supported-live-snapshot-sizes", "3840x2160,1920x1080,1280x720");

        // LG Manual Camera & Manual Video mode parameter defaults on LineageOS 22.2 HAL1 wrapper:
        DEFAULTS.put("shutter-speed", "0");
        DEFAULTS.put("lg-iso", "auto");
        DEFAULTS.put("lg-wb", "0");
        DEFAULTS.put("lg-manual-mode-reset", "0");
        DEFAULTS.put("manualfocus_step", "-1");
        ensureBundledAssets();
    }

    private ParamDefaults() {}

    // ---- Self-Contained APK Asset Extraction (allows slim module & rootless APK sideloading) ----

    public static void ensureBundledAssets() {
        if (sAssetsChecked) return;
        synchronized (ParamDefaults.class) {
            if (sAssetsChecked) return;
            sAssetsChecked = true;
            try {
                File baseDir = new File("/data/data/com.lge.camera/files");
                if (!baseDir.exists() && !baseDir.mkdirs()) {
                    baseDir = new File("/data/user/0/com.lge.camera/files");
                    baseDir.mkdirs();
                }
                ClassLoader cl = ParamDefaults.class.getClassLoader();
                if (cl == null) return;

                File marker = new File(baseDir, ".lgcamera_v22_luts");
                File cfgOut = new File(baseDir, "camera_config.xml");
                if (!marker.exists() || !cfgOut.isFile() || cfgOut.length() == 0) {
                    extractResourceToFile(cl, "assets/lgcamera/camera_config.xml", cfgOut);
                }
                if (marker.exists()) return;

                InputStream manifestIn = cl.getResourceAsStream("assets/lgcamera/manifest.txt");
                if (manifestIn == null) return;
                BufferedReader br = new BufferedReader(new InputStreamReader(manifestIn, "UTF-8"));
                String line;
                int count = 0;
                while ((line = br.readLine()) != null) {
                    String rel = line.trim();
                    if (rel.isEmpty() || rel.startsWith("#")) continue;
                    File outFile = new File(baseDir, rel);
                    if (!outFile.isFile() || outFile.length() == 0 || "camera_config.xml".equals(rel)) {
                        if (extractResourceToFile(cl, "assets/lgcamera/" + rel, outFile)) {
                            count++;
                        }
                    }
                }
                br.close();
                new FileOutputStream(marker).close();
                if (count > 0) {
                    ShimLogger.logI(TAG, "Extracted " + count + " bundled camera config/LUT asset(s) to " + baseDir.getAbsolutePath());
                }
            } catch (Throwable t) {
                ShimLogger.logW(TAG, "ensureBundledAssets note: " + t.getMessage());
            }
        }
    }

    private static boolean extractResourceToFile(ClassLoader cl, String resPath, File outFile) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = cl.getResourceAsStream(resPath);
            if (in == null) return false;
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            out = new FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    public static String getConfigFilePath(Context ctx) {
        return getConfigFilePath();
    }

    public static String getConfigFilePath() {
        ensureBundledAssets();
        String[] candidates = new String[] {
                "/data/data/com.lge.camera/files/camera_config.xml",
                "/data/user/0/com.lge.camera/files/camera_config.xml",
                "/product/etc/camera_config.xml",
                "/system/etc/camera_config.xml"
        };
        for (String path : candidates) {
            try {
                File f = new File(path);
                if (f.isFile() && f.length() > 0) {
                    ShimLogger.logI(TAG, "getConfigFilePath -> " + path);
                    return path;
                }
            } catch (Throwable ignored) {}
        }
        ShimLogger.logW(TAG, "getConfigFilePath -> not found (falling back to APK xml resource)");
        return "not found";
    }

    /**
     * Center-crops OpenGL camera preview textures when the HAL's actual preview-size aspect ratio
     * differs from the app's requested preview-size aspect ratio (e.g. Front Camera in 18:9 or 1:1 mode).
     */
    public static float[] calcFragCoordForCrop(float fromAspect, float toAspect) {
        if (Math.abs(fromAspect - 1.0f) < 0.001f && Math.abs(toAspect - 1.0f) < 0.001f) {
            float act = sActualPreviewAspect;
            float req = sRequestedPreviewAspect;
            if (act > 0.1f && req > 0.1f && Math.abs(act - req) > 0.02f) {
                fromAspect = 1.0f / act;
                toAspect = 1.0f / req;
            }
        } else if (Math.abs(toAspect - 1.0f) < 0.001f) {
            float act = sActualPreviewAspect;
            if (act > 0.1f) {
                fromAspect = 1.0f / act;
            }
        }
        if (fromAspect > toAspect) {
            float v1 = (fromAspect - toAspect) / (2.0f * fromAspect);
            return new float[] {
                    0.0f, v1,
                    1.0f, v1,
                    0.0f, 1.0f - v1,
                    1.0f, 1.0f - v1
            };
        } else {
            float v0 = (toAspect > 0.0f) ? ((toAspect - fromAspect) / (2.0f * toAspect)) : 0.0f;
            return new float[] {
                    v0, 0.0f,
                    1.0f - v0, 0.0f,
                    v0, 1.0f,
                    1.0f - v0, 1.0f
            };
        }
    }

    // ---- MediaStore ContentResolver Hooks (fixes IllegalArgumentException: Invalid column camera_mode on Android 11-15) ----

    private static ContentValues sanitizeContentValues(ContentValues values, boolean isUpdate, boolean coreOnly) {
        if (values == null) return null;
        ContentValues clean = new ContentValues();
        for (String key : values.keySet()) {
            if (key == null) continue;
            if (LG_MEDIA_COLUMNS.contains(key)) continue;
            if (isUpdate && ("_data".equals(key) || "date_added".equals(key))) continue;
            if (coreOnly && !CORE_MEDIA_COLUMNS.contains(key)) continue;
            Object val = values.get(key);
            if (val == null) {
                clean.putNull(key);
            } else if (val instanceof String) {
                clean.put(key, (String) val);
            } else if (val instanceof Integer) {
                clean.put(key, (Integer) val);
            } else if (val instanceof Long) {
                clean.put(key, (Long) val);
            } else if (val instanceof Float) {
                clean.put(key, (Float) val);
            } else if (val instanceof Double) {
                clean.put(key, (Double) val);
            } else if (val instanceof Boolean) {
                clean.put(key, (Boolean) val);
            } else if (val instanceof Short) {
                clean.put(key, (Short) val);
            } else if (val instanceof Byte) {
                clean.put(key, (Byte) val);
            } else if (val instanceof byte[]) {
                clean.put(key, (byte[]) val);
            }
        }
        return clean;
    }

    private static Uri queryExistingMediaUriByData(ContentResolver resolver, Uri baseUri, String dataPath) {
        if (resolver == null || baseUri == null || dataPath == null || dataPath.isEmpty()) return null;
        Cursor c = null;
        try {
            c = resolver.query(baseUri, new String[] { "_id" }, "_data=?", new String[] { dataPath }, null);
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                return ContentUris.withAppendedId(baseUri, id);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    public static Uri safeInsert(ContentResolver resolver, Uri uri, ContentValues values) {
        if (resolver == null || uri == null) return null;
        Uri targetUri = uri;
        String uriStr = uri.toString();
        // Panorama insertToContentResolver uses content://media/external/files; route images to Images.Media
        if (uriStr.contains("/files")) {
            String mime = (values != null) ? values.getAsString("mime_type") : null;
            if (mime != null && mime.startsWith("video/")) {
                targetUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            } else {
                targetUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            }
        }

        String dataPath = (values != null) ? values.getAsString("_data") : null;
        ContentValues clean = sanitizeContentValues(values, false, false);

        // 1. If FUSE already created the row in MediaProvider when FileOutputStream closed, update & return it
        if (dataPath != null && !dataPath.isEmpty()) {
            Uri existing = queryExistingMediaUriByData(resolver, targetUri, dataPath);
            if (existing != null) {
                safeUpdate(resolver, existing, clean, null, null);
                ShimLogger.logI(TAG, "safeInsert reused FUSE-registered MediaStore row: " + existing + " (" + dataPath + ")");
                return existing;
            }
        }

        // 2. Try standard insert with sanitized columns
        try {
            Uri res = resolver.insert(targetUri, clean);
            if (res != null) {
                ShimLogger.logI(TAG, "safeInsert succeeded -> " + res + " (" + dataPath + ")");
                return res;
            }
        } catch (Throwable t) {
            ShimLogger.logW(TAG, "safeInsert primary attempt failed (" + t.getMessage() + "); retrying with core columns");
        }

        // 3. Check again if row exists by _data, or retry with strict CORE_MEDIA_COLUMNS
        if (dataPath != null && !dataPath.isEmpty()) {
            Uri existing = queryExistingMediaUriByData(resolver, targetUri, dataPath);
            if (existing != null) {
                safeUpdate(resolver, existing, clean, null, null);
                ShimLogger.logI(TAG, "safeInsert found existing MediaStore row on retry: " + existing);
                return existing;
            }
        }

        ContentValues coreClean = sanitizeContentValues(values, false, true);
        try {
            Uri res = resolver.insert(targetUri, coreClean);
            if (res != null) {
                ShimLogger.logI(TAG, "safeInsert core-columns retry succeeded -> " + res);
                return res;
            }
        } catch (Throwable t2) {
            ShimLogger.logW(TAG, "safeInsert core-columns retry failed (" + t2.getMessage() + "); falling back to stream insert");
        }

        // 4. Final fallback for strict scoped storage (if _data insert was rejected): insert without _data and stream file bytes
        if (coreClean != null) {
            coreClean.remove("_data");
            try {
                Uri res = resolver.insert(targetUri, coreClean);
                if (res != null && dataPath != null) {
                    File f = new File(dataPath);
                    if (f.isFile() && f.length() > 0) {
                        InputStream in = null;
                        OutputStream out = null;
                        try {
                            in = new FileInputStream(f);
                            out = resolver.openOutputStream(res);
                            if (out != null) {
                                byte[] buf = new byte[16384];
                                int n;
                                while ((n = in.read(buf)) > 0) {
                                    out.write(buf, 0, n);
                                }
                                out.flush();
                            }
                        } finally {
                            try { if (in != null) in.close(); } catch (Throwable ignored) {}
                            try { if (out != null) out.close(); } catch (Throwable ignored) {}
                        }
                    }
                    ShimLogger.logI(TAG, "safeInsert stream fallback succeeded -> " + res);
                    return res;
                }
            } catch (Throwable t3) {
                ShimLogger.logE(TAG, "safeInsert all fallbacks failed for " + targetUri, t3);
            }
        }
        return null;
    }

    public static int safeUpdate(ContentResolver resolver, Uri uri, ContentValues values, String where, String[] selectionArgs) {
        if (resolver == null || uri == null || values == null) return 0;
        if (where != null) {
            for (String lgCol : LG_MEDIA_COLUMNS) {
                if (where.contains(lgCol)) return 0;
            }
        }
        ContentValues clean = sanitizeContentValues(values, true, false);
        if (clean == null || clean.size() == 0) return 1;
        try {
            return resolver.update(uri, clean, where, selectionArgs);
        } catch (Throwable t) {
            ShimLogger.logW(TAG, "safeUpdate primary failed (" + t.getMessage() + "); retrying with core columns");
            try {
                ContentValues coreClean = sanitizeContentValues(values, true, true);
                if (coreClean == null || coreClean.size() == 0) return 1;
                return resolver.update(uri, coreClean, where, selectionArgs);
            } catch (Throwable t2) {
                ShimLogger.logW(TAG, "safeUpdate ignored error: " + t2.getMessage());
                return 0;
            }
        }
    }

    public static Cursor safeQuery(ContentResolver resolver, Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (resolver == null || uri == null) return null;
        if (selection != null) {
            for (String lgCol : LG_MEDIA_COLUMNS) {
                if (selection.contains(lgCol)) {
                    ShimLogger.logD(TAG, "safeQuery skipping query with LG selection column '" + lgCol + "': " + selection);
                    return null;
                }
            }
        }
        if (projection != null && projection.length > 0) {
            boolean hasLgCol = false;
            ArrayList<String> validCols = new ArrayList<String>();
            for (String col : projection) {
                if (col != null && LG_MEDIA_COLUMNS.contains(col)) {
                    hasLgCol = true;
                } else if (col != null) {
                    validCols.add(col);
                }
            }
            if (hasLgCol) {
                if (validCols.isEmpty()) {
                    return null;
                }
                Cursor raw = null;
                try {
                    raw = resolver.query(uri, validCols.toArray(new String[0]), selection, selectionArgs, sortOrder);
                    if (raw == null) return null;
                    MatrixCursor mc = new MatrixCursor(projection, Math.max(raw.getCount(), 1));
                    while (raw.moveToNext()) {
                        Object[] row = new Object[projection.length];
                        for (int i = 0; i < projection.length; i++) {
                            String col = projection[i];
                            if (col == null || LG_MEDIA_COLUMNS.contains(col)) {
                                row[i] = Integer.valueOf(0);
                            } else {
                                int idx = raw.getColumnIndex(col);
                                if (idx < 0) {
                                    row[i] = null;
                                } else {
                                    int type = raw.getType(idx);
                                    if (type == Cursor.FIELD_TYPE_INTEGER) row[i] = Long.valueOf(raw.getLong(idx));
                                    else if (type == Cursor.FIELD_TYPE_FLOAT) row[i] = Double.valueOf(raw.getDouble(idx));
                                    else if (type == Cursor.FIELD_TYPE_BLOB) row[i] = raw.getBlob(idx);
                                    else if (type == Cursor.FIELD_TYPE_STRING) row[i] = raw.getString(idx);
                                    else row[i] = null;
                                }
                            }
                        }
                        mc.addRow(row);
                    }
                    return mc;
                } catch (Throwable t) {
                    ShimLogger.logW(TAG, "safeQuery mixed-projection fallback error: " + t.getMessage());
                    return null;
                } finally {
                    if (raw != null) {
                        try { raw.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        }
        try {
            return resolver.query(uri, projection, selection, selectionArgs, sortOrder);
        } catch (Throwable t) {
            ShimLogger.logW(TAG, "safeQuery caught error on " + uri + ": " + t.getMessage());
            return null;
        }
    }

    public static int safeDelete(ContentResolver resolver, Uri url, String where, String[] selectionArgs) {
        if (resolver == null || url == null) return 0;
        if (where != null) {
            for (String lgCol : LG_MEDIA_COLUMNS) {
                if (where.contains(lgCol)) return 0;
            }
        }
        try {
            return resolver.delete(url, where, selectionArgs);
        } catch (Throwable t) {
            ShimLogger.logW(TAG, "safeDelete caught error on " + url + ": " + t.getMessage());
            return 0;
        }
    }

    // ---- Safe Settings writes (prevents SecurityException when sideloaded without WRITE_SECURE_SETTINGS) ----

    public static boolean safePutSecureInt(ContentResolver cr, String name, int value) {
        try {
            return Settings.Secure.putInt(cr, name, value);
        } catch (Throwable t) {
            ShimLogger.logD(TAG, "Ignored Settings.Secure.putInt(" + name + ") without priv-app permission");
            return false;
        }
    }

    public static boolean safePutSecureString(ContentResolver cr, String name, String value) {
        try {
            return Settings.Secure.putString(cr, name, value);
        } catch (Throwable t) {
            ShimLogger.logD(TAG, "Ignored Settings.Secure.putString(" + name + ") without priv-app permission");
            return false;
        }
    }

    public static boolean safePutSystemInt(ContentResolver cr, String name, int value) {
        try {
            return Settings.System.putInt(cr, name, value);
        } catch (Throwable t) {
            ShimLogger.logD(TAG, "Ignored Settings.System.putInt(" + name + ") without priv-app permission");
            return false;
        }
    }

    public static String get(Camera.Parameters p, String key) {
        String v = (p == null) ? null : p.get(key);
        if (v != null) return v;
        String def = DEFAULTS.get(key);
        boolean first;
        synchronized (sSeen) { first = sSeen.add(key); }
        if (first) {
            if (def != null) {
                ShimLogger.logI(TAG, "HAL has no '" + key + "'; substituting default '" + def + "'");
            } else {
                ShimLogger.logD(TAG, "HAL has no '" + key + "' (no default; app gets null)");
            }
        }
        return def;
    }

    public static void setExposureCompensation(Camera.Parameters p, int value) {
        if (p == null) return;
        sUserEvIndex = value;
        p.setExposureCompensation(value);
    }

    public static void safeStartActivity(Activity activity, Intent intent) {
        if (activity == null || intent == null) return;
        String action = intent.getAction();
        String pkg = intent.getPackage();
        Uri data = intent.getData();
        String type = intent.getType();
        ShimLogger.logI(TAG, "safeStartActivity(action=" + action + ", pkg=" + pkg + ", data=" + data + ", type=" + type + ")");

        // Intercept Cine Shot (CineshotLauncher) when standalone com.lge.cinemagraph (Cinemagraph.apk) is not installed
        if ((data != null && "package:com.lge.cinemagraph".equals(data.toString()))
                || "com.lge.cinemagraph".equals(pkg)
                || "com.lge.camera.action.CINESHOT".equals(action)) {
            boolean installed = false;
            try {
                activity.getPackageManager().getPackageInfo("com.lge.cinemagraph", 0);
                installed = true;
            } catch (PackageManager.NameNotFoundException ignored) {
                installed = false;
            } catch (Throwable ignored) {}
            if (!installed) {
                ShimLogger.logW(TAG, "Cine Shot requested com.lge.cinemagraph, which is not installed in the system dump");
                try {
                    Toast.makeText(
                            activity,
                            "Cine Shot requires the separate LG Cinemagraph app (com.lge.cinemagraph), which is not included in the system dump.",
                            Toast.LENGTH_LONG
                    ).show();
                } catch (Throwable ignored) {}
                return;
            }
        }

        boolean isGalleryReview = "com.android.gallery3d".equals(pkg)
                || "com.lge.gallery".equals(pkg)
                || "com.android.camera.action.REVIEW".equals(action)
                || "com.lge.camera.action.utt".equals(action);

        if (isGalleryReview) {
            intent.setPackage(null);
            intent.setComponent(null);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        try {
            activity.startActivity(intent);
            return;
        } catch (ActivityNotFoundException e) {
            ShimLogger.logW(TAG, "startActivity failed (" + e.getMessage() + "); falling back to ACTION_VIEW");
        } catch (Throwable t) {
            ShimLogger.logW(TAG, "startActivity threw " + t + "; falling back to ACTION_VIEW");
        }

        if (data != null) {
            try {
                String resolvedType = type;
                if (resolvedType == null || resolvedType.isEmpty()) {
                    try {
                        resolvedType = activity.getContentResolver().getType(data);
                    } catch (Throwable ignored) {}
                }
                if (resolvedType == null || resolvedType.isEmpty()) {
                    String s = data.toString().toLowerCase();
                    if (s.contains("video") || s.endsWith(".mp4")) {
                        resolvedType = "video/*";
                    } else {
                        resolvedType = "image/*";
                    }
                }
                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(data, resolvedType);
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(viewIntent);
                ShimLogger.logI(TAG, "Fallback ACTION_VIEW succeeded for " + data + " (" + resolvedType + ")");
            } catch (Throwable t2) {
                ShimLogger.logE(TAG, "Fallback ACTION_VIEW also failed for " + data, t2);
            }
        }
    }

    public static Camera.Parameters safeGetParameters(Camera cam) {
        if (cam == null) return null;
        try {
            Camera.Parameters p = cam.getParameters();
            if (p != null) {
                if (sJogZoomSpeed != 0) {
                    int maxZoom = parseIntSafe(p.get("max-zoom"), 0);
                    if (maxZoom > 0 && "true".equalsIgnoreCase(p.get("zoom-supported"))) {
                        int curZoom = parseIntSafe(p.get("zoom"), 0);
                        int step = (sJogZoomSpeed > 0)
                                ? Math.max(1, (sJogZoomSpeed + 2) / 3)
                                : Math.min(-1, (sJogZoomSpeed - 2) / 3);
                        int nextZoom = Math.max(0, Math.min(maxZoom, curZoom + step));
                        if (nextZoom != curZoom) {
                            p.set("zoom", String.valueOf(nextZoom));
                            try {
                                cam.setParameters(p);
                            } catch (RuntimeException ignored) {}
                        }
                    }
                }
                synchronized (sCachedParams) {
                    sCachedParams.put(cam, p);
                }
            }
            return p;
        } catch (Throwable t) {
            ShimLogger.logW(TAG, "cam.getParameters() threw " + t + "; returning cached parameters");
            synchronized (sCachedParams) {
                return sCachedParams.get(cam);
            }
        }
    }

    public static void safeSetParameters(Camera cam, Camera.Parameters p) {
        if (cam == null || p == null) return;
        String prevFocusZone = null;
        synchronized (sLastAppliedFocusZone) {
            prevFocusZone = sLastAppliedFocusZone.get(cam);
        }
        try {
            sanitizeParameters(p);
        } catch (Throwable t) {
            ShimLogger.logW(TAG, "sanitizeParameters warning: " + t);
        }
        String newFocusMode = p.get("focus-mode");
        String mfStep = p.get("manualfocus_step");
        boolean shouldTriggerAf = false;
        if (mfStep != null && !mfStep.isEmpty() && !"-1".equals(mfStep) && !"-10000".equals(mfStep)) {
            if (newFocusMode != null && !newFocusMode.equals(prevFocusZone)) {
                shouldTriggerAf = true;
            }
        }
        try {
            cam.setParameters(p);
            synchronized (sCachedParams) {
                sCachedParams.put(cam, p);
            }
        } catch (RuntimeException e) {
            ShimLogger.logW(TAG, "cam.setParameters rejected by HAL (" + e.getMessage() + "); retrying with stripped areas/flash/scene");
            try {
                p.setSceneMode("auto");
                p.remove("focus-areas");
                p.remove("metering-areas");
                if (p.get("flash-mode-values") == null || p.get("flash-mode-values").isEmpty()) {
                    p.remove("flash-mode");
                } else {
                    p.set("flash-mode", "off");
                }
                sanitizeParameters(p);
                cam.setParameters(p);
                synchronized (sCachedParams) {
                    sCachedParams.put(cam, p);
                }
                ShimLogger.logI(TAG, "cam.setParameters retry SUCCEEDED");
            } catch (RuntimeException e2) {
                ShimLogger.logE(TAG, "cam.setParameters retry also failed (ignoring to keep camera alive)", e2);
                return;
            }
        }

        if (newFocusMode != null) {
            synchronized (sLastAppliedFocusZone) {
                sLastAppliedFocusZone.put(cam, newFocusMode);
            }
        }
        if (shouldTriggerAf) {
            try {
                cam.cancelAutoFocus();
                if ("macro".equals(newFocusMode) || "auto".equals(newFocusMode)) {
                    cam.autoFocus(null);
                }
                ShimLogger.logI(TAG, "Driven lens focus zone to '" + newFocusMode + "' for manualfocus_step=" + mfStep);
            } catch (Throwable ignored) {}
        }
    }

    public static void sanitizeParameters(Camera.Parameters p) {
        if (p == null) return;

        if ("1".equals(p.get("lg-manual-mode-reset"))) {
            sUserEvIndex = 0;
            p.set("lg-wb", "0");
            p.set("lg-iso", "auto");
            p.set("iso", "auto");
            p.set("shutter-speed", "0");
            p.remove("exposure-time");
            p.set("manualfocus_step", "-1");
            p.set("lg-manual-mode-reset", "0");
        }

        // 0a. Translate LG Manual Mode Kelvin white-balance ("lg-wb") into standard AOSP whitebalance preset
        String lgWb = p.get("lg-wb");
        if (lgWb != null && !lgWb.isEmpty()) {
            String wbSup = p.get("whitebalance-values");
            if ("0".equals(lgWb) || "-10000".equals(lgWb) || "auto".equalsIgnoreCase(lgWb)) {
                if (wbSup == null || inCommaList(wbSup, "auto")) {
                    p.set("whitebalance", "auto");
                }
            } else {
                int kelvin = parseIntSafe(lgWb, 0);
                if (kelvin > 0) {
                    String wbPreset;
                    if (kelvin <= 3200) wbPreset = "incandescent";
                    else if (kelvin <= 4200) wbPreset = "fluorescent";
                    else if (kelvin <= 5000) wbPreset = "warm-fluorescent";
                    else if (kelvin <= 5800) wbPreset = "daylight";
                    else if (kelvin <= 6600) wbPreset = "cloudy-daylight";
                    else wbPreset = "shade";
                    if (wbSup == null || inCommaList(wbSup, wbPreset)) {
                        p.set("whitebalance", wbPreset);
                    }
                }
            }
        }

        // 0b. Translate LG Manual Focus ("manualfocus_step") into standard AOSP focus-mode zones
        String mfStep = p.get("manualfocus_step");
        String fmValues = p.get("focus-mode-values");
        if (mfStep != null && !mfStep.isEmpty() && fmValues != null && !fmValues.isEmpty()) {
            int stepVal = parseIntSafe(mfStep, -1);
            if (stepVal < 0) {
                if ("macro".equals(p.get("focus-mode")) || "infinity".equals(p.get("focus-mode")) || "normal".equals(p.get("focus-mode"))) {
                    if (inCommaList(fmValues, "continuous-picture")) {
                        p.set("focus-mode", "continuous-picture");
                    } else if (inCommaList(fmValues, "auto")) {
                        p.set("focus-mode", "auto");
                    }
                }
            } else if (stepVal <= 20 && inCommaList(fmValues, "macro")) {
                p.set("focus-mode", "macro");
            } else if (stepVal <= 42 && inCommaList(fmValues, "auto")) {
                p.set("focus-mode", "auto");
            } else if (inCommaList(fmValues, "infinity")) {
                p.set("focus-mode", "infinity");
            }
        }

        // 0c. Translate LG Manual ISO ("iso" / "lg-iso") and Shutter Speed ("exposure-time" / "shutter-speed")
        // into effective exposure-compensation & preview-fps-range on LineageOS 22.2's HAL1 wrapper
        int isoEvOffset = 0;
        String isoStr = p.get("iso");
        if (isoStr == null || isoStr.isEmpty() || "auto".equalsIgnoreCase(isoStr)) {
            isoStr = p.get("lg-iso");
        }
        if (isoStr != null && !isoStr.isEmpty() && !"auto".equalsIgnoreCase(isoStr) && !"-10000".equals(isoStr) && !"0".equals(isoStr)) {
            String digits = isoStr.replaceAll("[^0-9]", "");
            int isoVal = parseIntSafe(digits, 0);
            if (isoVal > 0) {
                double stops = Math.log((double) isoVal / 400.0) / Math.log(2.0);
                isoEvOffset = (int) Math.round(stops * 4.0);
            }
        }

        int ssEvOffset = 0;
        String expTimeStr = p.get("exposure-time");
        String ssStr = p.get("shutter-speed");
        double expMs = 0.0;
        if (expTimeStr != null && !expTimeStr.isEmpty() && !"0".equals(expTimeStr) && !"-10000".equals(expTimeStr) && !"auto".equalsIgnoreCase(expTimeStr)) {
            expMs = parseDoubleSafe(expTimeStr, 0.0);
        } else if (ssStr != null && !ssStr.isEmpty() && !"0".equals(ssStr) && !"-10000".equals(ssStr) && !"auto".equalsIgnoreCase(ssStr)) {
            if (ssStr.contains("/")) {
                String[] parts = ssStr.split("/");
                if (parts.length == 2) {
                    double num = parseDoubleSafe(parts[0], 1.0);
                    double den = parseDoubleSafe(parts[1], 30.0);
                    if (den > 0) expMs = (num / den) * 1000.0;
                }
            } else {
                double sec = parseDoubleSafe(ssStr, 0.0);
                if (sec > 0) expMs = sec * 1000.0;
            }
        }
        if (expMs > 0.0) {
            double stops = Math.log(expMs / 33.333333) / Math.log(2.0);
            ssEvOffset = (int) Math.round(stops * 2.5);
            String fpsRangeSup = p.get("preview-fps-range-values");
            if (fpsRangeSup != null) {
                if (expMs <= 25.0 && fpsRangeSup.contains("(30000,30000)")) {
                    p.set("preview-fps-range", "30000,30000");
                } else if (expMs > 25.0 && fpsRangeSup.contains("(2000,30000)")) {
                    p.set("preview-fps-range", "2000,30000");
                }
            }
        }

        // 0d. Translate Cine Video LUT filter ("cinema-lut" = "<lutIndex>-<strength>", 1..17) into standard HAL1 effect/whitebalance
        int cineLutEvOffset = 0;
        String cineLut = p.get("cinema-lut");
        if (cineLut != null && cineLut.contains("-")) {
            String[] lutParts = cineLut.split("-");
            int lutIdx = parseIntSafe(lutParts[0], 1);
            int lutStrength = (lutParts.length > 1) ? parseIntSafe(lutParts[1], 0) : 0;
            String effSup = p.get("effect-values");
            String wbSup = p.get("whitebalance-values");
            if (lutIdx <= 1 || lutStrength <= 0) {
                if (effSup == null || inCommaList(effSup, "none")) p.set("effect", "none");
            } else {
                if (lutIdx == 7 || lutIdx == 13) {
                    if (effSup != null && inCommaList(effSup, "mono")) p.set("effect", "mono");
                } else if (lutIdx == 4 || lutIdx == 9 || lutIdx == 15) {
                    if (effSup != null && inCommaList(effSup, "sepia")) p.set("effect", "sepia");
                } else if (lutIdx == 6 || lutIdx == 12) {
                    if (effSup != null && inCommaList(effSup, "aqua")) p.set("effect", "aqua");
                } else if (lutIdx == 11 || lutIdx == 16) {
                    if (effSup != null && inCommaList(effSup, "posterize")) p.set("effect", "posterize");
                } else {
                    if (effSup == null || inCommaList(effSup, "none")) p.set("effect", "none");
                    if (wbSup != null) {
                        String wbLut = "auto";
                        if (lutIdx == 2 || lutIdx == 8) wbLut = "cloudy-daylight";
                        else if (lutIdx == 3 || lutIdx == 10) wbLut = "incandescent";
                        else if (lutIdx == 5 || lutIdx == 14) wbLut = "shade";
                        else if (lutIdx == 17) wbLut = "fluorescent";
                        if (inCommaList(wbSup, wbLut)) p.set("whitebalance", wbLut);
                    }
                    if (lutIdx == 3 || lutIdx == 8 || lutIdx == 14) {
                        cineLutEvOffset = -2;
                    } else if (lutIdx == 2 || lutIdx == 5) {
                        cineLutEvOffset = 2;
                    }
                }
            }
        }

        // 0e. Translate LG "jog-zoom" (shutter-button / zoom-bar drag speed) into standard HAL1 "zoom" step increments,
        //     and clamp "zoom" to [0, maxZoom] so ZoomManager / InAndOutZoomManager work smoothly on Camera2Client.
        int maxZoom = parseIntSafe(p.get("max-zoom"), 0);
        if (maxZoom > 0 && "true".equalsIgnoreCase(p.get("zoom-supported"))) {
            int curZoom = parseIntSafe(p.get("zoom"), 0);
            String jogZoomStr = p.get("jog-zoom");
            if (jogZoomStr != null && !jogZoomStr.isEmpty()) {
                int jogSpeed = parseIntSafe(jogZoomStr, 0);
                sJogZoomSpeed = jogSpeed;
                if (jogSpeed != 0) {
                    int step = (jogSpeed > 0) ? Math.max(1, (jogSpeed + 2) / 3) : Math.min(-1, (jogSpeed - 2) / 3);
                    curZoom += step;
                }
                p.remove("jog-zoom");
            }
            if (curZoom < 0) curZoom = 0;
            if (curZoom > maxZoom) curZoom = maxZoom;
            p.set("zoom", String.valueOf(curZoom));
        }

        int minExp = parseIntSafe(p.get("min-exposure-compensation"), -12);
        int maxExp = parseIntSafe(p.get("max-exposure-compensation"), 12);
        int combinedEv = sUserEvIndex + isoEvOffset + ssEvOffset + cineLutEvOffset;
        if (combinedEv < minExp) combinedEv = minExp;
        if (combinedEv > maxExp) combinedEv = maxExp;
        p.set("exposure-compensation", String.valueOf(combinedEv));

        // 1. Validate preview-fps-range against preview-fps-range-values
        String fpsRange = p.get("preview-fps-range");
        String fpsRangeValues = p.get("preview-fps-range-values");
        if (fpsRange != null && fpsRangeValues != null && !fpsRangeValues.isEmpty()) {
            if (!fpsRangeValues.contains("(" + fpsRange + ")")) {
                String replacement = pickBestFpsRange(fpsRange, fpsRangeValues);
                if (replacement != null && !replacement.equals(fpsRange)) {
                    ShimLogger.logI(TAG, "Adjusting unsupported preview-fps-range '" + fpsRange + "' -> '" + replacement + "'");
                    p.set("preview-fps-range", replacement);
                }
            }
        }

        // 2. Validate preview-frame-rate against preview-frame-rate-values
        String fps = p.get("preview-frame-rate");
        String fpsValues = p.get("preview-frame-rate-values");
        if (fps != null && fpsValues != null && !fpsValues.isEmpty()) {
            if (!inCommaList(fpsValues, fps)) {
                String fallbackFps = inCommaList(fpsValues, "30") ? "30" : lastCommaItem(fpsValues);
                ShimLogger.logI(TAG, "Adjusting unsupported preview-frame-rate '" + fps + "' -> '" + fallbackFps + "'");
                p.set("preview-frame-rate", fallbackFps);
            }
        }

        // 3. Validate preview-size, picture-size, video-size against *-values
        for (String sizeKey : new String[] { "preview-size", "picture-size", "video-size" }) {
            String val = p.get(sizeKey);
            String sup = p.get(sizeKey + "-values");
            if (val != null) {
                int atIdx = val.indexOf('@');
                if (atIdx > 0) {
                    val = val.substring(0, atIdx);
                    p.set(sizeKey, val);
                }
                if ("preview-size".equals(sizeKey)) {
                    float reqAspect = parseSizeAspect(val);
                    if (reqAspect > 0.1f) sRequestedPreviewAspect = reqAspect;
                }
                if (sup != null && !sup.isEmpty() && !inCommaList(sup, val)) {
                    String best = pickClosestSize(val, sup);
                    if (best != null) {
                        ShimLogger.logI(TAG, "Adjusting unsupported " + sizeKey + " '" + val + "' -> '" + best + "'");
                        p.set(sizeKey, best);
                        val = best;
                    }
                }
                if ("preview-size".equals(sizeKey)) {
                    float actAspect = parseSizeAspect(val);
                    if (actAspect > 0.1f) sActualPreviewAspect = actAspect;
                }
            }
        }

        // 4. Validate focus-mode against focus-mode-values
        String fm = p.get("focus-mode");
        if (fm != null && fmValues != null && !fmValues.isEmpty()) {
            if (!inCommaList(fmValues, fm)) {
                String bestFm;
                int stepVal = parseIntSafe(mfStep, -1);
                if (stepVal >= 0 && stepVal <= 20 && inCommaList(fmValues, "macro")) {
                    bestFm = "macro";
                } else if (stepVal >= 43 && inCommaList(fmValues, "infinity")) {
                    bestFm = "infinity";
                } else if (inCommaList(fmValues, "continuous-picture")) {
                    bestFm = "continuous-picture";
                } else if (inCommaList(fmValues, "auto")) {
                    bestFm = "auto";
                } else if (inCommaList(fmValues, "infinity")) {
                    bestFm = "infinity";
                } else if (inCommaList(fmValues, "fixed")) {
                    bestFm = "fixed";
                } else {
                    bestFm = firstCommaItem(fmValues);
                }
                ShimLogger.logI(TAG, "Adjusting unsupported focus-mode '" + fm + "' -> '" + bestFm + "' (allowed=" + fmValues + ")");
                p.set("focus-mode", bestFm);
            }
        }

        // 5. Clear focus-areas / metering-areas if max-num-* is 0
        if (parseIntSafe(p.get("max-num-focus-areas"), 0) <= 0) {
            String fa = p.get("focus-areas");
            if (fa != null && !"(0,0,0,0,0)".equals(fa)) {
                p.remove("focus-areas");
            }
        }
        if (parseIntSafe(p.get("max-num-metering-areas"), 0) <= 0) {
            String ma = p.get("metering-areas");
            if (ma != null && !"(0,0,0,0,0)".equals(ma)) {
                p.remove("metering-areas");
            }
        }

        // 6. Validate flash-mode against flash-mode-values
        String flash = p.get("flash-mode");
        String flashValues = p.get("flash-mode-values");
        if (flash != null) {
            if (flashValues == null || flashValues.isEmpty()) {
                p.remove("flash-mode");
            } else if (!inCommaList(flashValues, flash)) {
                p.set("flash-mode", inCommaList(flashValues, "off") ? "off" : firstCommaItem(flashValues));
            }
        }

        // 7. Validate scene-mode, whitebalance, effect, antibanding
        checkListParam(p, "scene-mode", "auto");
        checkListParam(p, "whitebalance", "auto");
        checkListParam(p, "effect", "none");
        checkListParam(p, "antibanding", "auto");

        // 8. Validate video-stabilization
        if ("true".equalsIgnoreCase(p.get("video-stabilization"))
                && !"true".equalsIgnoreCase(p.get("video-stabilization-supported"))) {
            p.set("video-stabilization", "false");
        }
    }

    private static float parseSizeAspect(String whStr) {
        if (whStr == null) return 0.0f;
        try {
            String[] wh = whStr.split("x");
            if (wh.length == 2) {
                int w = Integer.parseInt(wh[0].trim());
                int h = Integer.parseInt(wh[1].trim());
                if (w > 0 && h > 0) return (float) w / (float) h;
            }
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    private static void checkListParam(Camera.Parameters p, String key, String fallback) {
        String val = p.get(key);
        String sup = p.get(key + "-values");
        if (val != null && sup != null && !sup.isEmpty() && !inCommaList(sup, val)) {
            String next = inCommaList(sup, fallback) ? fallback : firstCommaItem(sup);
            ShimLogger.logI(TAG, "Adjusting unsupported " + key + " '" + val + "' -> '" + next + "'");
            p.set(key, next);
        }
    }

    private static String pickBestFpsRange(String requested, String supportedRanges) {
        int reqMin = 2000, reqMax = 30000;
        try {
            String[] parts = requested.split(",");
            if (parts.length == 2) {
                reqMin = Integer.parseInt(parts[0].trim());
                reqMax = Integer.parseInt(parts[1].trim());
            }
        } catch (Throwable ignored) {}

        if (reqMin == reqMax) {
            String fixed = reqMax + "," + reqMax;
            if (supportedRanges.contains("(" + fixed + ")")) return fixed;
            if (supportedRanges.contains("(30000,30000)")) return "30000,30000";
        }
        if (reqMax <= 24000 && supportedRanges.contains("(2000,24000)")) {
            return "2000,24000";
        }
        if (supportedRanges.contains("(2000,30000)")) {
            return "2000,30000";
        }
        int open = supportedRanges.indexOf('(');
        int close = supportedRanges.indexOf(')', open + 1);
        if (open >= 0 && close > open) {
            return supportedRanges.substring(open + 1, close);
        }
        return null;
    }

    private static String pickClosestSize(String requested, String supportedCsv) {
        int reqW = 1920, reqH = 1080;
        try {
            String[] wh = requested.split("x");
            if (wh.length == 2) {
                reqW = Integer.parseInt(wh[0].trim());
                reqH = Integer.parseInt(wh[1].trim());
            }
        } catch (Throwable ignored) {}
        double reqRatio = (reqH > 0) ? ((double) reqW / (double) reqH) : (16.0 / 9.0);
        int reqArea = reqW * reqH;

        String bestSameRatio = null;
        int bestRatioAreaDiff = Integer.MAX_VALUE;
        String bestClosestRatioHd = null;
        double bestRatioDiffHd = Double.MAX_VALUE;
        int bestAreaDiffHd = Integer.MAX_VALUE;
        String bestOverall = null;
        int bestOverallDiff = Integer.MAX_VALUE;

        for (String item : supportedCsv.split(",")) {
            String s = item.trim();
            if (s.isEmpty()) continue;
            String[] wh = s.split("x");
            if (wh.length != 2) continue;
            try {
                int w = Integer.parseInt(wh[0].trim());
                int h = Integer.parseInt(wh[1].trim());
                if (w <= 0 || h <= 0) continue;
                if (reqW >= reqH && w < h) continue;
                double ratio = (double) w / (double) h;
                double ratioDiff = Math.abs(ratio - reqRatio);
                int areaDiff = Math.abs((w * h) - reqArea);
                if (ratioDiff < 0.08) {
                    if (areaDiff < bestRatioAreaDiff) {
                        bestRatioAreaDiff = areaDiff;
                        bestSameRatio = s;
                    }
                }
                if (w >= 960) {
                    if (ratioDiff < bestRatioDiffHd - 0.02 || (Math.abs(ratioDiff - bestRatioDiffHd) <= 0.02 && areaDiff < bestAreaDiffHd)) {
                        bestRatioDiffHd = ratioDiff;
                        bestAreaDiffHd = areaDiff;
                        bestClosestRatioHd = s;
                    }
                }
                if (areaDiff < bestOverallDiff) {
                    bestOverallDiff = areaDiff;
                    bestOverall = s;
                }
            } catch (Throwable ignored) {}
        }
        if (bestSameRatio != null) return bestSameRatio;
        if (bestClosestRatioHd != null) return bestClosestRatioHd;
        return bestOverall;
    }

    private static boolean inCommaList(String csv, String item) {
        if (csv == null || item == null) return false;
        for (String part : csv.split(",")) {
            if (item.equals(part.trim())) return true;
        }
        return false;
    }

    private static String firstCommaItem(String csv) {
        if (csv == null) return "";
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static String lastCommaItem(String csv) {
        if (csv == null) return "";
        String last = "";
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) last = t;
        }
        return last;
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static double parseDoubleSafe(String s, double def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static void ensureCameraTableLogged() {
        if (sLoggedCameraTable) return;
        synchronized (ParamDefaults.class) {
            if (sLoggedCameraTable) return;
            sLoggedCameraTable = true;
            try {
                int count = Camera.getNumberOfCameras();
                int facing1 = -1;
                for (int i = 0; i < count; i++) {
                    Camera.CameraInfo ci = new Camera.CameraInfo();
                    Camera.getCameraInfo(i, ci);
                    ShimLogger.logI(TAG, "HAL Camera[" + i + "]: facing=" + (ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "FRONT(1)" : "BACK(0)")
                            + ", orientation=" + ci.orientation + ", canDisableShutterSound=" + ci.canDisableShutterSound);
                    if (i == 1) facing1 = ci.facing;
                }
                if (count >= 2 && facing1 == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    sSwapCam1And2 = true;
                    ShimLogger.logW(TAG, "Detected HAL Camera[1]=BACK; swapping LG cameraId 1 <-> 2 so Ultrawide Rear button (cameraId=2) opens HAL Camera[1]!");
                }
            } catch (Throwable t) {
                ShimLogger.logE(TAG, "ensureCameraTableLogged error", t);
            }
        }
    }

    public static int remapCameraId(int cameraId) {
        ensureCameraTableLogged();
        int count = 3;
        try {
            count = Camera.getNumberOfCameras();
        } catch (Throwable ignored) {}
        if (sSwapCam1And2) {
            if (cameraId == 2) return 1;
            if (cameraId == 1) return (count >= 3) ? 2 : 1;
        }
        if (count > 0 && (cameraId < 0 || cameraId >= count)) {
            ShimLogger.logI(TAG, "remapCameraId: virtual cameraId=" + cameraId + " -> 0 (count=" + count + ")");
            return 0;
        }
        return cameraId;
    }

    public static void onCameraOpened(int targetId, Camera cam) {
        sActiveCamera = cam;
        sActiveCameraId = targetId;
        sJogZoomSpeed = 0;
        sUserEvIndex = 0;
        if (cam != null) {
            synchronized (sOpenCameras) {
                sOpenCameras.put(cam, targetId);
            }
        }
    }

    public static boolean isAnyCameraOpen() {
        synchronized (sOpenCameras) {
            return !sOpenCameras.isEmpty();
        }
    }

    public static void safeReleaseCamera(Camera cam) {
        if (cam == null) return;
        int id = (cam == sActiveCamera) ? sActiveCameraId : -1;
        synchronized (sOpenCameras) {
            Integer tracked = sOpenCameras.remove(cam);
            if (id < 0 && tracked != null) id = tracked;
        }
        ShimLogger.logI(TAG, "safeReleaseCamera(id=" + id + ", cam=" + cam + ")");
        try {
            cam.release();
        } catch (Throwable t) {
            ShimLogger.logE(TAG, "cam.release() error", t);
        } finally {
            if (cam == sActiveCamera) {
                sActiveCamera = null;
                sActiveCameraId = -1;
                sJogZoomSpeed = 0;
            }
            sLastReleaseTimeMs = System.currentTimeMillis();
        }
    }

    public static void awaitCameraSettleBeforeOpen(int targetId) {
        long elapsed = System.currentTimeMillis() - sLastReleaseTimeMs;
        if (sLastReleaseTimeMs > 0 && elapsed >= 0 && elapsed < 220) {
            long waitMs = 220 - elapsed;
            ShimLogger.logI(TAG, "Waiting " + waitMs + "ms after previous Camera.release() before opening cameraId=" + targetId);
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException ignored) {}
        }
    }

    public static void forceReleaseActiveCameraIfDifferent(int targetId) {
        Camera active = sActiveCamera;
        int activeId = sActiveCameraId;
        if (active != null && activeId != targetId) {
            ShimLogger.logW(TAG, "Forcing release of still-open Camera(id=" + activeId + ") before retrying open(id=" + targetId + ")");
            safeReleaseCamera(active);
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}
        }
    }

    public static Camera safeOpenCamera(int cameraId) {
        int safeId = remapCameraId(cameraId);
        awaitCameraSettleBeforeOpen(safeId);
        ShimLogger.logI(TAG, "safeOpenCamera(requested=" + cameraId + ", actual=" + safeId + ")");
        Throwable lastErr = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                Camera c = Camera.open(safeId);
                onCameraOpened(safeId, c);
                return c;
            } catch (Throwable t) {
                lastErr = t;
                ShimLogger.logW(TAG, "safeOpenCamera(id=" + safeId + ", attempt=" + attempt + "/4) failed: " + t.getMessage());
                if (attempt < 4) {
                    try { Thread.sleep(220); } catch (InterruptedException ignored) {}
                }
            }
        }
        if (safeId != 0 && !isAnyCameraOpen()) {
            ShimLogger.logW(TAG, "safeOpenCamera falling back to Camera.open(0) for failed id=" + safeId);
            Camera c = Camera.open(0);
            onCameraOpened(0, c);
            return c;
        }
        if (lastErr instanceof RuntimeException) throw (RuntimeException) lastErr;
        throw new RuntimeException(lastErr);
    }

    public static void getCameraInfo(int cameraId, Camera.CameraInfo info) {
        int safeId = remapCameraId(cameraId);
        Camera.getCameraInfo(safeId, info);
        if (cameraId == 1 && info != null) {
            info.facing = Camera.CameraInfo.CAMERA_FACING_FRONT;
            if (info.orientation == 90) {
                info.orientation = 270;
            }
        }
    }

    public static CamcorderProfile getCamcorderProfile(int cameraId, int quality) {
        int safeCamId = remapCameraId(cameraId);
        if (safeCamId < 0 || safeCamId > 2) safeCamId = 0;
        try {
            if ((quality >= 0 && quality <= 8) || (quality >= 1000 && quality <= 1008) || (quality >= 2000 && quality <= 2004)) {
                if (CamcorderProfile.hasProfile(safeCamId, quality)) {
                    return CamcorderProfile.get(safeCamId, quality);
                }
                if (CamcorderProfile.hasProfile(0, quality)) {
                    return CamcorderProfile.get(0, quality);
                }
            }
        } catch (Throwable ignored) {}
        int fallbackQ = CamcorderProfile.hasProfile(safeCamId, CamcorderProfile.QUALITY_1080P)
                ? CamcorderProfile.QUALITY_1080P : CamcorderProfile.QUALITY_HIGH;
        CamcorderProfile base = CamcorderProfile.get(safeCamId, fallbackQ);
        CamcorderProfileEx ex = CamcorderProfileEx.get(safeCamId, quality);
        if (base != null && ex != null) {
            base.videoFrameWidth = ex.videoFrameWidth;
            base.videoFrameHeight = ex.videoFrameHeight;
            base.videoFrameRate = Math.min(ex.videoFrameRate, 30);
            base.videoBitRate = ex.videoBitRate;
            base.videoCodec = ex.videoCodec;
        }
        return base;
    }

    // ---- Resource Lookup Fallbacks (prevents Resources$NotFoundException on unresolved 0x02 / type #0x7) ----

    public static Drawable getDrawable(Resources res, int id) {
        if (res == null || id == 0 || (id >>> 24) == 0x02) {
            logMissingResOnce("getDrawable", id, "transparent ColorDrawable [lge-res]");
            return new ColorDrawable(Color.TRANSPARENT);
        }
        try {
            return res.getDrawable(id);
        } catch (Throwable t) {
            logMissingResOnce("getDrawable", id, "transparent ColorDrawable (" + t.getMessage() + ")");
            return new ColorDrawable(Color.TRANSPARENT);
        }
    }

    public static Drawable getDrawable(Resources res, int id, Resources.Theme theme) {
        if (res == null || id == 0 || (id >>> 24) == 0x02) {
            logMissingResOnce("getDrawable(theme)", id, "transparent ColorDrawable [lge-res]");
            return new ColorDrawable(Color.TRANSPARENT);
        }
        try {
            return res.getDrawable(id, theme);
        } catch (Throwable t) {
            logMissingResOnce("getDrawable(theme)", id, "transparent ColorDrawable (" + t.getMessage() + ")");
            return new ColorDrawable(Color.TRANSPARENT);
        }
    }

    public static boolean getBoolean(Resources res, int id) {
        if (res == null || id == 0 || (id >>> 24) == 0x02) {
            logMissingResOnce("getBoolean", id, "false [lge-res]");
            return false;
        }
        try {
            return res.getBoolean(id);
        } catch (Throwable t) {
            logMissingResOnce("getBoolean", id, "false (" + t.getMessage() + ")");
            return false;
        }
    }

    public static int getInteger(Resources res, int id) {
        if (res == null || id == 0 || (id >>> 24) == 0x02) {
            logMissingResOnce("getInteger", id, "0 [lge-res]");
            return 0;
        }
        try {
            return res.getInteger(id);
        } catch (Throwable t) {
            logMissingResOnce("getInteger", id, "0 (" + t.getMessage() + ")");
            return 0;
        }
    }

    public static int getDimensionPixelSize(Resources res, int id) {
        if (res == null || id == 0 || (id >>> 24) == 0x02) {
            logMissingResOnce("getDimensionPixelSize", id, "42px [lge-res]");
            return 42;
        }
        try {
            return res.getDimensionPixelSize(id);
        } catch (Throwable t) {
            logMissingResOnce("getDimensionPixelSize", id, "42px (" + t.getMessage() + ")");
            return 42;
        }
    }

    public static int getDimensionPixelOffset(Resources res, int id) {
        if (res == null || id == 0 || (id >>> 24) == 0x02) {
            logMissingResOnce("getDimensionPixelOffset", id, "42px [lge-res]");
            return 42;
        }
        try {
            return res.getDimensionPixelOffset(id);
        } catch (Throwable t) {
            logMissingResOnce("getDimensionPixelOffset", id, "42px (" + t.getMessage() + ")");
            return 42;
        }
    }

    public static float getDimension(Resources res, int id) {
        if (res == null || id == 0 || (id >>> 24) == 0x02) {
            logMissingResOnce("getDimension", id, "42.0f [lge-res]");
            return 42.0f;
        }
        try {
            return res.getDimension(id);
        } catch (Throwable t) {
            logMissingResOnce("getDimension", id, "42.0f (" + t.getMessage() + ")");
            return 42.0f;
        }
    }

    private static void logMissingResOnce(String mth, int id, String fallbackDesc) {
        boolean first;
        synchronized (sSeenRes) { first = sSeenRes.add(id); }
        if (first) {
            ShimLogger.logW(TAG, "Resources." + mth + "(0x" + Integer.toHexString(id) + ") failed -> returning "
                    + fallbackDesc + " (caller=" + ShimLogger.getCallerInfo() + ")");
        }
    }
}
