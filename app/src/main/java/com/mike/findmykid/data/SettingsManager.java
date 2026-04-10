package com.mike.findmykid.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

public class SettingsManager {
    private static final String TAG = "SettingsManager";
    private static final String PREF_FILE_NAME = "secret_settings";
    private static final String FALLBACK_PREF_FILE = "settings_fallback";
    
    private static final String KEY_MASTER_PASSWORD_HASH = "pref_master_password_hash";
    private static final String KEY_ENDPOINT_URL = "pref_endpoint_url";
    private static final String KEY_UPDATE_INTERVAL = "pref_update_interval";
    private static final String KEY_SERVICE_ENABLED = "pref_is_service_enabled";
    private static final String KEY_TRACK_LOCATION = "pref_track_location";
    private static final String KEY_TRACK_APP = "pref_track_app";
    private static final String KEY_TRACK_RINGER = "pref_track_ringer";
    private static final String KEY_TRACK_SCREEN = "pref_track_screen";
    private static final String KEY_DEBUG_LOG_ENABLED = "pref_debug_log_enabled";

    private SharedPreferences sharedPrefs;
    private boolean usingFallback = false;

    public SettingsManager(Context context) {
        // Try encrypted preferences first
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            sharedPrefs = EncryptedSharedPreferences.create(
                    context,
                    PREF_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Throwable t) {
            // IMPORTANT: Catch Throwable, not just GeneralSecurityException | IOException.
            // EncryptedSharedPreferences can throw unchecked exceptions like:
            // - ProviderException (KeyStore provider issue after cold boot)
            // - AndroidKeyStoreException (key invalidated)
            // - KeyStoreException, InvalidKeyException
            // If we only catch checked exceptions, these unchecked ones crash the entire process,
            // which is exactly what was killing the service after cold starts.
            Log.e(TAG, "Failed to create EncryptedSharedPreferences: " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            // Fallback to regular SharedPreferences to keep the service alive
            // This is better than having sharedPrefs=null which would disable the service
            try {
                sharedPrefs = context.getSharedPreferences(FALLBACK_PREF_FILE, Context.MODE_PRIVATE);
                usingFallback = true;
                Log.w(TAG, "Using fallback (unencrypted) SharedPreferences");
            } catch (Throwable t2) {
                Log.e(TAG, "Even fallback SharedPreferences failed: " + t2.getMessage(), t2);
            }
        }
    }

    public boolean isUsingFallback() {
        return usingFallback;
    }

    public void setMasterPasswordHash(String hash) {
        if (sharedPrefs != null) {
            sharedPrefs.edit().putString(KEY_MASTER_PASSWORD_HASH, hash).apply();
        }
    }

    public String getMasterPasswordHash() {
        return sharedPrefs != null ? sharedPrefs.getString(KEY_MASTER_PASSWORD_HASH, null) : null;
    }
    
    public boolean isPasswordSet() {
        return getMasterPasswordHash() != null;
    }

    public void setEndpointUrl(String url) {
        if (sharedPrefs != null) {
            sharedPrefs.edit().putString(KEY_ENDPOINT_URL, url).apply();
        }
    }

    public String getEndpointUrl() {
        return sharedPrefs != null ? sharedPrefs.getString(KEY_ENDPOINT_URL, "") : "";
    }

    public void setUpdateInterval(int intervalMinutes) {
        if (sharedPrefs != null) {
            sharedPrefs.edit().putInt(KEY_UPDATE_INTERVAL, intervalMinutes).apply();
        }
    }

    public int getUpdateInterval() {
        return sharedPrefs != null ? sharedPrefs.getInt(KEY_UPDATE_INTERVAL, 15) : 15;
    }

    public void setServiceEnabled(boolean enabled) {
        if (sharedPrefs != null) {
            sharedPrefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply();
        }
    }

    public boolean isServiceEnabled() {
        // CRITICAL: If sharedPrefs is null, return true (not false!)
        // Returning false would cause the service to stop itself, which is the opposite
        // of what we want when there's a KeyStore/crypto issue.
        if (sharedPrefs == null) {
            Log.e(TAG, "sharedPrefs is null in isServiceEnabled(), returning true to keep service alive");
            return true;
        }
        return sharedPrefs.getBoolean(KEY_SERVICE_ENABLED, false);
    }

    public void setTrackLocation(boolean track) {
        if (sharedPrefs != null) {
            sharedPrefs.edit().putBoolean(KEY_TRACK_LOCATION, track).apply();
        }
    }

    public boolean isTrackLocation() {
        return sharedPrefs == null || sharedPrefs.getBoolean(KEY_TRACK_LOCATION, true);
    }

    public void setTrackApp(boolean track) {
        if (sharedPrefs != null) {
            sharedPrefs.edit().putBoolean(KEY_TRACK_APP, track).apply();
        }
    }

    public boolean isTrackApp() {
        return sharedPrefs == null || sharedPrefs.getBoolean(KEY_TRACK_APP, true);
    }

    public void setTrackRinger(boolean track) {
        if (sharedPrefs != null) {
            sharedPrefs.edit().putBoolean(KEY_TRACK_RINGER, track).apply();
        }
    }

    public boolean isTrackRinger() {
        return sharedPrefs == null || sharedPrefs.getBoolean(KEY_TRACK_RINGER, true);
    }

    public void setTrackScreen(boolean track) {
        if (sharedPrefs != null) {
            sharedPrefs.edit().putBoolean(KEY_TRACK_SCREEN, track).apply();
        }
    }

    public boolean isTrackScreen() {
        return sharedPrefs == null || sharedPrefs.getBoolean(KEY_TRACK_SCREEN, true);
    }
    
    public void setDebugLogEnabled(boolean enabled) {
        if (sharedPrefs != null) {
            sharedPrefs.edit().putBoolean(KEY_DEBUG_LOG_ENABLED, enabled).apply();
        }
    }

    /**
     * Returns whether debug logging to file is enabled. Default is false (off).
     * When off, no logs are saved to device storage.
     */
    public boolean isDebugLogEnabled() {
        return sharedPrefs != null && sharedPrefs.getBoolean(KEY_DEBUG_LOG_ENABLED, false);
    }

    public void setLastSuccessSync(long timestamp) {
        if (sharedPrefs != null) {
            sharedPrefs.edit().putLong("last_success_sync", timestamp).apply();
        }
    }
    
    public long getLastSuccessSync() {
        return sharedPrefs != null ? sharedPrefs.getLong("last_success_sync", 0) : 0;
    }
}
