package com.mike.findmykid.service;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.mike.findmykid.data.SettingsManager;
import com.mike.findmykid.tracker.Logger;

import java.util.concurrent.TimeUnit;

/**
 * WorkManager-based guard that ensures TrackingService is always running.
 * 
 * WHY WorkManager instead of AlarmManager:
 * - AlarmManager alarms are CANCELLED on force-stop (swipe from recents on Samsung, Xiaomi, etc.)
 * - WorkManager persists its schedule in a SQLite database, so it survives process death
 * - WorkManager is backed by JobScheduler (API 23+) which is not cancelled on force-stop
 * - This is Google's recommended approach for reliable periodic background work
 */
public class ServiceGuardWorker extends Worker {
    private static final String TAG = "ServiceGuard";
    public static final String UNIQUE_WORK_NAME = "service_guard";

    public ServiceGuardWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        try {
            Logger.log(context, TAG, "ServiceGuardWorker fired");

            SettingsManager settingsManager = new SettingsManager(context);

            if (!settingsManager.isServiceEnabled()) {
                Logger.log(context, TAG, "Service is disabled in settings, skipping restart");
                return Result.success();
            }

            // Check permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Logger.log(context, TAG, "Location permission not granted, skipping restart");
                    return Result.success();
                }
            }

            boolean isRunning = TrackingService.isRunning();
            long lastHeartbeat = TrackingService.getLastHeartbeat();
            long now = System.currentTimeMillis();
            long heartbeatAge = lastHeartbeat > 0 ? (now - lastHeartbeat) / 1000 : -1;

            Logger.log(context, TAG, "Service status: isRunning=" + isRunning 
                + ", heartbeatAge=" + heartbeatAge + "s");

            if (!isRunning) {
                Logger.log(context, TAG, "Service is NOT running, starting...");
                startService(context);
            } else if (heartbeatAge > 0 && heartbeatAge > 30 * 60) {
                // Service claims to be running but no heartbeat for 30 minutes - zombie
                Logger.log(context, TAG, "Service is ZOMBIE (no heartbeat for " + (heartbeatAge / 60) + " min), force restarting...");
                try {
                    context.stopService(new Intent(context, TrackingService.class));
                    Thread.sleep(500);
                } catch (Exception e) {
                    Logger.log(context, TAG, "Error stopping zombie: " + e.getMessage());
                }
                startService(context);
            } else {
                Logger.log(context, TAG, "Service is running and healthy");
            }

        } catch (Throwable t) {
            Logger.log(context, TAG, "Error in ServiceGuardWorker: " + t.getMessage());
        }

        return Result.success();
    }

    private void startService(Context context) {
        try {
            Intent serviceIntent = new Intent(context, TrackingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Logger.log(context, TAG, "Service start command issued [PID=" + android.os.Process.myPid() + "]");

            // Wait and verify the service actually survived
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {}

            boolean aliveAfterStart = TrackingService.isRunning();
            long heartbeatAfter = TrackingService.getLastHeartbeat();
            long now = System.currentTimeMillis();
            long heartbeatAge = heartbeatAfter > 0 ? (now - heartbeatAfter) / 1000 : -1;

            Logger.log(context, TAG, "Service survival check after 5s: alive=" + aliveAfterStart 
                + ", heartbeatAge=" + heartbeatAge + "s [PID=" + android.os.Process.myPid() + "]");

            if (!aliveAfterStart) {
                Logger.log(context, TAG, "WARNING: Service died immediately after start!");
            }
        } catch (Exception e) {
            Logger.log(context, TAG, "Failed to start service: " + e.getMessage());
        }
    }

    /**
     * Schedules the periodic guard worker. Should be called when the service is enabled.
     * Uses KEEP policy so it won't reset the schedule if already running.
     */
    public static void schedule(Context context) {
        PeriodicWorkRequest guardWork = new PeriodicWorkRequest.Builder(
                ServiceGuardWorker.class,
                15, TimeUnit.MINUTES)  // Minimum interval for PeriodicWorkRequest
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                guardWork);

        Logger.log(context, TAG, "ServiceGuardWorker scheduled (every 15 min)");
    }

    /**
     * Cancels the periodic guard worker. Should be called when the service is disabled.
     */
    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME);
        Logger.log(context, TAG, "ServiceGuardWorker cancelled");
    }
}
