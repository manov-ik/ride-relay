package com.bajajnav.relay;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import java.util.Locale;

/**
 * Records a ride whenever the cluster link is up: distance, moving time, average
 * and top speed, plus a thinned route trace for the detail dialog.
 *
 * Sampling is deliberately cheap (5 s / 8 m, GPS only) because this runs for the
 * whole ride with the screen off. Junk fixes are dropped rather than smoothed:
 * a 60 m accuracy fix or a jump faster than 216 km/h is not movement, it is a fix
 * taken under a bridge.
 *
 * A ride ends when the link drops, and also after ten minutes without movement so
 * a bike parked with the ignition on does not become one eight-hour trip.
 */
final class TripRecorder {
    private static final long MIN_TRIP_METERS = 200;
    private static final long MIN_TRIP_SECONDS = 60;
    private static final long IDLE_END_MS = 10 * 60 * 1000L;
    private static final long SAMPLE_MS = 5000L;
    private static final float SAMPLE_METERS = 8f;
    private static final float MAX_ACCURACY_M = 60f;
    private static final double MAX_SPEED_MPS = 60.0;
    private static final double MOVING_MPS = 0.8;
    private static final int MAX_POINTS = 1200;

    private static final TripRecorder INSTANCE = new TripRecorder();

    private Context ctx;
    private LocationManager lm;
    private boolean recording;

    private long startedMs;
    private long lastMoveMs;
    private long lastFixMs;
    private long movingMs;
    private double meters;
    private double maxKmh;
    private Location last;
    private StringBuilder points;
    private int pointCount;

    private TripRecorder() {
    }

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            sample(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    /** The bike link just became ready. */
    static void onLinkUp() {
        INSTANCE.start();
    }

    /** The link dropped or the service is going away. */
    static void onLinkDown() {
        INSTANCE.stop();
    }

    /** The Advanced toggle changed. */
    static void refresh() {
        if (RelayService.ridesEnabled) {
            if (ClusterLink.get().isReady()) {
                INSTANCE.start();
            }
        } else {
            INSTANCE.stop();
        }
    }

    static boolean isRecording() {
        return INSTANCE.recording;
    }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private synchronized void start() {
        if (recording) {
            return;
        }
        ctx = RelayService.context();
        if (ctx == null || !RelayService.ridesEnabled || !hasPermission(ctx)) {
            return;
        }
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return;
        }
        String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
        if (!lm.isProviderEnabled(provider)) {
            Lg.w("ride log: no location provider available");
            return;
        }
        recording = true;
        startedMs = System.currentTimeMillis();
        lastMoveMs = startedMs;
        lastFixMs = startedMs;
        movingMs = 0;
        meters = 0;
        maxKmh = 0;
        last = null;
        points = new StringBuilder();
        pointCount = 0;
        try {
            Looper looper = Looper.myLooper();
            if (looper != null) {
                lm.requestLocationUpdates(provider, SAMPLE_MS, SAMPLE_METERS, listener, looper);
            } else {
                lm.requestLocationUpdates(provider, SAMPLE_MS, SAMPLE_METERS, listener);
            }
            Lg.i("ride log: started on " + provider);
        } catch (Exception e) {
            recording = false;
            Lg.w("ride log: start failed: " + e);
        }
    }

    private synchronized void stop() {
        if (!recording) {
            return;
        }
        recording = false;
        if (lm != null) {
            try {
                lm.removeUpdates(listener);
            } catch (Exception ignored) {
            }
        }
        finish();
    }

    private void sample(Location loc) {
        if (loc == null) {
            return;
        }
        long now = loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis();
        if (loc.hasAccuracy() && loc.getAccuracy() > MAX_ACCURACY_M) {
            return;
        }
        if (last != null) {
            double dt = (now - lastFixMs) / 1000.0;
            if (dt <= 0) {
                return;
            }
            float[] out = new float[1];
            Location.distanceBetween(last.getLatitude(), last.getLongitude(),
                    loc.getLatitude(), loc.getLongitude(), out);
            double d = out[0];
            double v = d / dt;
            if (v > MAX_SPEED_MPS) {
                return; // a jump, not a ride
            }
            meters += d;
            double kmh = loc.hasSpeed() ? loc.getSpeed() * 3.6 : v * 3.6;
            if (kmh > maxKmh) {
                maxKmh = kmh;
            }
            if (v > MOVING_MPS) {
                movingMs += Math.round(dt * 1000);
                lastMoveMs = now;
            }
            addPoint(loc, d);
        } else {
            addPoint(loc, Double.MAX_VALUE);
        }
        last = loc;
        lastFixMs = now;

        if (meters > MIN_TRIP_METERS && now - lastMoveMs > IDLE_END_MS) {
            Lg.i("ride log: parked for 10 min, splitting the trip");
            finish();
            recording = false; // let start() arm the next ride
            start();
        }
    }

    private void addPoint(Location loc, double moved) {
        // One point every ~40 m keeps a two-hour ride under a few hundred points,
        // which is plenty for the little trace in the detail dialog.
        if (moved < 40 && pointCount > 0) {
            return;
        }
        if (pointCount >= MAX_POINTS) {
            return;
        }
        if (pointCount > 0) {
            points.append(';');
        }
        points.append(String.format(Locale.US, "%.6f,%.6f",
                loc.getLatitude(), loc.getLongitude()));
        pointCount++;
    }

    /** Writes the ride if it was long enough to be one. */
    private void finish() {
        long ended = System.currentTimeMillis();
        long seconds = Math.max(0, (ended - startedMs) / 1000L);
        boolean worthIt = meters >= MIN_TRIP_METERS && seconds >= MIN_TRIP_SECONDS;
        if (worthIt && ctx != null) {
            TripStore.Trip t = new TripStore.Trip();
            t.started = startedMs;
            t.ended = ended;
            t.meters = meters;
            t.movingSeconds = movingMs / 1000L;
            t.maxKmh = maxKmh;
            t.avgKmh = movingMs > 0 ? (meters / 1000.0) / (movingMs / 3600000.0) : 0;
            t.points = points == null ? "" : points.toString();
            TripStore.get(ctx).insert(t);
            Lg.i(String.format(Locale.US, "ride log: saved %.1f km in %s (avg %.0f, max %.0f)",
                    meters / 1000.0, TripStore.duration(seconds), t.avgKmh, t.maxKmh));
        } else if (meters > 0) {
            Lg.i(String.format(Locale.US, "ride log: discarded %.0f m / %d s",
                    meters, seconds));
        }
        meters = 0;
        movingMs = 0;
        maxKmh = 0;
        last = null;
        points = null;
        pointCount = 0;
    }
}
