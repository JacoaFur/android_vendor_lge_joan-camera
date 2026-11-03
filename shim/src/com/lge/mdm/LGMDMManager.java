package com.lge.mdm;
import android.content.ComponentName;
import com.lge.mdm.config.LGMDMApplicationState;
import java.util.ArrayList;
import java.util.List;
/** Enterprise policy client: stubbed to "everything allowed". */
public class LGMDMManager {
    private static LGMDMManager sInstance;
    private LGMDMManager() {}
    public static synchronized LGMDMManager getInstance() {
        if (sInstance == null) sInstance = new LGMDMManager();
        return sInstance;
    }
    public boolean getAllowCameraWithWhitelist(ComponentName admin) { return true; }
    public boolean getAllowMicrophone(ComponentName admin) { return true; }
    public List<LGMDMApplicationState> getApplicationState(ComponentName admin) { return new ArrayList<LGMDMApplicationState>(); }
    public List<String> getCameraWhitelist(ComponentName admin) { return new ArrayList<String>(); }
    public List<String> getMicrophoneWhitelist(ComponentName admin) { return new ArrayList<String>(); }
}
