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
}
