package com.lge.systemservice.core;
import android.content.Context;
import com.lge.os.ShimLogger;

public class LGContext {
    static { ShimLogger.init(); }
    public LGContext(Context context) {
        ShimLogger.logD("LGContext", "new LGContext(" + context + ")");
    }
    public Object getLGSystemService(String name) {
        ShimLogger.logI("LGContext", "getLGSystemService(\"" + name + "\")");
        if (name == null) return null;
        if (name.equals("emotionled")) return new LEDManager();
        if (name.equals("lgthermal")) return new LGThermalManager();
        if (name.equals("osservice")) return new OsManager();
        if (name.equals("smartcover")) return new SmartCoverManager();
        if (name.equals("volumevibrator")) return new VolumeVibratorManager();
        if (name.equals("wfdService")) return new WfdManager();
        ShimLogger.logW("LGContext", "Unhandled LGSystemService requested: " + name);
        return null;
    }
}
