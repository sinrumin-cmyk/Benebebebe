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

    /** Keep the touch controls on the screen in VR (a viewer with a touch lever, or taking the phone out to try). */
    static boolean vrControls(Context c) {
        return prefs(c).getBoolean("vr_controls", true);
    }
}
