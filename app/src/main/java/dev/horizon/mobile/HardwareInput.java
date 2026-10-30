package dev.horizon.mobile;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Hardware keyboard, mouse and gamepad for the game.
 *
 * <p>NativeActivity hands the window's input straight to the native game, so nothing Java overrides on the activity
 * (dispatchKeyEvent and the like) ever sees a key or a stick. And the game does not take gamepads, nor a mouse that
 * must be captured, from there. So this puts one tiny transparent window above the game that takes keyboard focus but
 * no touches (touches go on to the controls under it). Its events arrive in Java, and are sent on the way the
 * on-screen controls send theirs: keys and text, mouse movement and buttons, the virtual pad.
 */
final class HardwareInput {
    interface Host {
        /** A gamepad key: gives it to the virtual pad (or swallows it when the gamepad is off). */
        void padKey(KeyEvent e);
        /** A gamepad stick, trigger or hat. */
        void padMotion(MotionEvent e);
        /** The system's Back: what the activity did before this window took the focus. */
        void back();
        /** This window gained or lost the keyboard focus (the activity must not read that as the game losing focus). */
        void focusChanged(boolean focused);
    }

    private final Context context;
    private final Host host;
    private final WindowManager windows;
    private HwView view;
    private WindowManager.LayoutParams lp;

    HardwareInput(Context context, WindowManager windows, Host host) {
        this.context = context;
        this.windows = windows;
        this.host = host;
    }

    void attach(android.os.IBinder token) {
        if (view != null) return;
        view = new HwView(context);
        lp = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM      // never the target of the on-screen keyboard
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.token = token;
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        windows.addView(view, lp);
    }

    /**
     * The on-screen keyboard can only type into the activity's own window, which must have the focus then: while it
     * is up this window lets go of the focus, and takes it back when the keyboard is gone.
     */
    void setFocusable(boolean focusable) {
        if (view == null) return;
        if (focusable) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try { windows.updateViewLayout(view, lp); } catch (Throwable ignored) {}
    }

    void detach() {
        try { if (view != null) windows.removeViewImmediate(view); } catch (Throwable ignored) {}
        view = null;
    }

    /** True if a mouse is plugged in (not the touch screen). */
    static boolean mouseConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && (d.getSources() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                    && (d.getSources() & InputDevice.SOURCE_TOUCHSCREEN) != InputDevice.SOURCE_TOUCHSCREEN) return true;
        }
        return false;
    }

    private final class HwView extends View {
        private int buttons;

        HwView(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setOnCapturedPointerListener((v, e) -> captured(e));
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            host.focusChanged(hasFocus);
            if (hasFocus) {
                hideSystemUi();
                if (Build.VERSION.SDK_INT >= 26 && mouseConnected()) requestPointerCapture();   // unlimited mouse look, no cursor to hit the screen edge
            } else if (Build.VERSION.SDK_INT >= 26) {
                releasePointerCapture();
            }
        }

        /** The system bars follow the window that has the focus, so this one has to ask for them hidden as well. */
        @SuppressWarnings("deprecation")
        private void hideSystemUi() {
            setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (GamepadInput.isPadKey(e)) {
                host.padKey(e);
                return true;
            }
            int code = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) host.back();
                return true;
            }
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_POWER) return false;
            if (down && e.getRepeatCount() > 0) {
                sendText(e);                                // a held key repeats its letter, not the key press
                return true;
            }
            HorizonInput.nativeKey(0, code, down);
            if (down) sendText(e);
            return true;
        }

        /** The characters a key types, only while the game has a text field in focus (chat, signs, anvil). */
        private void sendText(KeyEvent e) {
            if (e.isCtrlPressed() || e.isAltPressed() || e.isMetaPressed() || !HorizonInput.nativeWantsText()) return;
            int unicode = e.getUnicodeChar();
            if (unicode >= 32 && unicode != 127) HorizonInput.nativeText(new String(Character.toChars(unicode)));
        }

        @Override
        public boolean onGenericMotionEvent(MotionEvent e) {
            if (GamepadInput.isPadMotion(e)) {
                host.padMotion(e);
                return true;
            }
            return super.onGenericMotionEvent(e);
        }

        /** Captured mouse: x and y are movement since the last event, in pixels. */
        private boolean captured(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (e.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) HorizonInput.nativeMouseMove(e.getX(), e.getY());
                    break;
                case MotionEvent.ACTION_SCROLL:
                    HorizonInput.nativeScroll(e.getAxisValue(MotionEvent.AXIS_VSCROLL));
                    break;
                case MotionEvent.ACTION_BUTTON_PRESS:
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    int now = e.getButtonState();
                    int[][] map = {{MotionEvent.BUTTON_PRIMARY, 0}, {MotionEvent.BUTTON_SECONDARY, 1}, {MotionEvent.BUTTON_TERTIARY, 2}};
                    for (int[] m : map) {
                        boolean was = (buttons & m[0]) != 0, is = (now & m[0]) != 0;
                        if (was != is) HorizonInput.nativeMouseButton(m[1], is);
                    }
                    buttons = now;
                    break;
                default:
                    break;
            }
            return true;
        }
    }
}
