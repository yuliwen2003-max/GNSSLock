package com.yuliwen.gnsslock;

import android.app.Activity;
import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

/** Foreground-only compass. Heading is the screen's upper edge, clockwise from north. */
public final class HeadingTracker implements SensorEventListener {
    public interface Listener {
        void onHeading(float degrees, boolean available);
    }

    private static final long DISPATCH_MS = 50;
    private static final long SENSOR_STALE_MS = 2000;
    private static final long LOCATION_FRESH_MS = 120000;
    private static final long VECTOR_PAIR_NS = 1000000000L;
    private static final float SMOOTHING_MS = 180f;

    private final Activity activity;
    private final Listener listener;
    private final SensorManager sensors;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float[] rotation = new float[9];
    private final float[] remapped = new float[9];
    private final float[] orientation = new float[3];
    private final float[] gravity = new float[3];
    private final float[] magnetic = new float[3];
    private Sensor rotationSensor;
    private Sensor gravitySensor;
    private Sensor magneticSensor;
    private boolean started;
    private boolean usingRotation;
    private boolean gravityReady;
    private boolean magneticReady;
    private long gravityTimestamp;
    private long magneticTimestamp;
    private long lastValidEvent;
    private long lastDispatch;
    private long lastSmoothing;
    private boolean smoothed;
    private boolean available;
    private boolean delivered;
    private float heading;
    private float declination;
    private long declinationExpires;
    private int lastDisplayRotation = -1;

    private final Runnable checkFreshness = new Runnable() {
        @Override public void run() {
            if (!started) return;
            if (lastValidEvent == 0 || SystemClock.elapsedRealtime() - lastValidEvent > SENSOR_STALE_MS) {
                unavailable();
            }
            handler.postDelayed(this, 1000);
        }
    };

    public HeadingTracker(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        sensors = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
    }

    /** Call from the Activity's main thread, normally from onResume. */
    public void start() {
        if (started) return;
        started = true;
        resetSamples();
        if (sensors == null) {
            unavailable();
            return;
        }
        rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        usingRotation = rotationSensor != null && sensors.registerListener(
                this, rotationSensor, SensorManager.SENSOR_DELAY_GAME, handler);
        if (!usingRotation) {
            gravitySensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magneticSensor = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            boolean gravityRegistered = gravitySensor != null && sensors.registerListener(
                    this, gravitySensor, SensorManager.SENSOR_DELAY_GAME, handler);
            boolean magneticRegistered = magneticSensor != null && sensors.registerListener(
                    this, magneticSensor, SensorManager.SENSOR_DELAY_GAME, handler);
            if (!gravityRegistered || !magneticRegistered) {
                sensors.unregisterListener(this);
                unavailable();
                return;
            }
        }
        // The UI must not retain an old bearing while waiting for a first valid sample.
        unavailable();
        handler.postDelayed(checkFreshness, 1000);
    }

    /** Call from onPause. No sensor or delayed callback remains registered. */
    public void stop() {
        started = false;
        handler.removeCallbacks(checkFreshness);
        if (sensors != null) sensors.unregisterListener(this);
        resetSamples();
        unavailable();
    }

