package com.mike.findmykid.network;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import com.mike.findmykid.tracker.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class NetworkSender {
    private static final String TAG = "NetworkSender";

    /**
     * Overload without Context for backward compatibility (used by Test button in SettingsActivity).
     */
    public static boolean sendData(String endpointUrl, int batteryLevel, Location location, String foregroundApp, String ringerMode, Boolean isScreenOn) {
        return sendData(null, endpointUrl, batteryLevel, location, null, foregroundApp, ringerMode, isScreenOn);
    }

    public static boolean sendData(Context context, String endpointUrl, int batteryLevel, Location location, String locationSource, String foregroundApp, String ringerMode, Boolean isScreenOn) {
        if (endpointUrl == null || endpointUrl.isEmpty()) {
            logSafe(context, "Endpoint URL is null or empty");
            return false;
        }

        HttpURLConnection conn = null;
        try {
            JSONObject payload = new JSONObject();
            payload.put("timestamp", System.currentTimeMillis() / 1000L);
            payload.put("battery_level", batteryLevel);
            
            if (location != null) {
                JSONObject locObj = new JSONObject();
                locObj.put("latitude", location.getLatitude());
                locObj.put("longitude", location.getLongitude());
                locObj.put("accuracy_meters", location.getAccuracy());
                if (locationSource != null) {
                    locObj.put("source", locationSource);
                }
                payload.put("location", locObj);
            }

            if (foregroundApp != null) {
                payload.put("foreground_app", foregroundApp);
            } else {
                payload.put("foreground_app", "unknown");
            }

            if (ringerMode != null) {
                payload.put("ringer_mode", ringerMode);
            }

            if (isScreenOn != null) {
                payload.put("is_screen_on", isScreenOn);
            }

            String jsonString = payload.toString();

            URL url = new URL(endpointUrl);
            conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return true;
            } else {
                logSafe(context, "HTTP error: " + code + " for URL: " + endpointUrl);
                // Try to read error body
                try {
                    java.io.InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        byte[] errorBytes = new byte[Math.min(errorStream.available(), 512)];
                        int read = errorStream.read(errorBytes);
                        if (read > 0) {
                            logSafe(context, "Error body: " + new String(errorBytes, 0, read, StandardCharsets.UTF_8));
                        }
                        errorStream.close();
                    }
                } catch (Exception ignored) {}
                return false;
            }

        } catch (java.net.UnknownHostException e) {
            logSafe(context, "DNS resolution failed (no internet?): " + e.getMessage());
            return false;
        } catch (java.net.SocketTimeoutException e) {
            logSafe(context, "Connection timed out: " + e.getMessage());
            return false;
        } catch (java.io.IOException e) {
            logSafe(context, "Network I/O error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } catch (JSONException e) {
            logSafe(context, "JSON error: " + e.getMessage());
            return false;
        } catch (Exception e) {
            logSafe(context, "Unexpected error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
        }
    }

    private static void logSafe(Context context, String message) {
        Log.e(TAG, message);
        if (context != null) {
            try {
                Logger.log(context, TAG, message);
            } catch (Exception ignored) {}
        }
    }
}
