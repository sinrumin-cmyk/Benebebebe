package dev.horizon.mobile;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * A hardware gamepad (Bluetooth, USB, a phone clip-on) as the game's virtual pad. NativeActivity does not hand
 * gamepad events to the game, so HorizonActivity gives them here first. Button numbers are HorizonInput's.
 */
final class GamepadInput {
    private static final float DEAD = 0.12f;
    private static final float TRIGGER_ON = 0.5f, TRIGGER_OFF = 0.3f;

    private final InputMux.Source keys, axes;
    private boolean lt, rt;
    private int hatX, hatY;

    GamepadInput(InputMux mux) {
        keys = mux.source(InputMux.PAD_KEYS);
        axes = mux.source(InputMux.PAD_AXES);
    }

    /** The game button for an Android keycode, or -1. */
    static int button(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return HorizonInput.SOUTH;
            case KeyEvent.KEYCODE_BUTTON_B: return HorizonInput.EAST;
            case KeyEvent.KEYCODE_BUTTON_X: return HorizonInput.WEST;
            case KeyEvent.KEYCODE_BUTTON_Y: return HorizonInput.NORTH;
            case KeyEvent.KEYCODE_BUTTON_L1: return HorizonInput.LB;
            case KeyEvent.KEYCODE_BUTTON_R1: return HorizonInput.RB;
            case KeyEvent.KEYCODE_BUTTON_L2: return HorizonInput.LT;
            case KeyEvent.KEYCODE_BUTTON_R2: return HorizonInput.RT;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return HorizonInput.SELECT;
            case KeyEvent.KEYCODE_BUTTON_START: return HorizonInput.START;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return HorizonInput.LSTICK;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return HorizonInput.RSTICK;
            case KeyEvent.KEYCODE_DPAD_UP: return HorizonInput.DUP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return HorizonInput.DDOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return HorizonInput.DLEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return HorizonInput.DRIGHT;
            default: return -1;
        }
    }

    static boolean fromPad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** A key a gamepad sent and the game has a button for. */
    static boolean isPadKey(KeyEvent e) {
        return fromPad(e.getSource()) && button(e.getKeyCode()) >= 0;
    }

    static boolean isPadMotion(MotionEvent e) {
        return fromPad(e.getSource()) && e.getAction() == MotionEvent.ACTION_MOVE;
    }

    /** True if a gamepad is connected right now. */
    static boolean connected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && fromPad(d.getSources())) return true;
        }
        return false;
    }

    void onKey(KeyEvent e) {
        int b = button(e.getKeyCode());
        if (b < 0 || e.getRepeatCount() > 0) return;
        keys.button(b, e.getAction() == KeyEvent.ACTION_DOWN);
    }

    void onMotion(MotionEvent e) {
        InputDevice d = e.getDevice();
        axes.stick(false, axis(e, d, MotionEvent.AXIS_X), -axis(e, d, MotionEvent.AXIS_Y));
        // Most pads put the right stick on Z/RZ, some on RX/RY.
        boolean zr = d != null && d.getMotionRange(MotionEvent.AXIS_Z) != null;
        float rx = zr ? axis(e, d, MotionEvent.AXIS_Z) : axis(e, d, MotionEvent.AXIS_RX);
        float ry = zr ? axis(e, d, MotionEvent.AXIS_RZ) : axis(e, d, MotionEvent.AXIS_RY);
        axes.stick(true, rx, -ry);

        lt = trigger(lt, Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)), HorizonInput.LT);
        rt = trigger(rt, Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)), HorizonInput.RT);

        int hx = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_X)), hy = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if (hx != hatX) { axes.button(HorizonInput.DLEFT, hx < 0); axes.button(HorizonInput.DRIGHT, hx > 0); hatX = hx; }
        if (hy != hatY) { axes.button(HorizonInput.DUP, hy < 0); axes.button(HorizonInput.DDOWN, hy > 0); hatY = hy; }
    }

    private boolean trigger(boolean was, float value, int button) {
        boolean now = value > (was ? TRIGGER_OFF : TRIGGER_ON);
        if (now != was) axes.button(button, now);
        return now;
    }

    private static float axis(MotionEvent e, InputDevice d, int axis) {
        float v = e.getAxisValue(axis);
        float flat = 0;
        if (d != null) {
            InputDevice.MotionRange r = d.getMotionRange(axis, e.getSource());
            if (r != null) flat = r.getFlat();
        }
        return Math.abs(v) <= Math.max(flat, DEAD) ? 0f : v;
    }

    void release() {
        keys.release();
        axes.release();
        lt = rt = false;
        hatX = hatY = 0;
    }
}