    /** Keeps only the computed declination, never the supplied position. Null clears it. */
    public void setLocation(Location location) {
        declinationExpires = 0;
        if (location == null || !finite(location.getLatitude()) || !finite(location.getLongitude())
                || Math.abs(location.getLatitude()) > 90 || Math.abs(location.getLongitude()) > 180) return;
        long now = SystemClock.elapsedRealtime();
        long age = location.getElapsedRealtimeNanos() > 0
                ? (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1000000L
                : System.currentTimeMillis() - location.getTime();
        if (age < -2000 || age > LOCATION_FRESH_MS) return;
        float altitude = location.hasAltitude() && finite(location.getAltitude())
                ? (float) location.getAltitude() : 0f;
        GeomagneticField field = new GeomagneticField((float) location.getLatitude(),
                (float) location.getLongitude(), altitude, System.currentTimeMillis());
        declination = field.getDeclination();
        if (!finite(declination)) return;
        declinationExpires = now + LOCATION_FRESH_MS - Math.max(0, age);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!started) return;
        int type = event.sensor.getType();
        if (usingRotation && type != Sensor.TYPE_ROTATION_VECTOR) return;
        if (!usingRotation && type != Sensor.TYPE_ACCELEROMETER && type != Sensor.TYPE_MAGNETIC_FIELD) return;
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            invalidate(type);
            unavailable();
            return;
        }
        if (usingRotation) {
            if (event.values.length < 3 || !validVector(event.values)) {
                unavailable();
                return;
            }
            SensorManager.getRotationMatrixFromVector(rotation, event.values);
        } else {
            if (event.values.length < 3 || !validVector(event.values)) {
                invalidate(type);
                unavailable();
                return;
            }
            if (type == Sensor.TYPE_ACCELEROMETER) {
                copyFiltered(event.values, gravity, gravityReady);
                gravityReady = true;
                gravityTimestamp = event.timestamp;
            } else {
                copyFiltered(event.values, magnetic, magneticReady);
                magneticReady = true;
                magneticTimestamp = event.timestamp;
            }
            if (!gravityReady || !magneticReady) return;
            if (Math.abs(gravityTimestamp - magneticTimestamp) > VECTOR_PAIR_NS
                    || !SensorManager.getRotationMatrix(rotation, null, gravity, magnetic)) {
                unavailable();
                return;
            }
        }
        updateHeading();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (!started || accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE) return;
        if ((usingRotation && sensor == rotationSensor)
                || (!usingRotation && (sensor == gravitySensor || sensor == magneticSensor))) {
            invalidate(sensor.getType());
            unavailable();
        }
    }

    private void updateHeading() {
        int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int x = SensorManager.AXIS_X;
        int y = SensorManager.AXIS_Y;
        switch (displayRotation) {
            case Surface.ROTATION_90: x = SensorManager.AXIS_Y; y = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180: x = SensorManager.AXIS_MINUS_X; y = SensorManager.AXIS_MINUS_Y; break;
            case Surface.ROTATION_270: x = SensorManager.AXIS_MINUS_Y; y = SensorManager.AXIS_X; break;
            default: break;
        }
        if (!SensorManager.remapCoordinateSystem(rotation, x, y, remapped)) {
            unavailable();
            return;
        }
        SensorManager.getOrientation(remapped, orientation);
        long now = SystemClock.elapsedRealtime();
        float degrees = normalize((float) Math.toDegrees(orientation[0])
                + (now < declinationExpires ? declination : 0f));
        if (!finite(degrees)) {
            unavailable();
            return;
        }
        if (!smoothed || displayRotation != lastDisplayRotation) {
            heading = degrees;
            smoothed = true;
        } else {
            // Shortest signed arc avoids a full turn when crossing 359 / 0 degrees.
            float delta = (degrees - heading + 540f) % 360f - 180f;
            float alpha = 1f - (float) Math.exp(-(now - lastSmoothing) / SMOOTHING_MS);
            heading = normalize(heading + alpha * delta);
        }
        lastDisplayRotation = displayRotation;
        lastSmoothing = now;
        lastValidEvent = now;
        if (!available || now - lastDispatch >= DISPATCH_MS) {
            available = true;
            delivered = true;
            lastDispatch = now;
            listener.onHeading(heading, true);
        }
    }

    private void unavailable() {
        smoothed = false;
        if (delivered && !available) return;
        available = false;
        delivered = true;
        listener.onHeading(heading, false);
    }

    private void invalidate(int type) {
        if (type == Sensor.TYPE_ACCELEROMETER) gravityReady = false;
        if (type == Sensor.TYPE_MAGNETIC_FIELD) magneticReady = false;
    }

    private void resetSamples() {
        gravityReady = false;
        magneticReady = false;
        smoothed = false;
        lastValidEvent = 0;
        lastDispatch = 0;
        lastSmoothing = 0;
        lastDisplayRotation = -1;
    }

    private static void copyFiltered(float[] source, float[] target, boolean ready) {
        for (int i = 0; i < 3; i++) target[i] = ready ? target[i] * .75f + source[i] * .25f : source[i];
    }

    private static boolean validVector(float[] values) {
        for (int i = 0; i < 3; i++) if (!finite(values[i])) return false;
        return true;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static float normalize(float degrees) {
        return (degrees % 360f + 360f) % 360f;
    }
}
