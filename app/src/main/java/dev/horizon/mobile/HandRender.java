package dev.horizon.mobile;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Draws tracked hands: as virtual hands that follow the real ones joint for joint, or as a skeleton in a small window. */
final class HandRender {
    /** Thumb, index, middle, ring, pinky: each starts at the joint nearest the palm. */
    private static final int[][] FINGERS = {{1, 2, 3, 4}, {5, 6, 7, 8}, {9, 10, 11, 12}, {13, 14, 15, 16}, {17, 18, 19, 20}};
    private static final float[] FINGER_WIDTH = {1.15f, 1.0f, 1.05f, 0.95f, 0.8f};
    private static final int[] PALM = {0, 1, 5, 9, 13, 17};
    private static final int[][] BONES = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {0, 5}, {5, 6}, {6, 7}, {7, 8}, {5, 9}, {9, 10}, {10, 11}, {11, 12},
            {9, 13}, {13, 14}, {14, 15}, {15, 16}, {13, 17}, {17, 18}, {18, 19}, {19, 20}, {0, 17}};

    /** The picture's height fills this much of the view's height. Raise it to make the hands bigger and more spread out. */
    static final float SCENE_SCALE = 1.0f;
    /** A palm this long (in picture heights) is drawn at the screen's depth: no parallax between the eyes. Longer = nearer. */
    private static final float REFERENCE_PALM = 0.22f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] xs = new float[21], ys = new float[21];
    private final int[] order = new int[5];
    private final float[] depth = new float[5];

    /** How close the hand is: 1 = at the screen, larger = nearer. From the palm's size, which is all one camera can tell. */
    static float closeness(float[] pts, float aspect) {
        float size = (float) Math.hypot((pts[0] - pts[27]) * aspect, pts[1] - pts[28]);
        return Math.max(0.5f, Math.min(2.2f, size / REFERENCE_PALM));
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round((color >>> 24) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (color & 0xFFFFFF);
    }

    /**
     * One hand at its real pose, filling a W x H canvas as the camera picture would: the picture keeps its
     * proportions, is centred, and may be cut at the sides. dx moves it sideways (the two eyes' parallax).
     */
    void scene(Canvas c, float w, float h, float aspect, float[] pts, int state, float alpha, float dx) {
        for (int i = 0; i < 21; i++) {
            xs[i] = w / 2 + (pts[3 * i] - 0.5f) * aspect * h * SCENE_SCALE + dx;
            ys[i] = h / 2 + (pts[3 * i + 1] - 0.5f) * h * SCENE_SCALE;
        }
        float palm = (float) Math.hypot(xs[0] - xs[9], ys[0] - ys[9]);
        if (palm < 4f) return;
        final float tube = 0.17f * palm;
        final int shade = withAlpha(0xFF8A5642, alpha), skin = withAlpha(state == HandFrame.FIST ? 0xFFE58C86 : 0xFFE6B497, alpha);
        final int shine = withAlpha(0x55FFFFFF, alpha);

        // The palm, as a rounded slab.
        path.reset();
        for (int i = 0; i < PALM.length; i++) {
            if (i == 0) path.moveTo(xs[PALM[i]], ys[PALM[i]]); else path.lineTo(xs[PALM[i]], ys[PALM[i]]);
        }
        path.close();
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(tube * 1.5f);
        paint.setColor(shade);
        c.drawPath(path, paint);
        paint.setStrokeWidth(tube * 0.9f);
        paint.setColor(skin);
        c.drawPath(path, paint);

        // Fingers, the farthest first, so a nearer finger covers a farther one.
        for (int f = 0; f < 5; f++) {
            float z = 0;
            for (int j : FINGERS[f]) z += pts[3 * j + 2];
            depth[f] = z;
            order[f] = f;
        }
        for (int a = 0; a < 5; a++) {
            for (int b = a + 1; b < 5; b++) {
                if (depth[order[b]] > depth[order[a]]) { int t = order[a]; order[a] = order[b]; order[b] = t; }
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (int n = 0; n < 5; n++) {
            int f = order[n];
            int[] chain = FINGERS[f];
            path.reset();
            for (int i = 0; i < chain.length; i++) {
                if (i == 0) path.moveTo(xs[chain[i]], ys[chain[i]]); else path.lineTo(xs[chain[i]], ys[chain[i]]);
            }
            float width = tube * FINGER_WIDTH[f];
            paint.setStrokeWidth(width);
            paint.setColor(shade);
            c.drawPath(path, paint);
            paint.setStrokeWidth(width * 0.68f);
            paint.setColor(skin);
            c.drawPath(path, paint);
            paint.setStrokeWidth(width * 0.18f);
            paint.setColor(shine);
            c.drawPath(path, paint);
        }

        // A pinch is shown where the thumb and finger meet.
        if (state == HandFrame.PINCH) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(tube * 0.28f);
            paint.setColor(withAlpha(0xFFFFD54F, alpha));
            c.drawCircle((xs[4] + xs[8]) / 2, (ys[4] + ys[8]) / 2, tube * 1.1f, paint);
        }
    }

    /** Both hands as skeletons in a window, with the left hand's stick: its neutral spot, its range, and where the palm is. */
    void debug(Canvas c, HandFrame f, float x, float y, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xAA000000);
        rect.set(x, y, x + w, y + h);
        c.drawRoundRect(rect, h * 0.06f, h * 0.06f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, h * 0.008f));
        paint.setColor(0x44FFFFFF);
        c.drawLine(x + w / 2, y, x + w / 2, y + h, paint);    // the left hand is the one on this side, the right hand the other

        if (f.leftActive) {
            float nx = x + f.neutralX * w, ny = y + f.neutralY * h;
            paint.setColor(0x884C8DFF);
            rect.set(nx - f.rangeX * w, ny - f.rangeY * h, nx + f.rangeX * w, ny + f.rangeY * h);
            c.drawOval(rect, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(nx, ny, h * 0.012f, paint);
            if (f.present[HandFrame.LEFT]) {
                float[] p = f.pts[HandFrame.LEFT];
                float cx = 0, cy = 0;
                for (int i : PALM) { cx += p[3 * i]; cy += p[3 * i + 1]; }
                cx = x + cx / PALM.length * w;
                cy = y + cy / PALM.length * h;
                paint.setStyle(Paint.Style.STROKE);
                c.drawLine(nx, ny, cx, cy, paint);
            }
        }
        for (int side = 0; side < 2; side++) {
            if (!f.present[side]) continue;
            float[] p = f.pts[side];
            int st = f.state[side];
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, h * 0.014f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(st == HandFrame.PINCH ? 0xFFFFD54F : st == HandFrame.FIST ? 0xFFFF6B6B : 0xFF7CF3A0);
            for (int[] bone : BONES) {
                c.drawLine(x + p[3 * bone[0]] * w, y + p[3 * bone[0] + 1] * h, x + p[3 * bone[1]] * w, y + p[3 * bone[1] + 1] * h, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(side == HandFrame.LEFT ? 0xFF4C8DFF : 0xFFFF9A3C);
            for (int i = 0; i < 21; i++) c.drawCircle(x + p[3 * i] * w, y + p[3 * i + 1] * h, h * 0.014f, paint);
        }
    }
}
