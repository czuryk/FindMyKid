package com.mike.findmykid.tracker;

import android.content.Context;
import android.util.Log;

import com.mike.findmykid.data.SettingsManager;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Logger {
    private static final String TAG = "FindMyKidLogger";
    private static final String LOG_DIR = "logs";
    private static final Object LOCK = new Object();

    // Keep max log file size to 2MB to avoid filling up storage
    private static final long MAX_LOG_FILE_SIZE = 2 * 1024 * 1024;

    public static void log(Context context, String module, String message) {
        // Always log to logcat regardless of debug log setting
        Log.d(TAG, "[" + module + "] " + message);

        if (context == null) return;

        // Skip file logging if debug log is disabled
        try {
            SettingsManager settings = new SettingsManager(context);
            if (!settings.isDebugLogEnabled()) return;
        } catch (Exception e) {
            // If we can't read settings, skip file logging to be safe
            return;
        }

        synchronized (LOCK) {
            FileWriter writer = null;
            try {
                File dir = new File(context.getExternalFilesDir(null), LOG_DIR);
                if (!dir.exists()) {
                    dir.mkdirs();
                }

                String dateFormatted = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                File logFile = new File(dir, "log_" + dateFormatted + ".txt");

                // Check file size to prevent filling storage
                if (logFile.exists() && logFile.length() > MAX_LOG_FILE_SIZE) {
                    // Rotate: rename current file and start fresh
                    File rotated = new File(dir, "log_" + dateFormatted + "_old.txt");
                    if (rotated.exists()) {
                        rotated.delete();
                    }
                    logFile.renameTo(rotated);
                    logFile = new File(dir, "log_" + dateFormatted + ".txt");
                }

                String timeFormatted = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
                String threadName = Thread.currentThread().getName();
                String logEntry = timeFormatted + " [" + threadName + "] [" + module + "] " + message + "\n";

                writer = new FileWriter(logFile, true);
                writer.append(logEntry);
                writer.flush();
            } catch (Exception e) {
                Log.e(TAG, "Error writing log to file", e);
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (Exception ignored) {}
                }
            }
        }
    }
}
