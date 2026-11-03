package com.lge.app.permission;

import android.app.Activity;
import android.os.Bundle;
import com.lge.os.ShimLogger;

public class RequestPermissionsActivity extends Activity {
    static { ShimLogger.init(); }
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ShimLogger.logI("LGPermActivity", "RequestPermissionsActivity.onCreate -> finishing with RESULT_OK");
        setResult(RESULT_OK);
        finish();
    }
}
