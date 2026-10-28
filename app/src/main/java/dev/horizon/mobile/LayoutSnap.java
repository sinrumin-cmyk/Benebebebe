package dev.horizon.mobile;

import java.util.List;

/**
 * Position maths of the layout editor: turns a dragged position into a Mojo position expression and
 * snaps / aligns it to neighbouring controls. Ported from MojoLauncher's ControlInterface
 * (generateDynamicX/Y, applySize, snapAndAlign; LGPL-3.0). No Android classes, so it can be tested alone.
 */
final class LayoutSnap {
    /** The preferred button size is 100 % in Horizon, as in the layouts' "preferred_scale" variable. */
    static final float PREF = 100f;

    /** A control as the maths sees it: pixel box, size in dp and the expressions that placed it. */
    static final class Box {
        float x, y, w, h;
        float wDp, hDp;
        String dx = "0", dy = "0";
    }

    private LayoutSnap() {}

    static String genX(Box b, float x, float screenW) {
        if (screenW <= 0) return "0";
        if (x + b.w / 2f > screenW / 2f) return (x + b.w) / screenW + " * ${screen_width} - ${width}";
        return x / screenW + " * ${screen_width}";
    }

    static String genY(Box b, float y, float screenH) {
        if (screenH <= 0) return "0";
        if (y + b.h / 2f > screenH / 2f) return (y + b.h) / screenH + " * ${screen_height} - ${height}";
        return y / screenH + " * ${screen_height}";
    }

    /** Rewrites an expression of `nb` so its ${width} / ${height} / ${right} / ${bottom} mean nb's, not the control being placed. */
    static String applySize(String equation, Box nb) {
        return equation
                .replace("${right}", "(${screen_width} - ${width})")
                .replace("${bottom}", "(${screen_height} - ${height})")
                .replace("${height}", "(px(" + nb.hDp + ") / " + PREF + " * ${preferred_scale})")
                .replace("${width}", "(px(" + nb.wDp + ") / " + PREF + " * ${preferred_scale})");
    }

    private static boolean canSnap(Box self, Box other, float minDist) {
        if (other == self) return false;
        double d = Math.hypot((other.x + other.w / 2f) - (self.x + self.w / 2f), (other.y + other.h / 2f) - (self.y + self.h / 2f));
        return d <= Math.max(other.w / 2f + self.w / 2f, other.h / 2f + self.h / 2f) + minDist;
    }

    /**
     * Moves `self` to (x, y) and returns {dynamicX, dynamicY}: plain proportional expressions, replaced by
     * ones relative to a neighbour when an edge comes within the snap distance (6 dp) of it.
     */
    static String[] snap(Box self, float x, float y, List<Box> others, float screenW, float screenH, float density, float margin) {
        final float min = 6 * density;
        String dynamicX = genX(self, x, screenW);
        String dynamicY = genY(self, y, screenH);
        self.x = x;
        self.y = y;

        for (Box b : others) {
            if (!canSnap(self, b, min)) continue;
            float bTop = b.y, bBottom = b.y + b.h, bLeft = b.x, bRight = b.x + b.w;
            float top = self.y, bottom = self.y + self.h, left = self.x, right = self.x + self.w;
            String baseY = genY(self, self.y, screenH), baseX = genX(self, self.x, screenW);

            if (Math.abs(top - bBottom) < min) {
                dynamicY = applySize(b.dy, b) + applySize(" + ${height}", b) + " + ${margin}";
            } else if (Math.abs(bTop - bottom) < min) {
                dynamicY = applySize(b.dy, b) + " - ${height} - ${margin}";
            }
            if (!dynamicY.equals(baseY)) {
                if (Math.abs(bLeft - left) < min) {
                    dynamicX = applySize(b.dx, b);
                } else if (Math.abs(bRight - right) < min) {
                    dynamicX = applySize(b.dx, b) + applySize(" + ${width}", b) + " - ${width}";
                }
            }

            if (Math.abs(bLeft - right) < min) {
                dynamicX = applySize(b.dx, b) + " - ${width} - ${margin}";
            } else if (Math.abs(left - bRight) < min) {
                dynamicX = applySize(b.dx, b) + applySize(" + ${width}", b) + " + ${margin}";
            }
            if (!dynamicX.equals(baseX)) {
                if (Math.abs(bTop - top) < min) {
                    dynamicY = applySize(b.dy, b);
                } else if (Math.abs(bBottom - bottom) < min) {
                    dynamicY = applySize(b.dy, b) + applySize(" + ${height}", b) + " - ${height}";
                }
            }
        }
        return new String[]{dynamicX, dynamicY};
    }
}
