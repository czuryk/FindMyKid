package com.mike.findmykid.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import com.mike.findmykid.data.SettingsManager;
import com.mike.findmykid.service.TrackingService;
import com.mike.findmykid.tracker.Logger;

/**
 * Watchdog receiver that periodically checks if the TrackingService is alive
 * AND actively executing tasks. Detects both dead services and "zombie" services
 * (alive but not executing). Scheduled via AlarmManager to survive Doze mode.
 */
public class ServiceWatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = "ServiceWatchdog";
    private static final long WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes

    // If no heartbeat for this duration, consider the service a zombie
    // Should be significantly larger than the max tracking interval to avoid false positives
    private static final long ZOMBIE_THRESHOLD_MS = 30 * 60 * 1000L; // 30 minutes

    @Override
    public void onReceive(Context context, Intent intent) {
        Logger.log(context, TAG, "Watchdog alarm fired");

        SettingsManager settingsManager = new SettingsManager(context);

        if (!settingsManager.isServiceEnabled()) {
            Logger.log(context, TAG, "Service is disabled in settings, not restarting");
            return;
        }

        // Check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Logger.log(context, TAG, "Location permission not granted, not restarting");
                return;
            }
        }

        boolean isRunning = TrackingService.isRunning();
        long lastHeartbeat = TrackingService.getLastHeartbeat();
        long lastTaskExecution = TrackingService.getLastTaskExecution();
        long now = System.currentTimeMillis();

        Logger.log(context, TAG, "Service status:" 
            + " isRunning=" + isRunning
            + ", lastHeartbeat=" + (lastHeartbeat > 0 ? ((now - lastHeartbeat) / 1000) + "s ago" : "never")
            + ", lastTaskExecution=" + (lastTaskExecution > 0 ? ((now - lastTaskExecution) / 1000) + "s ago" : "never"));

        boolean needsRestart = false;
        String reason = "";

        if (!isRunning) {
            needsRestart = true;
            reason = "Service is NOT running";
        } else if (lastHeartbeat > 0 && (now - lastHeartbeat) > ZOMBIE_THRESHOLD_MS) {
            // Service says it's running but no heartbeat for too long — zombie!
            needsRestart = true;
            reason = "Service is ZOMBIE (no heartbeat for " + ((now - lastHeartbeat) / 1000 / 60) + " min)";
        }

        if (needsRestart) {
            Logger.log(context, TAG, reason + " — restarting service...");

            // First, try to stop the old service if it's a zombie
            if (isRunning) {
                try {
                    context.stopService(new Intent(context, TrackingService.class));
                    Logger.log(context, TAG, "Stopped zombie service");
                    // Small delay to allow cleanup
                    Thread.sleep(500);
                } catch (Exception e) {
                    Logger.log(context, TAG, "Error stopping zombie: " + e.getMessage());
                }
            }

            // Start fresh service
            Intent serviceIntent = new Intent(context, TrackingService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Logger.log(context, TAG, "Service restart initiated");
            } catch (Exception e) {
                Logger.log(context, TAG, "Failed to restart service: " + e.getMessage());
            }
        } else {
            Logger.log(context, TAG, "TrackingService is running and healthy");
        }

        // Always reschedule watchdog
        scheduleWatchdog(context);
    }

    /**
     * Schedules the next watchdog alarm. Uses setExactAndAllowWhileIdle
     * to work even in Doze mode.
     */
    public static void scheduleWatchdog(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(context, ServiceWatchdogReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 1001, intent, flags);

        long triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
        }

        Logger.log(context, TAG, "Watchdog scheduled in " + (WATCHDOG_INTERVAL_MS / 1000 / 60) + " minutes");
    }

    /**
     * Cancels the watchdog alarm.
     */
    public static void cancelWatchdog(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(context, ServiceWatchdogReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 1001, intent, flags);
        alarmManager.cancel(pendingIntent);
    }
}
