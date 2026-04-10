package com.mike.findmykid.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.mike.findmykid.data.SettingsManager;
import com.mike.findmykid.service.ServiceGuardWorker;
import com.mike.findmykid.service.TrackingService;
import com.mike.findmykid.tracker.Logger;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Logger.log(context, TAG, "Boot completed, checking if service should start");

            SettingsManager settingsManager = new SettingsManager(context);
            if (settingsManager.isServiceEnabled()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        Logger.log(context, TAG, "No location permission, skipping");
                        return;
                    }
                }

                // Start the service
                Intent serviceIntent = new Intent(context, TrackingService.class);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    Logger.log(context, TAG, "Service started after boot");
                } catch (Exception e) {
                    Logger.log(context, TAG, "Failed to start service after boot: " + e.getMessage());
                }

                // Schedule all guards
                ServiceWatchdogReceiver.scheduleWatchdog(context);
                ServiceGuardWorker.schedule(context);
            } else {
                Logger.log(context, TAG, "Service is disabled, not starting");
            }
        }
    }
}
