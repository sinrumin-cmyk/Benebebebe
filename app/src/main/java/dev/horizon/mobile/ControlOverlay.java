package dev.horizon.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating left stick, buttons, and a free area: a drag there turns the camera (or moves the
 * pointer in menus), a tap attacks / places, a held finger breaks blocks (decided in Rust).
 */
public class ControlOverlay extends View {
    private static final class Btn {
        final String label; final int index; final float cx, cy, r;
        boolean down;
        Btn(String label, int index, float cx, float cy, float r) { this.label = label; this.index = index; this.cx = cx; this.cy = cy; this.r = r; }
    }

    private static final int NONE = 0, STICK = 1, BUTTON = 2, FREE = 3;
    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Btn> buttons = new ArrayList<>();
    private final int[] role = new int[16];     // by pointer id
    private final Btn[] held = new Btn[16];
    private float stickCx, stickCy, stickX, stickY, stickR;
    private int stickPointer = -1, freePointer = -1;
    private boolean sprint;

    public ControlOverlay(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setFocusable(false);
        fill.setStyle(Paint.Style.FILL); fill.setColor(0x33FFFFFF);
        line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(2 * dp); line.setColor(0x88FFFFFF);
        text.setColor(0xDDFFFFFF); text.setTextAlign(Paint.Align.CENTER); text.setTextSize(13 * dp);
        stickR = 60 * dp;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        buttons.clear();
        float r = 34 * dp, m = 20 * dp;
        float bx = w - m - r, by = h - m - r;
        buttons.add(new Btn("Jump", HorizonInput.SOUTH, bx, by, r * 1.15f));
        buttons.add(new Btn("Hit", HorizonInput.RT, bx - 2.6f * r, by - 0.2f * r, r));
        buttons.add(new Btn("Use", HorizonInput.LT, bx - 0.2f * r, by - 2.6f * r, r));
        buttons.add(new Btn("Sneak", HorizonInput.RSTICK, bx - 2.6f * r, by - 2.8f * r, r * 0.8f));
        float s = 22 * dp, top = m + s;
        buttons.add(new Btn("II", HorizonInput.START, m + s, top, s));
        buttons.add(new Btn("Chat", HorizonInput.DRIGHT, m + 3.2f * s, top, s));
        buttons.add(new Btn("<", HorizonInput.LB, w / 2f - 2.2f * s, top, s));
        buttons.add(new Btn(">", HorizonInput.RB, w / 2f + 2.2f * s, top, s));
        buttons.add(new Btn("Inv", HorizonInput.NORTH, w - m - s, top, s));
        buttons.add(new Btn("Drop", HorizonInput.EAST, w - m - 3.2f * s, top, s));
        buttons.add(new Btn("Swap", HorizonInput.WEST, w - m - 5.4f * s, top, s));
        buttons.add(new Btn("Cam", HorizonInput.DUP, w - m - 7.6f * s, top, s));
    }

    @Override
    protected void onDraw(Canvas c) {
        for (Btn b : buttons) {
            fill.setColor(b.down ? 0x66FFFFFF : 0x33FFFFFF);
            c.drawCircle(b.cx, b.cy, b.r, fill);
            c.drawCircle(b.cx, b.cy, b.r, line);
            c.drawText(b.label, b.cx, b.cy + 5 * dp, text);
        }
        if (stickPointer >= 0) {
            fill.setColor(0x22FFFFFF);
            c.drawCircle(stickCx, stickCy, stickR, fill);
            c.drawCircle(stickCx, stickCy, stickR, line);
            fill.setColor(0x66FFFFFF);
            c.drawCircle(stickCx + stickX * stickR, stickCy - stickY * stickR, stickR * 0.4f, fill);
        }
    }

    private Btn hit(float x, float y) {
        for (Btn b : buttons) {
            float dx = x - b.cx, dy = y - b.cy, r = b.r * 1.15f;
            if (dx * dx + dy * dy <= r * r) return b;
        }
        return null;
    }

    private void press(Btn b, boolean down) {
        b.down = down;
        HorizonInput.nativeButton(b.index, down);
    }

    private void moveStick(float x, float y) {
        float dx = (x - stickCx) / stickR, dy = (stickCy - y) / stickR;
        float len = (float) Math.hypot(dx, dy);
        if (len > 1f) { dx /= len; dy /= len; len = 1f; }
        stickX = dx; stickY = dy;
        HorizonInput.nativeStick(false, dx, dy);
        boolean run = len > 0.95f && dy > 0.7f;     // pushed to the top edge: sprint
        if (run != sprint) { sprint = run; HorizonInput.nativeButton(HorizonInput.LSTICK, run); }
    }

    private void releaseStick() {
        stickPointer = -1; stickX = stickY = 0;
        HorizonInput.nativeStick(false, 0, 0);
        if (sprint) { sprint = false; HorizonInput.nativeButton(HorizonInput.LSTICK, false); }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked(), idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = e.getPointerId(idx);
                if (id >= role.length) break;
                float x = e.getX(idx), y = e.getY(idx);
                Btn b = hit(x, y);
                if (b != null) { role[id] = BUTTON; held[id] = b; press(b, true); }
                else if (stickPointer < 0 && x < getWidth() * 0.4f && y > getHeight() * 0.35f) {
                    role[id] = STICK; stickPointer = id; stickCx = x; stickCy = y; moveStick(x, y);
                } else if (freePointer < 0) {
                    role[id] = FREE; freePointer = id; HorizonInput.nativeTouch(0, x, y);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    if (id >= role.length) continue;
                    if (role[id] == STICK) moveStick(e.getX(i), e.getY(i));
                    else if (role[id] == FREE) HorizonInput.nativeTouch(1, e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (action == MotionEvent.ACTION_CANCEL) { for (int i = 0; i < e.getPointerCount(); i++) release(e.getPointerId(i), e.getX(i), e.getY(i)); }
                else release(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            }
        }
        invalidate();
        return true;
    }

    private void release(int id, float x, float y) {
        if (id >= role.length) return;
        switch (role[id]) {
            case BUTTON: if (held[id] != null) press(held[id], false); held[id] = null; break;
            case STICK: releaseStick(); break;
            case FREE: freePointer = -1; HorizonInput.nativeTouch(2, x, y); break;
        }
        role[id] = NONE;
    }
}
