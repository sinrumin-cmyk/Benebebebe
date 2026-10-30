package dev.horizon.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.View;

/**
 * The hand visualizer over the game, in a window of its own (the game owns its own surface, so views are put above
 * it, as the touch controls are). Mode 2 draws virtual hands that follow the real ones joint for joint, with depth
 * from the hand's apparent size; mode 1 draws a small window with the skeleton and the left hand's stick. In VR
 * (side-by-side) each eye gets its own picture, with parallax, bent by the same lens distortion as the game.
 */
final class HandOverlay extends View {
    static final int WINDOW = 1, SCENE = 2;
    private static final int GRID = 16;
    /** Largest sideways shift between the eyes, as a fraction of an eye's width, for a hand much nearer or farther than the screen. */
    private static final float PARALLAX = 0.05f;

    private final boolean vr;
    private final int mode;
    private final float k1, k2;
    private final HandRender render = new HandRender();
    private final float dp;

    private volatile HandFrame frame;
    private float aspect = 4f / 3f;
    private final float[][] smooth = new float[2][63];
    private final float[] alpha = new float[2];
    private final int[] state = new int[2];

    private Bitmap eyeBitmap;
    private Canvas eyeCanvas;
    private final float[][] mesh = new float[2][];
    private int meshW, meshH;

    HandOverlay(Context c, boolean vr, int mode) {
        super(c);
        this.vr = vr;
        this.mode = mode;
        float[] k = LensMesh.readLens(c);
        k1 = k[0];
        k2 = k[1];
        dp = c.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
    }

    /** Called from the hands thread for every camera frame. */
    void setFrame(HandFrame f) {
        frame = f;
        postInvalidateOnAnimation();
    }

    /** Eases the drawn pose toward the newest camera frame on every screen refresh: smooth motion between ~30 camera frames per second. */
    private boolean update() {
        HandFrame f = frame;
        boolean moving = false;
        if (f != null) aspect = f.aspect;
        for (int i = 0; i < 2; i++) {
            if (f != null && f.present[i]) {
                if (alpha[i] < 0.02f) System.arraycopy(f.pts[i], 0, smooth[i], 0, 63);
                else for (int j = 0; j < 63; j++) smooth[i][j] += (f.pts[i][j] - smooth[i][j]) * 0.45f;
                state[i] = f.state[i];
                alpha[i] = Math.min(1f, alpha[i] + 0.25f);
            } else {
                alpha[i] = Math.max(0f, alpha[i] - 0.12f);
            }
            if (alpha[i] > 0.02f) moving = true;
        }
        return moving;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        boolean moving = update();
        if (mode == SCENE) drawScene(canvas, w, h); else drawWindow(canvas, w, h);
        if (moving) postInvalidateOnAnimation();
    }

    // ---------------------------------------------------------------- virtual hands

    private void drawScene(Canvas canvas, int w, int h) {
        if (!vr) {
            for (int i = 0; i < 2; i++) {
                if (alpha[i] > 0.02f) render.scene(canvas, w, h, aspect, smooth[i], state[i], alpha[i] * 0.9f, 0f);
            }
            return;
        }
        final int bw = Math.max(1, (int) (w * 0.3f)), bh = Math.max(1, (int) (h * 0.6f));
        ensureEyeBitmap(bw, bh);
        ensureMesh(w, h, 0, 0, w / 2f, h);
        for (int eye = 0; eye < 2; eye++) {
            eyeCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            for (int i = 0; i < 2; i++) {
                if (alpha[i] < 0.02f) continue;
                // Nearer than the screen: the left eye's picture moves right, the right eye's left.
                float dx = (eye == 0 ? 1 : -1) * PARALLAX * bw * (HandRender.closeness(smooth[i], aspect) - 1f);
                render.scene(eyeCanvas, bw, bh, aspect, smooth[i], state[i], alpha[i] * 0.9f, dx);
            }
            canvas.save();
            canvas.clipRect(eye * w / 2f, 0, (eye + 1) * w / 2f, h);
            canvas.drawBitmapMesh(eyeBitmap, GRID, GRID, mesh[eye], 0, null, 0, null);
            canvas.restore();
        }
    }

    // ---------------------------------------------------------------- skeleton window

    private void drawWindow(Canvas canvas, int w, int h) {
        HandFrame f = frame;
        if (f == null) return;
        if (!vr) {
            float ww = 220 * dp, hh = ww * 0.75f;
            render.debug(canvas, f, (w - ww) / 2f, h - hh - 12 * dp, ww, hh);
            return;
        }
        final int bw = 320, bh = 240;
        ensureEyeBitmap(bw, bh);
        final float eyeW = w / 2f;
        final float pw = eyeW * 0.34f, ph = pw * 0.75f;
        for (int eye = 0; eye < 2; eye++) {
            ensureMeshFor(eye, w, h, (eyeW - pw) / 2f + (eye == 0 ? 1 : -1) * 0.008f * eyeW, h * 0.60f, pw, ph);
        }
        eyeCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        render.debug(eyeCanvas, f, 0, 0, bw, bh);
        for (int eye = 0; eye < 2; eye++) {
            canvas.save();
            canvas.clipRect(eye * w / 2f, 0, (eye + 1) * w / 2f, h);
            canvas.drawBitmapMesh(eyeBitmap, GRID, GRID, mesh[eye], 0, null, 0, null);
            canvas.restore();
        }
    }

    // ---------------------------------------------------------------- buffers

    private void ensureEyeBitmap(int bw, int bh) {
        if (eyeBitmap != null && eyeBitmap.getWidth() == bw && eyeBitmap.getHeight() == bh) return;
        eyeBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        eyeCanvas = new Canvas(eyeBitmap);
    }

    /** The full-eye mesh, built again only when the screen size changes. */
    private void ensureMesh(int w, int h, float bx, float by, float bw, float bh) {
        if (w == meshW && h == meshH && mesh[0] != null) return;
        for (int eye = 0; eye < 2; eye++) mesh[eye] = LensMesh.build(eye, w, h, bx, by, bw, bh, k1, k2, GRID);
        meshW = w;
        meshH = h;
    }

    private void ensureMeshFor(int eye, int w, int h, float bx, float by, float bw, float bh) {
        if (w == meshW && h == meshH && mesh[eye] != null) return;
        mesh[eye] = LensMesh.build(eye, w, h, bx, by, bw, bh, k1, k2, GRID);
        if (eye == 1) { meshW = w; meshH = h; }
    }
}
