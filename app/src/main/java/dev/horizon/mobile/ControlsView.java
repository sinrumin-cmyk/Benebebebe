package dev.horizon.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a Mojo control layout and turns touches on it into Horizon input: buttons, toggles, swipe
 * between buttons, drawers, joysticks. A finger on no control is the camera / menu pointer.
 * Layout format and behaviour follow MojoLauncher (LGPL-3.0).
 */
public final class ControlsView extends View {
    /** What the controls cannot do themselves: the on-screen keyboard and the switches that live on the overlay. */
    interface Host {
        void toggleKeyboard();

        void virtualMouseChanged(boolean on);

    }

    private static final class Item {
        LayoutData.Btn d;
        boolean isDrawer, isJoy;
        Item owner;                                   // the drawer of a sub-button
        final List<Item> subs = new ArrayList<>();
        String orientation = "LEFT";
        boolean open;
        float x, y, w, h;
        int[][] acts = new int[0][];
        boolean uiToggle;
        boolean pressed, on;
        float ox, oy;                                 // joystick: where the finger went down
        float jx, jy;                                 // joystick: current vector, -1..1
    }

    private final float density;
    private final float margin;
    private final List<Item> top = new ArrayList<>();
    private final Map<String, Float> vars = new HashMap<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Item[] owner = new Item[16];       // by pointer id
    private int freeId = -1;
    private final int[] btnCount = new int[16];
    private final int[] dirCount = new int[4];
    private Item joyActive;
    private boolean joySprint, uiHidden;
    private float lastSx = Float.NaN, lastSy = Float.NaN;
    private int slot;
    private int insetL, insetT, insetR, insetB;
    private final float scale;                        // the layout size setting, in percent
    private Host host;
    private boolean vmouse;

    void setHost(Host h) {
        host = h;
        if (Settings.virtualMouse(getContext())) {
            vmouse = true;
            HorizonInput.nativeVirtualMouse(true);
            h.virtualMouseChanged(true);
        }
    }

    public ControlsView(Context c, LayoutData layout) {
        super(c);
        scale = Settings.layoutScale(c);
        density = c.getResources().getDisplayMetrics().density;
        margin = Math.round(2 * density);
        setFocusable(false);
        text.setTextAlign(Paint.Align.CENTER);
        line.setStyle(Paint.Style.STROKE);

        boolean glfw = layout.version < 9;
        for (LayoutData.Btn b : layout.buttons) {
            Item it = button(b, glfw);
            if (it != null) top.add(it);
        }
        for (LayoutData.Drawer dr : layout.drawers) {
            Item it = new Item();
            it.d = dr.props;
            it.isDrawer = true;
            it.orientation = dr.orientation == null ? "LEFT" : dr.orientation;
            for (LayoutData.Btn s : dr.subs) {
                Item si = button(s, glfw);
                if (si != null) { si.owner = it; it.subs.add(si); }
            }
            top.add(it);
        }
        for (LayoutData.Btn j : layout.joysticks) {
            Item it = new Item();
            it.d = j;
            it.isJoy = true;
            top.add(it);
        }
    }

