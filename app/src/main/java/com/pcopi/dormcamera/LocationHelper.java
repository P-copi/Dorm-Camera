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
import androidx.core.content.ContextCompat;

public final class LocationHelper {
    public interface Callback {
        void onLocation(@NonNull Location location);
        void onUnavailable(@NonNull String reason);
    }

    private static final long STALE_AFTER_MS = 5 * 60 * 1000L;

    private final Context context;
    private final LocationManager locationManager;
    private Location bestLocation;

    public LocationHelper(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public static boolean isStale(Location location) {
        if (location == null) return true;
        long age = Math.abs(System.currentTimeMillis() - location.getTime());
        return age > STALE_AFTER_MS;
    }

    public Location getBestLocation() {
        if (!hasPermission() || locationManager == null) {
            return bestLocation;
        }

        Location gps = safeLastKnown(LocationManager.GPS_PROVIDER);
        Location network = safeLastKnown(LocationManager.NETWORK_PROVIDER);
        consider(gps);
        consider(network);
        return bestLocation;
    }

    public void refresh(@NonNull Callback callback) {
        if (!hasPermission()) {
            callback.onUnavailable("ไม่อนุญาต");
            return;
        }
        if (locationManager == null) {
            callback.onUnavailable("ไม่พร้อมใช้งาน");
            return;
        }

        Location existing = getBestLocation();
        if (existing != null) {
            callback.onLocation(existing);
        }

        String provider = chooseProvider();
        if (provider == null) {
            if (existing == null) {
                callback.onUnavailable("ปิดในเครื่อง");
            }
            return;
        }

        try {
            locationManager.requestSingleUpdate(
                    provider,
                    new LocationListener() {
                        @Override
                        public void onLocationChanged(@NonNull Location location) {
                            consider(location);
                            Location best = bestLocation != null ? bestLocation : location;
                            callback.onLocation(best);
                        }

                        @Override
                        public void onProviderDisabled(@NonNull String provider) {
                            if (bestLocation == null) {
                                callback.onUnavailable("ปิดในเครื่อง");
                            }
                        }

                        @Override
                        public void onProviderEnabled(@NonNull String provider) {
                        }

                        @Override
                        public void onStatusChanged(String provider, int status, Bundle extras) {
                        }
                    },
                    Looper.getMainLooper()
            );
        } catch (SecurityException e) {
            callback.onUnavailable("ไม่มีสิทธิ์");
        } catch (IllegalArgumentException e) {
            if (existing == null) {
                callback.onUnavailable("ไม่พบผู้ให้บริการตำแหน่ง");
            }
        }
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String chooseProvider() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
        } catch (Exception ignored) {
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private Location safeLastKnown(String provider) {
        try {
            if (!hasPermission() || locationManager == null) return null;
            return locationManager.getLastKnownLocation(provider);
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    private void consider(Location candidate) {
        if (candidate == null) return;
        if (bestLocation == null || isBetter(candidate, bestLocation)) {
            bestLocation = new Location(candidate);
        }
    }

    private boolean isBetter(Location candidate, Location current) {
        long timeDelta = candidate.getTime() - current.getTime();

        if (timeDelta > 120_000L) return true;
        if (timeDelta < -120_000L) return false;

        float candidateAccuracy = candidate.hasAccuracy()
                ? candidate.getAccuracy()
                : Float.MAX_VALUE;
        float currentAccuracy = current.hasAccuracy()
                ? current.getAccuracy()
                : Float.MAX_VALUE;

        if (timeDelta > 0 && candidateAccuracy <= currentAccuracy + 100f) {
            return true;
        }
        return candidateAccuracy < currentAccuracy;
    }
}
