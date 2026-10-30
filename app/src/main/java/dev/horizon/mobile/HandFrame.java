package dev.horizon.mobile;

/** What the camera saw in one frame, after HandTracker's analysis. Handed to listeners and never changed afterwards. */
final class HandFrame {
    static final int OPEN = 0, PINCH = 1, FIST = 2;
    static final int LEFT = 0, RIGHT = 1;

    /** Picture width over height: landmark x and y are fractions of the picture, so this restores their proportions. */
    float aspect = 4f / 3f;
    final boolean[] present = new boolean[2];
    /** 21 landmarks per hand, x y z each. x and y are 0..1 over the picture (not mirrored: right is the player's right); z is relative depth, smaller = closer to the camera. */
    final float[][] pts = new float[2][63];
    final int[] state = new int[2];
    /** The left hand's stick: where its palm was when it appeared, and the palm travel that is full stick. */
    boolean leftActive;
    float neutralX, neutralY, rangeX, rangeY;
}
