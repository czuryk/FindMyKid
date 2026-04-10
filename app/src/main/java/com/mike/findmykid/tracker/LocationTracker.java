package com.mike.findmykid.tracker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LocationTracker {
    private static final String TAG = "LocationTracker";
    // Total timeout for location acquisition
    private static final int TOTAL_TIMEOUT_SECONDS = 25;
    // How long to wait for GPS before falling back to Fused/Network
    private static final int GPS_PRIORITY_WAIT_SECONDS = 10;
    // Fused provider string constant (available on devices with Google Play Services)
    private static final String FUSED_PROVIDER = "fused";

    private final LocationManager locationManager;
    private final Context context;

    /**
     * Result holder that includes both the location and its source.
     */
    public static class LocationResult {
        public final Location location;
        public final String source; // "GPS", "NET", "FUSED", "LAST_GPS", "LAST_NET", "LAST_FUSED", "LAST_PASSIVE"

        public LocationResult(Location location, String source) {
            this.location = location;
            this.source = source;
        }
    }

    public LocationTracker(Context context) {
        this.context = context;
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * Gets a fresh location with GPS priority.
     * Uses requestLocationUpdates() which actually WAITS for a fix, unlike
     * getCurrentLocation() which returns null immediately if no fix is available.
     */
    @SuppressLint("MissingPermission")
    public LocationResult getFreshLocation() {
        try {
            return doGetFreshLocation();
        } catch (Throwable t) {
            try {
                Logger.log(context, TAG, "FATAL error in getFreshLocation: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Throwable ignored) {}
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private LocationResult doGetFreshLocation() {
        if (locationManager == null) {
            Logger.log(context, TAG, "LocationManager is null");
            return null;
        }

        // Log available providers for diagnostics
        List<String> allProviders = null;
        List<String> enabledProviders = null;
        try {
            allProviders = locationManager.getAllProviders();
            enabledProviders = locationManager.getProviders(true);
            Logger.log(context, TAG, "All providers: " + allProviders + ", enabled: " + enabledProviders);
        } catch (Exception e) {
            Logger.log(context, TAG, "Error listing providers: " + e.getMessage());
        }

        boolean hasFused = allProviders != null && allProviders.contains(FUSED_PROVIDER)
                && enabledProviders != null && enabledProviders.contains(FUSED_PROVIDER);
        boolean hasGps = enabledProviders != null && enabledProviders.contains(LocationManager.GPS_PROVIDER);
        boolean hasNetwork = enabledProviders != null && enabledProviders.contains(LocationManager.NETWORK_PROVIDER);

        Logger.log(context, TAG, "Provider availability: fused=" + hasFused + ", GPS=" + hasGps + ", Network=" + hasNetwork);

        // CRITICAL CHECK: On Android 10+ (API 29), when a foreground service is started
        // from the background (e.g. WorkManager, BootReceiver, START_STICKY restart),
        // location callbacks are SILENTLY not delivered unless ACCESS_BACKGROUND_LOCATION
        // ("Allow all the time") is granted. No exception is thrown — callbacks just never fire.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            boolean hasBgPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) 
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean hasFgPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) 
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
            Logger.log(context, TAG, "Permissions: FINE_LOCATION=" + hasFgPermission 
                + ", BACKGROUND_LOCATION=" + hasBgPermission);
            if (!hasBgPermission) {
                Logger.log(context, TAG, "WARNING: ACCESS_BACKGROUND_LOCATION NOT GRANTED! "
                    + "Location will NOT work when service is started from background. "
                    + "User must select 'Allow all the time' in app permissions.");
            }
        }

        // Separate results and latches for each provider
        final AtomicReference<Location> gpsResult = new AtomicReference<>(null);
        final AtomicReference<Location> altResult = new AtomicReference<>(null);
        final CountDownLatch gpsLatch = new CountDownLatch(1);
        final CountDownLatch altLatch = new CountDownLatch(1);

        // Determine the best alternative provider: fused > network
        final String altProvider = hasFused ? FUSED_PROVIDER : (hasNetwork ? LocationManager.NETWORK_PROVIDER : null);
        final String altSourceTag = hasFused ? "FUSED" : "NET";

        // Create listeners
        LocationListener gpsListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                Logger.log(context, TAG, "GPS onLocationChanged: " + location.getLatitude() + ", " + location.getLongitude()
                    + " (accuracy: " + location.getAccuracy() + "m)");
                gpsResult.set(location);
                gpsLatch.countDown();
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {
                Logger.log(context, TAG, "GPS provider disabled while waiting");
                gpsLatch.countDown();
            }
        };

        LocationListener altListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                Logger.log(context, TAG, altSourceTag + " onLocationChanged: " + location.getLatitude() + ", " + location.getLongitude()
                    + " (accuracy: " + location.getAccuracy() + "m)");
                altResult.set(location);
                altLatch.countDown();
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {
                Logger.log(context, TAG, altProvider + " provider disabled while waiting");
                altLatch.countDown();
            }
        };

        // Use a dedicated HandlerThread for callbacks — not main Looper
        HandlerThread locationThread = null;
        try {
            locationThread = new HandlerThread("LocationCallback");
            locationThread.start();
            Looper locationLooper = locationThread.getLooper();

            try {
                // REQUEST BOTH PROVIDERS via requestLocationUpdates().
                // This is key: unlike getCurrentLocation() which returns null immediately,
                // requestLocationUpdates() actually WAITS until the provider gets a fix.
                // minTime=0, minDistance=0 means "deliver as soon as you have anything".

                if (altProvider != null) {
                    locationManager.requestLocationUpdates(altProvider, 0, 0, altListener, locationLooper);
                    Logger.log(context, TAG, "Started listening on " + altProvider);
                }

                if (hasGps) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsListener, locationLooper);
                    Logger.log(context, TAG, "Started listening on GPS");
                }

                if (!hasGps && altProvider == null) {
                    Logger.log(context, TAG, "No location providers available!");
                } else {
                    // PHASE 1: Wait for GPS (priority — more accurate)
                    if (hasGps) {
                        Logger.log(context, TAG, "Phase 1: Waiting " + GPS_PRIORITY_WAIT_SECONDS + "s for GPS...");
                        boolean gpsCompleted = gpsLatch.await(GPS_PRIORITY_WAIT_SECONDS, TimeUnit.SECONDS);

                        if (gpsCompleted && gpsResult.get() != null) {
                            Location gps = gpsResult.get();
                            Logger.log(context, TAG, "GPS responded! " + gps.getLatitude() + ", " + gps.getLongitude()
                                + " (accuracy: " + gps.getAccuracy() + "m)");
                            return new LocationResult(gps, "GPS");
                        }
                        Logger.log(context, TAG, "GPS did not respond within " + GPS_PRIORITY_WAIT_SECONDS + "s");
                    }

                    // PHASE 2: Check if alt provider already responded
                    if (altResult.get() != null) {
                        Location alt = altResult.get();
                        Logger.log(context, TAG, "Using " + altSourceTag + " (already available): "
                            + alt.getLatitude() + ", " + alt.getLongitude()
                            + " (accuracy: " + alt.getAccuracy() + "m)");
                        return new LocationResult(alt, altSourceTag);
                    }

                    // PHASE 3: Wait remaining time for alt provider
                    if (altProvider != null) {
                        int remaining = TOTAL_TIMEOUT_SECONDS - GPS_PRIORITY_WAIT_SECONDS;
                        Logger.log(context, TAG, "Phase 3: Waiting " + remaining + "s for " + altProvider + "...");
                        boolean altCompleted = altLatch.await(remaining, TimeUnit.SECONDS);

                        if (altCompleted && altResult.get() != null) {
                            Location alt = altResult.get();
                            Logger.log(context, TAG, "Got " + altSourceTag + ": "
                                + alt.getLatitude() + ", " + alt.getLongitude()
                                + " (accuracy: " + alt.getAccuracy() + "m)");
                            return new LocationResult(alt, altSourceTag);
                        }
                    }

                    // Check if GPS came back late during phase 3
                    if (gpsResult.get() != null) {
                        Location gps = gpsResult.get();
                        Logger.log(context, TAG, "GPS responded late: " + gps.getLatitude() + ", " + gps.getLongitude());
                        return new LocationResult(gps, "GPS");
                    }

                    Logger.log(context, TAG, "All providers timed out after " + TOTAL_TIMEOUT_SECONDS + "s");
                }

            } catch (SecurityException se) {
                Logger.log(context, TAG, "SecurityException: " + se.getMessage());
            } catch (InterruptedException ie) {
                Logger.log(context, TAG, "Location wait interrupted");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Logger.log(context, TAG, "Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                try { locationManager.removeUpdates(gpsListener); } catch (Exception ignored) {}
                try { locationManager.removeUpdates(altListener); } catch (Exception ignored) {}
            }
        } finally {
            if (locationThread != null) {
                locationThread.quitSafely();
            }
        }

        // PHASE 4: Fallback to last known location
        Logger.log(context, TAG, "Fresh location failed, trying lastKnownLocation fallback...");
        return getLastKnownLocationSafe();
    }

    /**
     * Tries to get last known location from multiple providers.
     * Order: Fused → GPS → Network → Passive. Returns the freshest one.
     */
    @SuppressLint("MissingPermission")
    private LocationResult getLastKnownLocationSafe() {
        Location bestLocation = null;
        String bestProvider = null;

        String[] providers = {
            FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        };

        for (String provider : providers) {
            try {
                List<String> available = locationManager.getAllProviders();
                if (available == null || !available.contains(provider)) {
                    continue;
                }
                Location loc = locationManager.getLastKnownLocation(provider);
                if (loc != null) {
                    long ageMs = System.currentTimeMillis() - loc.getTime();
                    Logger.log(context, TAG, "lastKnown from " + provider + ": "
                        + loc.getLatitude() + ", " + loc.getLongitude()
                        + " (age: " + (ageMs / 1000) + "s, accuracy: " + loc.getAccuracy() + "m)");

                    if (bestLocation == null || loc.getTime() > bestLocation.getTime()) {
                        bestLocation = loc;
                        bestProvider = provider;
                    }
                } else {
                    Logger.log(context, TAG, "lastKnown from " + provider + ": null");
                }
            } catch (SecurityException se) {
                Logger.log(context, TAG, "SecurityException for " + provider + ": " + se.getMessage());
            } catch (Exception e) {
                Logger.log(context, TAG, "Error getting lastKnown from " + provider + ": " + e.getMessage());
            }
        }

        if (bestLocation != null) {
            long ageMs = System.currentTimeMillis() - bestLocation.getTime();
            String sourceTag;
            if (FUSED_PROVIDER.equals(bestProvider)) {
                sourceTag = "LAST_FUSED";
            } else if (LocationManager.GPS_PROVIDER.equals(bestProvider)) {
                sourceTag = "LAST_GPS";
            } else if (LocationManager.NETWORK_PROVIDER.equals(bestProvider)) {
                sourceTag = "LAST_NET";
            } else {
                sourceTag = "LAST_PASSIVE";
            }

            Logger.log(context, TAG, "Best lastKnown: " + bestProvider
                + " (age: " + (ageMs / 1000) + "s), source=" + sourceTag);
            return new LocationResult(bestLocation, sourceTag);
        }

        Logger.log(context, TAG, "No last known location available from any provider");
        return null;
    }
}
