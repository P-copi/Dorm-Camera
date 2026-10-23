package com.pcopi.dormcamera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/** Continuously refreshes both GPS and network location while the camera is visible. */
public final class LocationHelper {
    public interface Callback {
        void onLocation(@NonNull Location location);
        void onUnavailable(@NonNull String reason);
    }

    private static final long STALE_AFTER_MS = 5 * 60 * 1000L;
    private final Context context;
    private final LocationManager locationManager;
    @Nullable private Location bestLocation;
    @Nullable private Callback listenerCallback;
    private boolean tracking;

    private final LocationListener trackingListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location fix) {
            consider(fix);
            Location current = bestLocation;
            if (current != null && !isStale(current) && listenerCallback != null) {
                listenerCallback.onLocation(new Location(current));
            }
        }

        @Override public void onProviderDisabled(@NonNull String provider) {
            if (listenerCallback != null && getFreshLocation() == null) {
                listenerCallback.onUnavailable("กำลังค้นหาตำแหน่ง");
            }
        }
        @Override public void onProviderEnabled(@NonNull String provider) { }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };

    public LocationHelper(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public static boolean isStale(@Nullable Location location) {
        if (location == null) return true;
        long age = Math.abs(System.currentTimeMillis() - location.getTime());
        return location.getTime() <= 0L || age > STALE_AFTER_MS;
    }

    @Nullable
    public synchronized Location getBestLocation() {
        if (!hasPermission() || locationManager == null) return copy(bestLocation);
        consider(safeLastKnown(LocationManager.GPS_PROVIDER));
        consider(safeLastKnown(LocationManager.NETWORK_PROVIDER));
        return copy(bestLocation);
    }

    @Nullable
    public Location getFreshLocation() {
        Location location = getBestLocation();
        return isStale(location) ? null : location;
    }

    /** Kept for compatibility with existing MainActivity callbacks. */
    public void refresh(@NonNull Callback callback) {
        startTracking(callback);
    }

    public void startTracking(@NonNull Callback callback) {
        listenerCallback = callback;
        if (!hasPermission()) {
            callback.onUnavailable("ไม่อนุญาต");
            return;
        }
        if (locationManager == null) {
            callback.onUnavailable("ไม่มีบริการตำแหน่ง");
            return;
        }
        Location existing = getFreshLocation();
        if (existing != null) callback.onLocation(existing);

        if (tracking) return;
        boolean registered = false;
        registered |= registerProvider(LocationManager.GPS_PROVIDER);
        registered |= registerProvider(LocationManager.NETWORK_PROVIDER);
        tracking = registered;
        if (!registered && existing == null) {
            callback.onUnavailable("กรุณาเปิดตำแหน่งในเครื่อง");
        } else if (existing == null) {
            callback.onUnavailable("กำลังค้นหาตำแหน่ง");
        }
    }

    public void stopTracking() {
        if (tracking && locationManager != null) {
            try { locationManager.removeUpdates(trackingListener); }
            catch (Exception ignored) { }
        }
        tracking = false;
        listenerCallback = null;
    }

    private boolean registerProvider(String provider) {
        if (locationManager == null) return false;
        try {
            if (!locationManager.isProviderEnabled(provider)) return false;
            locationManager.requestLocationUpdates(provider,
                    1000L, 1f, trackingListener, Looper.getMainLooper());
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
    }

    @Nullable
    private Location safeLastKnown(String provider) {
        try {
            if (!hasPermission() || locationManager == null
                    || !locationManager.isProviderEnabled(provider)) return null;
            return locationManager.getLastKnownLocation(provider);
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private synchronized void consider(@Nullable Location candidate) {
        if (candidate == null || candidate.getTime() <= 0L
                || Double.isNaN(candidate.getLatitude())
                || Double.isNaN(candidate.getLongitude())
                || candidate.getLatitude() < -90 || candidate.getLatitude() > 90
                || candidate.getLongitude() < -180 || candidate.getLongitude() > 180) return;
        if (bestLocation == null || isBetter(candidate, bestLocation)) {
            bestLocation = new Location(candidate);
        }
    }

    private static boolean isBetter(Location candidate, Location current) {
        if (isStale(current) && !isStale(candidate)) return true;
        long difference = candidate.getTime() - current.getTime();
        if (difference > 120_000L) return true;
        if (difference < -120_000L) return false;

        float candidateAccuracy = candidate.hasAccuracy()
                ? candidate.getAccuracy() : Float.MAX_VALUE;
        float currentAccuracy = current.hasAccuracy()
                ? current.getAccuracy() : Float.MAX_VALUE;
        if (difference > 0 && candidateAccuracy <= currentAccuracy + 100f) return true;
        return candidateAccuracy < currentAccuracy;
    }

    @Nullable private static Location copy(@Nullable Location location) {
        return location == null ? null : new Location(location);
    }
}
