package com.mike.findmykid.tracker;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.util.List;

public class AppUsageTracker {
    private final UsageStatsManager usageStatsManager;

    public AppUsageTracker(Context context) {
        usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    public String getForegroundApp() {
        if (usageStatsManager == null) return "unknown";

        long time = System.currentTimeMillis();
        // Query last 5 minutes
        List<UsageStats> appList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, time - 1000 * 300, time);

        if (appList != null && !appList.isEmpty()) {
            UsageStats recentStats = null;
            for (UsageStats usageStats : appList) {
                if (recentStats == null || usageStats.getLastTimeUsed() > recentStats.getLastTimeUsed()) {
                    recentStats = usageStats;
                }
            }
            if (recentStats != null) {
                return recentStats.getPackageName();
            }
        }
        return "unknown";
    }
}