    /** A button with the actions Horizon can do; null if none of its keys do anything here. */
    private static Item button(LayoutData.Btn b, boolean glfw) {
        List<int[]> acts = new ArrayList<>();
        boolean ui = false;
        for (int k : b.keys) {
            int[] a = LayoutKeys.action(k, glfw);
            if (a == null) continue;
            acts.add(a);
            if (a[0] == LayoutKeys.UI) ui = true;
        }
        if (acts.isEmpty()) return null;
        Item it = new Item();
        it.d = b;
        it.acts = acts.toArray(new int[0][]);
        it.uiToggle = ui;
        return it;
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { relayout(); }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestApplyInsets();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        insetL = insetT = insetR = insetB = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout dc = in.getDisplayCutout();
            if (dc != null) {
                insetL = dc.getSafeInsetLeft();
                insetT = dc.getSafeInsetTop();
                insetR = dc.getSafeInsetRight();
                insetB = dc.getSafeInsetBottom();
            }
        }
        relayout();
        return super.onApplyWindowInsets(in);
    }

    private void relayout() {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        float aw = W - insetL - insetR, ah = H - insetT - insetB;
        for (Item it : top) {
            it.w = it.d.w * density * scale / 100f;
            it.h = it.d.h * density * scale / 100f;
            position(it, aw, ah);
            if (!it.isDrawer) continue;
            for (int i = 0; i < it.subs.size(); i++) {
                Item s = it.subs.get(i);
                if (it.orientation.equals("FREE")) {
                    s.w = s.d.w * density * scale / 100f;
                    s.h = s.d.h * density * scale / 100f;
                    position(s, aw, ah);
                    continue;
                }
                s.w = it.w;
                s.h = it.h;
                s.x = it.x;
                s.y = it.y;
                float stepX = (it.w + margin) * (i + 1), stepY = (it.h + margin) * (i + 1);
                switch (it.orientation) {
                    case "RIGHT": s.x += stepX; break;
                    case "LEFT": s.x -= stepX; break;
                    case "UP": s.y -= stepY; break;
                    case "DOWN": s.y += stepY; break;
                    default: break;
                }
            }
        }
        invalidate();
    }

    private void position(Item it, float aw, float ah) {
        vars.put("top", 0f);
        vars.put("left", 0f);
        vars.put("right", aw - it.w);
        vars.put("bottom", ah - it.h);
        vars.put("width", it.w);
        vars.put("height", it.h);
        vars.put("screen_width", aw);
        vars.put("screen_height", ah);
        vars.put("margin", margin);
        vars.put("preferred_scale", scale);
        it.x = insetL + LayoutExpr.eval(it.d.dx, vars, density);
        it.y = insetT + LayoutExpr.eval(it.d.dy, vars, density);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        for (Item it : top) {
            if (uiHidden && !it.uiToggle) continue;
            if (it.isJoy) drawJoystick(c, it);
            else drawButton(c, it);
            if (it.isDrawer && it.open) for (Item s : it.subs) drawButton(c, s);
        }
    }

    private static int withAlpha(int color, float a) {
        return Color.argb(Math.round(Color.alpha(color) * a), Color.red(color), Color.green(color), Color.blue(color));
    }

    private void drawButton(Canvas c, Item it) {
        float a = Math.max(0f, Math.min(1f, it.d.opacity));
        float r = Math.min(it.w, it.h) / 2f * it.d.radius / 100f;
        rect.set(it.x, it.y, it.x + it.w, it.y + it.h);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(withAlpha(it.d.bg, a));
        c.drawRoundRect(rect, r, r, fill);
        if (it.d.strokeW > 0) {
            float sw = it.d.strokeW * density;
            line.setStrokeWidth(sw);
            line.setColor(withAlpha(it.d.stroke, a));
            rect.inset(sw / 2f, sw / 2f);
            c.drawRoundRect(rect, r, r, line);
            rect.set(it.x, it.y, it.x + it.w, it.y + it.h);
        }
        if (it.pressed || it.on || (it.isDrawer && it.open)) {
            fill.setColor(withAlpha(0x66FFFFFF, a));
            c.drawRoundRect(rect, r, r, fill);
        }
        if (it.d.name == null || it.d.name.isEmpty()) return;
        float size = 13 * density, room = it.w - 6 * density;
        text.setTextSize(size);
        float tw = text.measureText(it.d.name);
        if (tw > room && room > 0) {
            size = Math.max(7 * density, size * room / tw);
            text.setTextSize(size);
        }
        text.setColor(withAlpha(0xFFFFFFFF, a));
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(it.d.name, it.x + it.w / 2f, it.y + it.h / 2f - (fm.ascent + fm.descent) / 2f, text);
    }

    private void drawJoystick(Canvas c, Item it) {
        float a = Math.max(0f, Math.min(1f, it.d.opacity));
        float size = Math.min(it.w, it.h), radius = size / 2f;
        float cx = it.x + it.w / 2f, cy = it.y + it.h / 2f;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(withAlpha(it.d.bg, a));
        c.drawCircle(cx, cy, radius, fill);
        if (it.d.strokeW > 0) {
            line.setStrokeWidth(it.d.strokeW * density);
            line.setColor(withAlpha(it.d.stroke, a));
            c.drawCircle(cx, cy, radius, line);
        }
        fill.setColor(withAlpha(0x88FFFFFF, a));
        c.drawCircle(cx + it.jx * radius * 0.6f, cy - it.jy * radius * 0.6f, radius * 0.4f, fill);
    }

    // ---------------------------------------------------------------- input to the game

    private void button(int idx, boolean down) {
        int n = Math.max(0, btnCount[idx] + (down ? 1 : -1));
        boolean was = btnCount[idx] > 0, now = n > 0;
        btnCount[idx] = n;
        if (was != now) HorizonInput.nativeButton(idx, now);
    }

    private void sendStick() {
        float x, y;
        if (joyActive != null) {
            x = joyActive.jx;
            y = joyActive.jy;
        } else {
            x = (dirCount[LayoutKeys.RIGHT] > 0 ? 1f : 0f) - (dirCount[LayoutKeys.LEFT] > 0 ? 1f : 0f);
            y = (dirCount[LayoutKeys.UP] > 0 ? 1f : 0f) - (dirCount[LayoutKeys.DOWN] > 0 ? 1f : 0f);
            float len = (float) Math.hypot(x, y);
            if (len > 1f) { x /= len; y /= len; }
        }
        if (x == lastSx && y == lastSy) return;
        lastSx = x;
        lastSy = y;
        HorizonInput.nativeStick(false, x, y);
    }

    private void actuate(Item it, boolean down) {
        for (int[] a : it.acts) {
            switch (a[0]) {
                case LayoutKeys.BTN:
                    button(a[1], down);
                    if (down && a[1] == HorizonInput.LB) slot = (slot + 8) % 9;
                    if (down && a[1] == HorizonInput.RB) slot = (slot + 1) % 9;
                    break;
                case LayoutKeys.DIR:
                    dirCount[a[1]] = Math.max(0, dirCount[a[1]] + (down ? 1 : -1));
                    sendStick();
                    break;
                case LayoutKeys.UI:
                    if (down) uiHidden = !uiHidden;
                    break;
                case LayoutKeys.SLOT:
                    if (down) selectSlot(a[1]);
                    break;
                case LayoutKeys.INJECT:
                    HorizonInput.nativeKey(a[1], a[2], down);
                    break;
                case LayoutKeys.MMB:
                    HorizonInput.nativeMouseButton(2, down);
                    break;
                case LayoutKeys.KEYBOARD:
                    if (down && host != null) host.toggleKeyboard();
                    break;
                case LayoutKeys.VMOUSE:
                    if (down) {
                        vmouse = !vmouse;
                        HorizonInput.nativeVirtualMouse(vmouse);
                        if (host != null) host.virtualMouseChanged(vmouse);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /** Horizon has previous / next slot only, so a number key taps them the right number of times. */
    private void selectSlot(int target) {
        int steps = (target - slot + 9) % 9;
        if (steps == 0) return;
        final int btn = steps <= 4 ? HorizonInput.RB : HorizonInput.LB;
        final int n = steps <= 4 ? steps : 9 - steps;
        for (int i = 0; i < n; i++) {
            long t = i * 120L;
            handler.postDelayed(() -> button(btn, true), t);
            handler.postDelayed(() -> button(btn, false), t + 60);
        }
        slot = target;
    }

    private void moveJoystick(Item it, float x, float y) {
        float radius = Math.min(it.w, it.h) / 2f;
        float cx = it.d.absolute ? it.x + it.w / 2f : it.ox, cy = it.d.absolute ? it.y + it.h / 2f : it.oy;
        float dx = (x - cx) / radius, dy = (cy - y) / radius;
        float len = (float) Math.hypot(dx, dy);
        if (len > 1f) { dx /= len; dy /= len; len = 1f; }
        it.jx = dx;
        it.jy = dy;
        boolean run = len > 0.95f && dy > 0.7f;       // pushed to the top edge: sprint
        if (run != joySprint) { joySprint = run; button(HorizonInput.LSTICK, run); }
        sendStick();
    }

    // ---------------------------------------------------------------- touches

    private static boolean contains(Item it, float x, float y) {
        return x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h;
    }

    private Item hit(float x, float y) {
        for (int i = top.size() - 1; i >= 0; i--) {
            Item it = top.get(i);
            if (uiHidden && !it.uiToggle) continue;
            if (it.isDrawer && it.open) for (Item s : it.subs) if (contains(s, x, y)) return s;
            if (contains(it, x, y)) return it;
        }
        return null;
    }

    private void press(Item it, float x, float y) {
        if (it.isJoy) {
            it.ox = x;
            it.oy = y;
            joyActive = it;
            moveJoystick(it, x, y);
        } else {
            it.pressed = true;
            if (!it.isDrawer && !it.d.toggle) actuate(it, true);
        }
    }

    private void release(Item it, boolean cancel) {
        if (it.isJoy) {
            it.jx = it.jy = 0;
            if (joyActive == it) joyActive = null;
            if (joySprint) { joySprint = false; button(HorizonInput.LSTICK, false); }
            sendStick();
        } else if (it.isDrawer) {
            it.pressed = false;
            if (!cancel) it.open = !it.open;
        } else {
            it.pressed = false;
            if (it.d.toggle) {
                if (!cancel) { it.on = !it.on; actuate(it, it.on); }
            } else {
                actuate(it, false);
            }
        }
    }

    private void onDown(int id, float x, float y) {
        if (id >= owner.length) return;
        Item it = hit(x, y);
        if (it != null) {
            owner[id] = it;
            press(it, x, y);
        } else if (freeId < 0) {
            freeId = id;
            HorizonInput.nativeTouch(0, x, y);
        }
    }

    private void onMove(int id, float x, float y) {
        if (id >= owner.length) return;
        if (freeId == id) { HorizonInput.nativeTouch(1, x, y); return; }
        Item it = owner[id];
        if (it == null) return;
        if (it.isJoy) { moveJoystick(it, x, y); return; }
        if (!it.d.swipe) return;
        Item n = hit(x, y);
        if (n == it) return;
        release(it, false);
        owner[id] = null;
        if (n != null && !n.isJoy && !n.isDrawer && n.d.swipe) {
            owner[id] = n;
            press(n, x, y);
        }
    }

    private void onUp(int id, float x, float y, boolean cancel) {
        if (id >= owner.length) return;
        if (freeId == id) { freeId = -1; HorizonInput.nativeTouch(2, x, y); return; }
        Item it = owner[id];
        owner[id] = null;
        if (it != null) release(it, cancel);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked(), idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                onDown(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) onMove(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                onUp(e.getPointerId(idx), e.getX(idx), e.getY(idx), false);
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < e.getPointerCount(); i++) onUp(e.getPointerId(i), e.getX(i), e.getY(i), true);
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }
}
