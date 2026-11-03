package com.lge.os;

import java.util.HashMap;
import java.util.Map;

/**
 * Auto-enriched PropertyUtils shim with caller stack tracing, stock build.prop values,
 * live SystemProperties lookup, and safe fallbacks.
 */
public class PropertyUtils {
    public static class PROP_CODE {
        public static final int RO_CNAS_ENABLE = 1805;
        public PROP_CODE() {}
    }
    private static final String TAG = "PropertyUtils";
    private static final Map<Integer, String> OVERRIDES = new HashMap<Integer, String>();
    private static final Map<Integer, String> CODE_TO_PROP = new HashMap<Integer, String>();
    private static final Map<String, String> STOCK_PROPS = new HashMap<String, String>();

    static {
        ShimLogger.init();
        OVERRIDES.put(1804, "qcom"); /* RO_CHIP_VENDOR: msm8998 */
        OVERRIDES.put(2200, "1");    /* CamLog.setLogOn calls Integer.parseInt(get(2200, "")) */
        OVERRIDES.put(1900, "2000,30000"); /* HW_CAMCORDER_FPSRANGE matching Camera 0 preview-fps-range-values */


    }

    private static PropertyUtils sInstance;
    public static synchronized PropertyUtils getInstance() {
        if (sInstance == null) sInstance = new PropertyUtils();
        return sInstance;
    }

    private static String sysprop(String key) {
        if (key == null || key.isEmpty()) return "";
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            String v = (String) c.getMethod("get", String.class, String.class).invoke(null, key, "");
            return v != null ? v : "";
        } catch (Throwable t) {
            return "";
        }
    }

    public String get(int code) {
        return get(code, "");
    }

    public String get(int code, String def) {
        String safeDef = (def != null) ? def : "";
        String v = OVERRIDES.get(code);
        String propName = CODE_TO_PROP.get(code);
        if ((v == null || v.isEmpty()) && propName != null) {
            v = sysprop(propName);
            if (v == null || v.isEmpty()) {
                v = STOCK_PROPS.get(propName);
            }
        }
        String out = (v != null && !v.isEmpty()) ? v : safeDef;
        String caller = ShimLogger.getCallerInfo();

        if (out.isEmpty()) {
            if (caller.contains("CamLog") || caller.contains("parseInt")) {
                out = "0";
            }
        }

        ShimLogger.logD(TAG, "get(code=" + code + (propName != null ? " [" + propName + "]" : "")
                + ", def='" + safeDef + "') -> '" + out + "' (caller=" + caller + ")");
        return out;
    }

    public boolean getBoolean(int code, boolean def) {
        String v = get(code, def ? "1" : "0");
        boolean out = v.equals("1") || v.equalsIgnoreCase("true");
        ShimLogger.logD(TAG, "getBoolean(code=" + code + ", def=" + def + ") -> " + out + " (caller=" + ShimLogger.getCallerInfo() + ")");
        return out;
    }

    public int getInt(int code, int def) {
        int out = def;
        try {
            String v = get(code, "" + def);
            if (v != null && !v.isEmpty()) out = Integer.parseInt(v.trim());
        } catch (NumberFormatException ignored) {}
        ShimLogger.logD(TAG, "getInt(code=" + code + ", def=" + def + ") -> " + out + " (caller=" + ShimLogger.getCallerInfo() + ")");
        return out;
    }

    public long getLong(int code, long def) {
        long out = def;
        try {
            String v = get(code, "" + def);
            if (v != null && !v.isEmpty()) out = Long.parseLong(v.trim());
        } catch (NumberFormatException ignored) {}
        ShimLogger.logD(TAG, "getLong(code=" + code + ", def=" + def + ") -> " + out + " (caller=" + ShimLogger.getCallerInfo() + ")");
        return out;
    }

    public void set(int code, String val) {
        ShimLogger.logW(TAG, "set(code=" + code + ", val='" + val + "') (caller=" + ShimLogger.getCallerInfo() + ")");
        OVERRIDES.put(code, val);
    }
}
