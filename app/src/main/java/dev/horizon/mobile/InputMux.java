package dev.horizon.mobile;

/**
 * Several input sources (a gamepad's keys, its sticks, the hands) feed the one virtual pad the game reads.
 * A button is down while any source holds it; a stick takes the source that is pushed furthest. Without this the
 * hands, which send their stick every frame, would overwrite a real gamepad's stick in the mixed mode.
 */
final class InputMux {
    /** Where the combined state goes: the game (HorizonInput) in play, a test in tests. */
    interface Sink {
        void button(int index, boolean down);
        void stick(boolean right, float x, float y);
    }

    static final Sink GAME = new Sink() {
        @Override public void button(int index, boolean down) { HorizonInput.nativeButton(index, down); }
        @Override public void stick(boolean right, float x, float y) { HorizonInput.nativeStick(right, x, y); }
    };

    /** Source numbers. */
    static final int PAD_KEYS = 0, PAD_AXES = 1, HANDS = 2, SOURCES = 3;

    private final Sink sink;
    private final boolean[][] buttons = new boolean[SOURCES][16];
    private final float[][][] sticks = new float[SOURCES][2][2];   // [source][left/right][x/y]
    private final boolean[] sentButton = new boolean[16];
    private final float[][] sentStick = new float[2][2];

    InputMux(Sink sink) {
        this.sink = sink;
    }

    /** One source's handle. Also what HandTracker writes to. */
    final class Source implements HandTracker.Output {
        private final int id;

        private Source(int id) { this.id = id; }

        @Override public void button(int index, boolean down) { setButton(id, index, down); }
        @Override public void stick(float x, float y) { setStick(id, false, x, y); }
        void stick(boolean right, float x, float y) { setStick(id, right, x, y); }
        @Override public void pointer(boolean present, float x, float y) {}
        void release() { releaseAll(id); }
    }

    Source source(int id) {
        return new Source(id);
    }

    private synchronized void setButton(int src, int index, boolean down) {
        if (index < 0 || index >= 16) return;
        buttons[src][index] = down;
        boolean any = false;
        for (int s = 0; s < SOURCES; s++) any |= buttons[s][index];
        if (any != sentButton[index]) {
            sentButton[index] = any;
            sink.button(index, any);
        }
    }

    private synchronized void setStick(int src, boolean right, float x, float y) {
        int side = right ? 1 : 0;
        sticks[src][side][0] = x;
        sticks[src][side][1] = y;
        float bx = 0, by = 0, best = 0;
        for (int s = 0; s < SOURCES; s++) {
            float sx = sticks[s][side][0], sy = sticks[s][side][1];
            float m = sx * sx + sy * sy;
            if (m > best) { best = m; bx = sx; by = sy; }
        }
        if (bx != sentStick[side][0] || by != sentStick[side][1]) {
            sentStick[side][0] = bx;
            sentStick[side][1] = by;
            sink.stick(right, bx, by);
        }
    }

    private synchronized void releaseAll(int src) {
        for (int i = 0; i < 16; i++) setButton(src, i, false);
        setStick(src, false, 0, 0);
        setStick(src, true, 0, 0);
    }
}
