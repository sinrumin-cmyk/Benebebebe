package dev.horizon.mobile;

/** The only seam between the on-screen controls and the game. Indices follow Rust's Button::ALL. */
public final class HorizonInput {
    static { System.loadLibrary("horizon_client"); }

    public static final int SOUTH = 0, EAST = 1, WEST = 2, NORTH = 3, LB = 4, RB = 5, LT = 6, RT = 7,
            SELECT = 8, START = 9, LSTICK = 10, RSTICK = 11, DUP = 12, DDOWN = 13, DLEFT = 14, DRIGHT = 15;

    public static native void nativeButton(int index, boolean down);
    public static native void nativeStick(boolean right, float x, float y);
    /** phase: 0 down, 1 move, 2 up. Pixels. The finger that is on no stick or button. */
    public static native void nativeTouch(int phase, float x, float y);

    /** A key as the game's own keyboard: scheme 0 is an Android KEYCODE_*, 1 a GLFW code (old Mojo layouts). */
    public static native void nativeKey(int scheme, int code, boolean down);
    /** Characters from the on-screen keyboard, any language. */
    public static native void nativeText(String text);
    /** A mouse moved by this many pixels (a real mouse or the virtual one). */
    public static native void nativeMouseMove(float dx, float dy);
    /** 0 left, 1 right, 2 middle. */
    public static native void nativeMouseButton(int button, boolean down);
    /** Wheel notches, up positive. */
    public static native void nativeScroll(float notches);
    /** The free finger acts as a touchpad that moves a cursor, a tap clicks. */
    public static native void nativeVirtualMouse(boolean on);
    /** Frames the game has drawn so far. */
    public static native long nativeFrames();
    /** True while a text field has the focus in the game. */
    public static native boolean nativeWantsText();
    /** The cursor to draw: x in the high 32 bits, y in the low ones; -1 for none. */
    public static native long nativeCursor();
}
