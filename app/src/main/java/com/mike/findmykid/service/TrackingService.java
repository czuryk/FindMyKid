package com.mike.findmykid.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import com.mike.findmykid.R;
import com.mike.findmykid.data.SettingsManager;
import com.mike.findmykid.network.NetworkSender;
import com.mike.findmykid.receiver.ServiceWatchdogReceiver;
import com.mike.findmykid.tracker.AppUsageTracker;
import com.mike.findmykid.tracker.BatteryTracker;
import com.mike.findmykid.tracker.LocationTracker;
import com.mike.findmykid.tracker.Logger;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TrackingService extends Service {
    private static final String CHANNEL_ID = "TrackingServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "TrackingService";
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    private static final String WAKELOCK_TAG = "FindMyKid::TrackingWakeLock";

    // Static flags for watchdog to check
    private static final AtomicBoolean sIsRunning = new AtomicBoolean(false);
    private static final AtomicLong sLastHeartbeat = new AtomicLong(0);
    private static final AtomicLong sLastTaskExecution = new AtomicLong(0);

    private HandlerThread workerThread;
    private Handler workerHandler;
    private SettingsManager settingsManager;
    private LocationTracker locationTracker;
    private AppUsageTracker appUsageTracker;
    private BatteryTracker batteryTracker;
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private final AtomicBoolean isTaskScheduled = new AtomicBoolean(false);

    private Runnable trackingRunnable;

    /**
     * Returns whether the service is currently running.
     */
    public static boolean isRunning() {
        return sIsRunning.get();
    }

    /**
     * Returns the timestamp of the last heartbeat from the worker thread.
     * Used by watchdog to detect a "zombie" service (alive but not executing tasks).
     */
    public static long getLastHeartbeat() {
        return sLastHeartbeat.get();
    }

    /**
     * Returns the timestamp of the last successful task execution.
     */
    public static long getLastTaskExecution() {
        return sLastTaskExecution.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sIsRunning.set(true);
        sLastHeartbeat.set(System.currentTimeMillis());
        Logger.log(this, TAG, "Service onCreate() [PID=" + android.os.Process.myPid() + "]");

        // CRITICAL: Call startForeground() IMMEDIATELY in onCreate().
        // Android kills foreground services that don't call startForeground() within 5-10 seconds.
        // Previously this was in onStartCommand(), which could be delayed.
        try {
            createNotificationChannel();
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("FindMyKid")
                    .setContentText("Syncing data")
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            Logger.log(this, TAG, "Foreground started in onCreate() [PID=" + android.os.Process.myPid() + "]");
        } catch (Throwable t) {
            Logger.log(this, TAG, "FAILED to start foreground in onCreate: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // Create SettingsManager with Throwable protection
        try {
            settingsManager = new SettingsManager(this);
        } catch (Throwable t) {
            Logger.log(this, TAG, "FAILED to create SettingsManager in onCreate: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            // Create a recovery SettingsManager below
        }

        try {
            locationTracker = new LocationTracker(this);
            appUsageTracker = new AppUsageTracker(this);
            batteryTracker = new BatteryTracker(this);
        } catch (Throwable t) {
            Logger.log(this, TAG, "FAILED to create trackers: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // Use HandlerThread instead of ScheduledExecutorService.
        // HandlerThread survives individual task failures — if a posted Runnable throws,
        // the Looper catches it, logs it, and continues processing the message queue.
        // ScheduledExecutorService silently stops scheduling after any unhandled exception.
        startWorkerThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.log(this, TAG, "onStartCommand() called, flags=" + flags + ", startId=" + startId + " [PID=" + android.os.Process.myPid() + "]");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Logger.log(this, TAG, "No location permission, stopping service");
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        // startForeground() is already called in onCreate() — no need to repeat here.
        // Just ensure the notification is up to date.

        // Ensure worker thread is alive
        ensureWorkerThreadAlive();

        // Schedule the tracking task if not already scheduled
        scheduleTrackingTask();

        // Schedule watchdog alarm
        ServiceWatchdogReceiver.scheduleWatchdog(this);

        Logger.log(this, TAG, "onStartCommand() completed successfully");

        return START_STICKY;
    }

    private void startWorkerThread() {
        if (workerThread != null && workerThread.isAlive()) {
            Logger.log(this, TAG, "Worker thread already alive, skipping creation");
            return;
        }

        workerThread = new HandlerThread("TrackingService-Worker");
        workerThread.setUncaughtExceptionHandler((thread, throwable) -> {
            Logger.log(TrackingService.this, TAG,
                "UNCAUGHT EXCEPTION in worker thread: " + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
            // Recreate the worker thread after a crash
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}
            startWorkerThread();
            scheduleTrackingTask();
        });
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        Logger.log(this, TAG, "Worker thread started");
    }

    private void ensureWorkerThreadAlive() {
        if (workerThread == null || !workerThread.isAlive()) {
            Logger.log(this, TAG, "Worker thread is dead, recreating...");
            startWorkerThread();
        }
    }

    private void scheduleTrackingTask() {
        if (isTaskScheduled.getAndSet(true)) {
            Logger.log(this, TAG, "Tracking task already scheduled, skipping");
            return;
        }

        int intervalMinutes = 15; // Default
        try {
            if (settingsManager != null) {
                intervalMinutes = settingsManager.getUpdateInterval();
            }
        } catch (Throwable t) {
            Logger.log(this, TAG, "Error reading interval: " + t.getMessage());
        }
        if (intervalMinutes < 1) intervalMinutes = 1;

        Logger.log(this, TAG, "Scheduling tracking task every " + intervalMinutes + " minutes [PID=" + android.os.Process.myPid() + "]");

        final long intervalMs = intervalMinutes * 60 * 1000L;

        trackingRunnable = new Runnable() {
            @Override
            public void run() {
                Logger.log(TrackingService.this, TAG, ">>> trackingRunnable STARTED [PID=" + android.os.Process.myPid() + "]");

                // Update heartbeat timestamp
                sLastHeartbeat.set(System.currentTimeMillis());

                // Acquire a partial wake lock to ensure the task completes
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wakeLock = null;
                try {
                    if (pm != null) {
                        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
                        wakeLock.acquire(60 * 1000L); // 60 second timeout max
                        Logger.log(TrackingService.this, TAG, "WakeLock acquired");
                    }

                    doTrackingWorkSafe();

                } finally {
                    if (wakeLock != null && wakeLock.isHeld()) {
                        try {
                            wakeLock.release();
                        } catch (Exception ignored) {}
                    }
                }

                Logger.log(TrackingService.this, TAG, "<<< trackingRunnable COMPLETED, next in " + (intervalMs / 1000 / 60) + " min");

                // Schedule next execution
                if (workerHandler != null) {
                    workerHandler.postDelayed(this, intervalMs);
                } else {
                    Logger.log(TrackingService.this, TAG, "workerHandler is null, cannot schedule next task");
                    isTaskScheduled.set(false);
                }
            }
        };

        // Delay first execution by 3 seconds to let the process fully initialize
        if (workerHandler != null) {
            Logger.log(this, TAG, "First tracking task scheduled with 3s delay");
            workerHandler.postDelayed(trackingRunnable, 3000);
        } else {
            Logger.log(this, TAG, "workerHandler is null on scheduleTrackingTask!");
            isTaskScheduled.set(false);
        }
    }

    /**
     * Wraps doTrackingWork with comprehensive error handling.
     * Catches Throwable to prevent any exception from killing the handler thread.
     */
    private void doTrackingWorkSafe() {
        try {
            doTrackingWork();
            consecutiveErrors.set(0);
            sLastTaskExecution.set(System.currentTimeMillis());
        } catch (Throwable t) {
            int errorCount = consecutiveErrors.incrementAndGet();
            try {
                Logger.log(this, TAG,
                    "ERROR in tracking task (" + errorCount + "/" + MAX_CONSECUTIVE_ERRORS + "): "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());

                // Log stack trace for debugging
                StringBuilder sb = new StringBuilder();
                for (StackTraceElement element : t.getStackTrace()) {
                    sb.append("  at ").append(element.toString()).append("\n");
                    if (sb.length() > 500) break; // limit log size
                }
                Logger.log(this, TAG, "Stack trace:\n" + sb.toString());
            } catch (Throwable ignored) {}

            if (errorCount >= MAX_CONSECUTIVE_ERRORS) {
                try {
                    Logger.log(this, TAG, "Too many consecutive errors (" + errorCount + "), restarting worker thread...");
                } catch (Throwable ignored) {}
                consecutiveErrors.set(0);
                
                // Reset and restart
                isTaskScheduled.set(false);
                try {
                    if (workerThread != null) {
                        workerThread.quitSafely();
                    }
                } catch (Throwable ignored) {}
                startWorkerThread();
                scheduleTrackingTask();
            }
        }
    }

    /**
     * The actual tracking work, separated for clarity.
     */
    private void doTrackingWork() {
        Logger.log(this, TAG, "doTrackingWork() enter [PID=" + android.os.Process.myPid() + "]");

        // Re-create settingsManager each time to handle potential KeyStore issues
        try {
            settingsManager = new SettingsManager(this);
            Logger.log(this, TAG, "SettingsManager created OK (fallback=" + settingsManager.isUsingFallback() + ")");
        } catch (Throwable t) {
            Logger.log(this, TAG, "Failed to create SettingsManager: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            // Don't return here — we might still be able to send data with old settingsManager
        }

        if (!settingsManager.isServiceEnabled()) {
            Logger.log(this, TAG, "Service disabled in settings, stopping");
            stopSelf();
            return;
        }

        String url = settingsManager.getEndpointUrl();
        if (url == null || url.isEmpty()) {
            Logger.log(this, TAG, "Endpoint URL is empty, skipping sync");
            return;
        }

        Logger.log(this, TAG, "Starting data sync to: " + url);

        Location location = null;
        String locationSource = null;
        if (settingsManager.isTrackLocation()) {
            if (locationTracker == null) {
                Logger.log(this, TAG, "ERROR: locationTracker is NULL! Recreating...");
                try {
                    locationTracker = new LocationTracker(this);
                } catch (Throwable t) {
                    Logger.log(this, TAG, "Failed to recreate locationTracker: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }

            if (locationTracker != null) {
                try {
                    Logger.log(this, TAG, "Calling getFreshLocation()...");
                    LocationTracker.LocationResult locResult = locationTracker.getFreshLocation();
                    if (locResult != null && locResult.location != null) {
                        location = locResult.location;
                        locationSource = locResult.source;
                        Logger.log(this, TAG, "Location: lat=" + location.getLatitude() 
                            + " lon=" + location.getLongitude() + " source=" + locationSource);
                    } else {
                        Logger.log(this, TAG, "Location: null (locResult=" + locResult + ")");
                    }
                } catch (Throwable t) {
                    Logger.log(this, TAG, "Error getting location: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            } else {
                Logger.log(this, TAG, "locationTracker still null after recreate attempt");
            }
        } else {
            Logger.log(this, TAG, "Location tracking is DISABLED in settings");
        }

        String app = null;
        if (settingsManager.isTrackApp()) {
            try {
                app = appUsageTracker.getForegroundApp();
            } catch (Throwable t) {
                Logger.log(this, TAG, "Error getting foreground app: " + t.getMessage());
            }
        }

        int battery = -1;
        try {
            battery = batteryTracker.getBatteryPercentage();
        } catch (Throwable t) {
            Logger.log(this, TAG, "Error getting battery: " + t.getMessage());
        }

        String ringerMode = null;
        if (settingsManager.isTrackRinger()) {
            try {
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(android.content.Context.AUDIO_SERVICE);
                if (am != null) {
                    switch (am.getRingerMode()) {
                        case android.media.AudioManager.RINGER_MODE_SILENT: ringerMode = "SILENT"; break;
                        case android.media.AudioManager.RINGER_MODE_VIBRATE: ringerMode = "VIBRATE"; break;
                        case android.media.AudioManager.RINGER_MODE_NORMAL: ringerMode = "NORMAL"; break;
                    }
                }
            } catch (Throwable t) {
                Logger.log(this, TAG, "Error getting ringer mode: " + t.getMessage());
            }
        }

        Boolean isScreenOn = null;
        if (settingsManager.isTrackScreen()) {
            try {
                android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) getSystemService(android.content.Context.DISPLAY_SERVICE);
                if (dm != null) {
                    android.view.Display[] displays = dm.getDisplays();
                    if (displays != null && displays.length > 0) {
                        isScreenOn = displays[0].getState() != android.view.Display.STATE_OFF;
                    }
                }
            } catch (Throwable t) {
                Logger.log(this, TAG, "Error getting screen state: " + t.getMessage());
            }
        }

        Logger.log(this, TAG, "Sending data: battery=" + battery + ", app=" + app 
            + ", ringer=" + ringerMode + ", screen=" + isScreenOn + ", locSource=" + locationSource);

        boolean success = NetworkSender.sendData(this, url, battery, location, locationSource, app, ringerMode, isScreenOn);
        if (success) {
            Logger.log(this, TAG, "Sync successful");
            settingsManager.setLastSuccessSync(System.currentTimeMillis());
        } else {
            Logger.log(this, TAG, "Sync FAILED");
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Logger.log(this, TAG, "onTaskRemoved() - app swiped from recents [PID=" + android.os.Process.myPid() + "]");

        // Strategy 1: Try to restart ourselves immediately
        try {
            Intent restartIntent = new Intent(this, TrackingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent);
            } else {
                startService(restartIntent);
            }
            Logger.log(this, TAG, "Self-restart initiated");
        } catch (Exception e) {
            Logger.log(this, TAG, "Self-restart failed: " + e.getMessage());
        }

        // Strategy 2: AlarmManager watchdog (may be cancelled by force-stop on some OEMs)
        ServiceWatchdogReceiver.scheduleWatchdog(this);

        // Strategy 3: WorkManager guard (survives force-stop!)
        try {
            ServiceGuardWorker.schedule(this);
        } catch (Exception e) {
            Logger.log(this, TAG, "Failed to schedule WorkManager guard: " + e.getMessage());
        }

        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Logger.log(this, TAG, "onDestroy() called [PID=" + android.os.Process.myPid() + "]");
        sIsRunning.set(false);

        // Remove pending callbacks
        if (workerHandler != null && trackingRunnable != null) {
            workerHandler.removeCallbacks(trackingRunnable);
        }

        // Quit worker thread
        if (workerThread != null) {
            workerThread.quitSafely();
        }

        isTaskScheduled.set(false);

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Tracking Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Sending coordinates and activity");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
