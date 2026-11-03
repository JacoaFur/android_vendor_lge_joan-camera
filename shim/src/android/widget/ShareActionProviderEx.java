package android.widget;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import java.util.LinkedHashMap;

/**
 * Stand-in for LG's framework-only android.widget.ShareActionProviderEx.
 * Used by QuickClipUtil.loadSharableActivities() and setQuickClipListListener():
 *   - new ShareActionProviderEx(ctx)
 *   - setShareIntent(intent)
 *   - getSortedListAndWeight() -> LinkedHashMap<ResolveInfo,Integer>
 *   - activitySelected(String) -> Intent
 */
public class ShareActionProviderEx {
    private final Context mContext;
    private Intent mIntent;

    public ShareActionProviderEx(Context context) {
        mContext = context;
    }

    public void setShareIntent(Intent intent) {
        mIntent = intent;
    }

    public LinkedHashMap<ResolveInfo, Integer> getSortedListAndWeight() {
        return new LinkedHashMap<ResolveInfo, Integer>();
    }

    public Intent activitySelected(String activityName) {
        return mIntent;
    }
}
