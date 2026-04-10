package com.mike.findmykid.ui;

import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import com.mike.findmykid.R;
import com.mike.findmykid.data.SettingsManager;
import com.mike.findmykid.receiver.ServiceWatchdogReceiver;
import com.mike.findmykid.service.ServiceGuardWorker;
import com.mike.findmykid.service.TrackingService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {
    private SettingsManager settingsManager;
    private MaterialSwitch switchService;
    private MaterialSwitch switchRinger;
    private MaterialSwitch switchScreen;
    private MaterialSwitch switchDebugLog;
    
    private boolean isNavigatingAway = false;
    private boolean requiresAuth = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settingsManager = new SettingsManager(this);

        TextInputEditText etEndpoint = findViewById(R.id.etEndpoint);
        TextInputEditText etInterval = findViewById(R.id.etInterval);
        MaterialSwitch switchLocation = findViewById(R.id.switchLocation);
        MaterialSwitch switchApp = findViewById(R.id.switchApp);
        switchRinger = findViewById(R.id.switchRinger);
        switchScreen = findViewById(R.id.switchScreen);
        switchService = findViewById(R.id.switchService);
        MaterialButton btnPermissions = findViewById(R.id.btnPermissions);
        MaterialButton btnTest = findViewById(R.id.btnTest);
        MaterialButton btnSave = findViewById(R.id.btnSave);
        MaterialButton btnSendLogs = findViewById(R.id.btnSendLogs);
        MaterialButton btnClearLogs = findViewById(R.id.btnClearLogs);
        switchDebugLog = findViewById(R.id.switchDebugLog);
        TextView tvStatus = findViewById(R.id.tvStatus);

        // Load existing
        etEndpoint.setText(settingsManager.getEndpointUrl());
        etInterval.setText(String.valueOf(settingsManager.getUpdateInterval()));
        switchLocation.setChecked(settingsManager.isTrackLocation());
        switchApp.setChecked(settingsManager.isTrackApp());
        switchRinger.setChecked(settingsManager.isTrackRinger());
        switchScreen.setChecked(settingsManager.isTrackScreen());
        switchService.setChecked(settingsManager.isServiceEnabled());
        switchDebugLog.setChecked(settingsManager.isDebugLogEnabled());

        long lastSync = settingsManager.getLastSuccessSync();
        if (lastSync > 0) {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(lastSync));
            tvStatus.setText("Status: Last success " + time);
        }

        // Service toggle acts immediately — no need to press Save
        // Check permissions BEFORE enabling — block if not all granted
        switchService.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return; // Ignore programmatic changes
            
            if (isChecked) {
                // Check all permissions FIRST before enabling the service
                if (!checkAllPermissionsGranted()) {
                    // Revert the switch immediately — do NOT start the service
                    switchService.setChecked(false);
                    showMissingPermissionsDialog();
                    return;
                }
                
                settingsManager.setServiceEnabled(true);
                boolean started = tryStartTrackingService();
                if (started) {
                    ServiceWatchdogReceiver.scheduleWatchdog(this);
                    ServiceGuardWorker.schedule(this);
                    Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show();
                } else {
                    // Revert the switch if service failed to start
                    switchService.setChecked(false);
                    settingsManager.setServiceEnabled(false);
                }
            } else {
                settingsManager.setServiceEnabled(false);
                stopTrackingService();
                ServiceWatchdogReceiver.cancelWatchdog(this);
                ServiceGuardWorker.cancel(this);
                Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show();
            }
        });

        btnPermissions.setOnClickListener(v -> requestPermissions());

        btnClearLogs.setOnClickListener(v -> clearLogs());
        btnSendLogs.setOnClickListener(v -> sendLogs());

        // Debug log toggle — acts immediately, no need to press Save
        switchDebugLog.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            settingsManager.setDebugLogEnabled(isChecked);
            Toast.makeText(this, isChecked ? "Debug logging enabled" : "Debug logging disabled", Toast.LENGTH_SHORT).show();
        });

        btnTest.setOnClickListener(v -> {
            String url = etEndpoint.getText().toString();
            if (url.isEmpty()) {
                Toast.makeText(this, "Url Not Set", Toast.LENGTH_SHORT).show();
                return;
            }
            tvStatus.setText("Status: Testing connection...");
            new Thread(() -> {
                boolean success = com.mike.findmykid.network.NetworkSender.sendData(url, 100, null, "Test App", "NORMAL", true);
                runOnUiThread(() -> {
                    if (success) {
                        tvStatus.setText("Status: Test successful");
                    } else {
                        tvStatus.setText("Status: Test failed");
                    }
                });
            }).start();
        });

        btnSave.setOnClickListener(v -> {
            String url = etEndpoint.getText().toString();
            if (url.isEmpty()) {
                Toast.makeText(this, "Url Not Set", Toast.LENGTH_SHORT).show();
                return;
            }
            settingsManager.setEndpointUrl(url);
            try {
                settingsManager.setUpdateInterval(Integer.parseInt(etInterval.getText().toString()));
            } catch (NumberFormatException e) {
                // Ignore parsing errors for now, fall back to default
            }
            settingsManager.setTrackLocation(switchLocation.isChecked());
            settingsManager.setTrackApp(switchApp.isChecked());
            settingsManager.setTrackRinger(switchRinger.isChecked());
            settingsManager.setTrackScreen(switchScreen.isChecked());

            // Service state is NOT touched here — it's handled by the switch listener

            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show();

            // After saving, check if all permissions are granted and warn if not
            if (!checkAllPermissionsGranted()) {
                showMissingPermissionsDialog();
            }
        });
    }

    private void clearLogs() {
        java.io.File dir = new java.io.File(getExternalFilesDir(null), "logs");
        if (dir.exists() && dir.isDirectory()) {
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    file.delete();
                }
            }
        }
        Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
    }

    private void sendLogs() {
        java.io.File dir = new java.io.File(getExternalFilesDir(null), "logs");
        if (!dir.exists() || !dir.isDirectory() || dir.listFiles() == null || dir.listFiles().length == 0) {
            Toast.makeText(this, "No logs found", Toast.LENGTH_SHORT).show();
            return;
        }

        java.io.File[] files = dir.listFiles();
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        if (files != null) {
            for (java.io.File file : files) {
                try {
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                    uris.add(uri);
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                }
            }
        }

        if (uris.isEmpty()) return;

        Intent sendIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        sendIntent.setType("text/plain");
        sendIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, "FindMyKid Logs");
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(sendIntent, "Send logs via"));
    }

    private void requestPermissions() {
        isNavigatingAway = true;

        // Sequential permission chain: location → background → notification → system settings
        // Android can only show ONE permission dialog at a time.
        // Calling multiple requestPermissions() simultaneously causes later ones to be dropped.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean hasFineLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) 
                == android.content.pm.PackageManager.PERMISSION_GRANTED;

            if (!hasFineLocation) {
                // Step 1: Request foreground location — chain continues in onRequestPermissionsResult
                requestPermissions(new String[]{
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                }, 100);
                Toast.makeText(this, "After granting location, you'll be asked to select 'Allow all the time'", Toast.LENGTH_LONG).show();
                return; // STOP — next steps will be triggered from onRequestPermissionsResult
            }
        }

        // Foreground location already granted — try background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            boolean hasBgLocation = checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) 
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!hasBgLocation) {
                requestBackgroundLocationPermission();
                return; // STOP — chain continues from onRequestPermissionsResult code 101
            }
        }

        // All location permissions granted — try notification
        requestNotificationPermissionOrContinue();
    }

    /**
     * Requests POST_NOTIFICATIONS if needed, otherwise proceeds to system settings.
     * Called after all location permissions are settled.
     */
    private void requestNotificationPermissionOrContinue() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.POST_NOTIFICATIONS
                }, 102);
                return; // STOP — chain continues from onRequestPermissionsResult code 102
            }
        }

        // All runtime permissions granted — open system settings if needed
        openSystemSettingsIfNeeded();
    }

    /**
     * Opens Usage Stats and Battery Optimization settings only if not yet granted.
     * Called at the end of the sequential permission chain.
     */
    private void openSystemSettingsIfNeeded() {
        if (!isUsageStatsPermissionGranted()) {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Requests ACCESS_BACKGROUND_LOCATION permission.
     * On Android 10 (API 29), this can be requested via requestPermissions().
     * On Android 11+ (API 30+), the system requires the user to go to Settings manually,
     * but we can still try requestPermissions() which will redirect to settings on some devices.
     */
    private void requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            boolean hasBgLocation = checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) 
                == android.content.pm.PackageManager.PERMISSION_GRANTED;

            if (!hasBgLocation) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestPermissions(new String[]{
                            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }, 101);
                    Toast.makeText(this, "Please select 'Allow all the time' for location", Toast.LENGTH_LONG).show();
                } else {
                    requestPermissions(new String[]{
                            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }, 101);
                }
            } else {
                // Background location already granted — continue chain
                requestNotificationPermissionOrContinue();
            }
        } else {
            // Pre-Android 10 — no background location needed, continue chain
            requestNotificationPermissionOrContinue();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 100) {
            // Foreground location result → chain to background location
            boolean locationGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (android.Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i]) 
                    && grantResults[i] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    locationGranted = true;
                }
            }
            if (locationGranted) {
                requestBackgroundLocationPermission();
            } else {
                Toast.makeText(this, "Location permission denied. Service cannot track location.", Toast.LENGTH_LONG).show();
                // Still continue chain for other permissions
                requestNotificationPermissionOrContinue();
            }
        } else if (requestCode == 101) {
            // Background location result → chain to notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                boolean bgGranted = checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) 
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (bgGranted) {
                    Toast.makeText(this, "Background location granted!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Background location denied. Location won't work when app is closed.", Toast.LENGTH_LONG).show();
                }
            }
            // Continue chain
            requestNotificationPermissionOrContinue();
        } else if (requestCode == 102) {
            // Notification result → chain to system settings
            openSystemSettingsIfNeeded();
        }
    }

    /**
     * Tries to start the tracking service. Returns true on success, false on failure.
     * Shows Toast with error message on failure.
     */
    private boolean tryStartTrackingService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Location permission is required. Please grant permission and try again.", Toast.LENGTH_LONG).show();
                return false;
            }
        }

        Intent serviceIntent = new Intent(this, TrackingService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Failed to start service: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
            return false;
        }
    }

    private void stopTrackingService() {
        Intent serviceIntent = new Intent(this, TrackingService.class);
        stopService(serviceIntent);
    }

    /**
     * Checks whether ALL required permissions and system states are granted.
     * Returns true only if everything is in order.
     */
    private boolean checkAllPermissionsGranted() {
        // 1. Fine Location
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        // 2. Background Location (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        // 3. POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        // 4. Location services enabled (GPS or Network)
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm != null) {
            boolean gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean networkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (!gpsEnabled && !networkEnabled) {
                return false;
            }
        }

        // 5. Usage Stats access
        if (!isUsageStatsPermissionGranted()) {
            return false;
        }

        // 6. Battery optimization ignored
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return false;
        }

        return true;
    }

    /**
     * Returns a list of human-readable descriptions of missing permissions/states.
     */
    private List<String> getMissingPermissions() {
        List<String> missing = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add("\u2022 Location permission");
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add("\u2022 Background location (Allow all the time)");
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add("\u2022 Notification permission");
            }
        }

        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm != null) {
            boolean gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean networkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            if (!gpsEnabled && !networkEnabled) {
                missing.add("\u2022 Location services (GPS/Network disabled)");
            }
        }

        if (!isUsageStatsPermissionGranted()) {
            missing.add("\u2022 Usage Stats access");
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            missing.add("\u2022 Battery optimization exemption");
        }

        return missing;
    }

    /**
     * Checks if Usage Stats permission is granted via AppOpsManager.
     */
    private boolean isUsageStatsPermissionGranted() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Shows a dialog listing missing permissions with Grant and Cancel buttons.
     * Grant button reuses the existing requestPermissions() flow.
     */
    private void showMissingPermissionsDialog() {
        List<String> missing = getMissingPermissions();
        if (missing.isEmpty()) return;

        String details = String.join("\n", missing);

        new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("Not all required permissions have been granted:\n\n" + details)
                .setPositiveButton("Grant", (dialog, which) -> requestPermissions())
                .setNegativeButton("Cancel", null)
                .setCancelable(true)
                .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && !isNavigatingAway) {
            requiresAuth = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (requiresAuth) {
            Intent intent = new Intent(this, AuthActivity.class);
            intent.putExtra("from_settings", true);
            startActivityForResult(intent, 1001);
        }
        isNavigatingAway = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001) {
            if (resultCode == RESULT_OK) {
                requiresAuth = false;
            } else {
                finish(); // Close settings
            }
        }
    }
}
