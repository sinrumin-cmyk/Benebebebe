package dev.horizon.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** The launcher's settings as the game process reads them. Same file as the launcher's own preferences. */
final class Settings {
    private Settings() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    /** Size of the layout's controls in percent, like MojoLauncher's button size. */
    static int layoutScale(Context c) {
        return Math.max(50, Math.min(300, prefs(c).getInt("layout_scale", 100)));
    }

    static boolean showFps(Context c) {
        return prefs(c).getBoolean("show_fps", false);
    }

    /** Tap on the screen attacks / places, a held finger breaks blocks. Off: the buttons and the mouse do that. */
    static boolean touchGestures(Context c) {
        return prefs(c).getBoolean("touch_gestures", false);
    }

    /** Start with the virtual mouse (a touchpad cursor) on. */
    static boolean virtualMouse(Context c) {
        return prefs(c).getBoolean("virtual_mouse", false);
    }

    /** Skip Horizon's main menu and open the world from the launcher straight away. */
    static boolean startInWorld(Context c) {
        return prefs(c).getBoolean("start_in_world", false);
    }

    /** Phone VR: two eyes side by side, the phone's rotation sensor turns the head (HORIZON_VR=1 for the game). */
    static boolean vr(Context c) {
        return prefs(c).getBoolean("vr", false);
    }

    /** How the game is controlled besides the touch screen: 1 hands only, 2 hands and gamepad, 3 gamepad only. */
    static final int MODE_HANDS = 1, MODE_MIXED = 2, MODE_PAD = 3;

    static int inputMode(Context c) {
        int m = prefs(c).getInt("input_mode", MODE_PAD);
        return m < MODE_HANDS || m > MODE_PAD ? MODE_PAD : m;
    }

    static String inputModeName(int mode) {
        return mode == MODE_HANDS ? "Только руки" : mode == MODE_MIXED ? "Руки + геймпад" : "Только геймпад";
    }

    /** What is drawn of the tracked hands over the game: 0 nothing, 1 a window with the skeleton, 2 virtual hands that move like the real ones. */
    static int handVis(Context c) {
        int v = prefs(c).getInt("hand_vis", 0);
        return v < 0 || v > 2 ? 0 : v;
    }

    static String handVisName(int v) {
        return v == 1 ? "Окошко со скелетом" : v == 2 ? "Руки в сцене" : "Выкл";
    }

    /** Hands from the back camera are a controller (HandTracker): left hand walks, right hand acts. */
    static boolean hands(Context c) {
        int m = inputMode(c);
        return m == MODE_HANDS || m == MODE_MIXED;
    }

    /** A hardware gamepad is a controller (GamepadInput). In hands-only mode its buttons are ignored. */
    static boolean pad(Context c) {
        int m = inputMode(c);
        return m == MODE_PAD || m == MODE_MIXED;
    }

    /** Keep the touch controls on the screen in VR (a viewer with a touch lever, or taking the phone out to try). */
    static boolean vrControls(Context c) {
        return prefs(c).getBoolean("vr_controls", true);
    }
}
