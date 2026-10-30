package dev.horizon.mobile;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Puts a flat picture on a Cardboard screen the way the game does. vr.wgsl finds, for every screen point p, the eye
 * image point q = p * (1 + k1 r^2 + k2 r^4) * scale (r = |p| with x stretched by the eye's aspect). Here q is known
 * (a point of the picture), so p is found by bisection on the radius, giving the vertices for Canvas.drawBitmapMesh.
 */
final class LensMesh {
    private LensMesh() {}

    /** K1 and K2 as the game reads them (vr.txt next to game-env.txt), so flat pictures bend like the game does. */
    static float[] readLens(Context c) {
        float[] k = {0.441f, 0.156f};
        File f = new File(Dirs.home(c), "vr.txt");
        if (!f.isFile()) return k;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                try {
                    if (line.startsWith("HORIZON_VR_K1=")) k[0] = Float.parseFloat(line.substring(14).trim());
                    if (line.startsWith("HORIZON_VR_K2=")) k[1] = Float.parseFloat(line.substring(14).trim());
                } catch (NumberFormatException ignored) {}
            }
        } catch (Throwable ignored) {}
        return k;
    }

    /**
     * @param eye 0 left, 1 right
     * @param bx, by, bw, bh where the picture sits inside its eye, in pixels from the eye's top-left corner
     * @return (grid+1)^2 vertices for drawBitmapMesh, in screen pixels
     */
    static float[] build(int eye, int screenW, int screenH, float bx, float by, float bw, float bh, float k1, float k2, int grid) {
        final float eyeW = screenW / 2f, eyeH = screenH;
        final float asp = eyeW / eyeH;
        final float scale = 1f / Math.max(0.1f, 1f + k1 + k2);
        float[] v = new float[(grid + 1) * (grid + 1) * 2];
        int k = 0;
        for (int j = 0; j <= grid; j++) {
            for (int i = 0; i <= grid; i++) {
                float px = bx + bw * i / grid, py = by + bh * j / grid;
                float qx = (px - eyeW / 2) / (eyeW / 2) * asp, qy = (py - eyeH / 2) / (eyeH / 2);
                float rs = (float) Math.hypot(qx, qy);
                float f = 1f;
                if (rs > 1e-6f) {
                    float lo = 0f, hi = 2f;
                    for (int n = 0; n < 32; n++) {
                        float mid = 0.5f * (lo + hi), r2 = mid * mid;
                        if (mid * (1f + k1 * r2 + k2 * r2 * r2) * scale < rs) lo = mid; else hi = mid;
                    }
                    f = 0.5f * (lo + hi) / rs;
                }
                v[k++] = eye * eyeW + eyeW / 2 * (1f + qx * f / asp);
                v[k++] = eyeH / 2 * (1f + qy * f);
            }
        }
        return v;
    }
}
