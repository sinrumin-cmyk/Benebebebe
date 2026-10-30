package dev.horizon.mobile;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Display;
import android.view.Surface;

import java.util.Arrays;

/**
 * Phone VR, 3DoF: the phone's rotation sensor as the head's orientation. Yaw, pitch and roll go to the game in
 * degrees (HorizonInput.nativeHead): yaw grows turning right, pitch is positive looking down, roll is positive
 * tilting the head to the right. Only the change of yaw and pitch matters to the game, so the sensor's own zero
 * (and any drift of it) never shows.
 */
final class VrSensors implements SensorEventListener {
    private final SensorManager sm;
    private final Sensor sensor;
    private final Display display;
    private final float[] raw = new float[9];
    private final float[] mapped = new float[9];
    /** Kept when looking straight up or down, where the heading is undefined. */
    private float yaw;

    VrSensors(Activity a) {
        sm = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = null;
        if (sm != null) {
            // The game rotation vector is gyroscope + accelerometer: no compass jumps inside a headset.
            s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        sensor = s;
        display = a.getWindowManager().getDefaultDisplay();
    }

    boolean available() {
        return sensor != null;
    }

    void start() {
        if (sensor != null) sm.registerListener(this, sensor, 5000); // 200 Hz, the most that needs no permission
    }

    void stop() {
        if (sm != null) sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float[] v = e.values.length > 4 ? Arrays.copyOf(e.values, 4) : e.values;
        SensorManager.getRotationMatrixFromVector(raw, v);
        int ax, ay;
        switch (display.getRotation()) {
            case Surface.ROTATION_90:  ax = SensorManager.AXIS_Y;       ay = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180: ax = SensorManager.AXIS_MINUS_X; ay = SensorManager.AXIS_MINUS_Y; break;
            case Surface.ROTATION_270: ax = SensorManager.AXIS_MINUS_Y; ay = SensorManager.AXIS_X;       break;
            default:                   ax = SensorManager.AXIS_X;       ay = SensorManager.AXIS_Y;       break;
        }
        if (!SensorManager.remapCoordinateSystem(raw, ax, ay, mapped)) return;
        // mapped's columns are the screen's right, up and back axes in the world (x east, y north, z up).
        float rz = mapped[6], uz = mapped[7];
        float fx = -mapped[2], fy = -mapped[5], fz = -mapped[8]; // forward = minus back
        if (Math.hypot(fx, fy) > 0.08) yaw = (float) Math.toDegrees(Math.atan2(fx, fy)); // clockwise from north
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, fz))));
        float roll = (float) Math.toDegrees(Math.atan2(-rz, uz));
        HorizonInput.nativeHead(yaw, pitch, roll);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
