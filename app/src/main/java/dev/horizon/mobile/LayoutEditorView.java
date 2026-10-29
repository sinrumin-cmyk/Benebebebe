package dev.horizon.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The canvas of the layout editor: shows a layout the way the game draws it, lets a finger drag controls
 * (with snapping to neighbours, as in MojoLauncher's editor, LGPL-3.0) and selects one for the side panel.
 * It edits a LayoutData in place; the activity owns saving.
 */
public final class LayoutEditorView extends View {
    static final int BUTTON = 0, DRAWER = 1, SUB = 2, JOY = 3;

    /** One control on the canvas. `d` is the data it edits; `drawer` is set for a drawer and for its sub-buttons. */
    static final class Item {
        int kind;
        LayoutData.Btn d;
        LayoutData.Drawer drawer;
        int index;                       // position among a drawer's sub-buttons
        boolean ok = true;               // Horizon understands at least one of its keys
        float x, y, w, h;
        final LayoutSnap.Box box = new LayoutSnap.Box();

        boolean isFreeSub() {
            return kind == SUB && "FREE".equals(drawer.orientation);
        }
    }

    interface Listener {
        /** The selection changed (or finished moving); null means nothing is selected. */
        void onSelect(Item it);

        /** The layout was changed by the canvas itself (a drag, an add, a delete...). */
        void onEdited();
    }

    private final float density;
    private final float margin;
    private final int slop;
    private final List<Item> items = new ArrayList<>();
    private final Map<LayoutData.Drawer, Item> drawerItems = new IdentityHashMap<>();
    private final Set<LayoutData.Drawer> collapsed = new HashSet<>();
    private final Map<String, Float> vars = new HashMap<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final DashPathEffect dash;

    private LayoutData layout = LayoutData.blank();
    private float scale = 100f;                 // the layout size setting, in percent
    private Listener listener;
    private Item selected;

    private int pointer = -1;
    private Item touched;
    private float downX, downY, offX, offY;
    private boolean dragging;

    public LayoutEditorView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        margin = Math.round(2 * density);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        dash = new DashPathEffect(new float[]{4 * density, 3 * density}, 0);
        text.setTextAlign(Paint.Align.CENTER);
        line.setStyle(Paint.Style.STROKE);
        setBackgroundColor(0xFF0F1318);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** The size setting of the layout's controls, so the editor shows them as the game will. */
    void setScale(int percent) {
        scale = percent;
        relayout();
    }

    void setLayout(LayoutData l) {
        layout = l;
        selected = null;
        collapsed.clear();
        rebuild();
        if (listener != null) listener.onSelect(null);
    }

    Item selected() {
        return selected;
    }

    boolean isCollapsed(Item drawer) {
        return drawer.kind == DRAWER && collapsed.contains(drawer.drawer);
    }

    void toggleCollapsed(Item drawer) {
        if (drawer.kind != DRAWER) return;
        if (!collapsed.remove(drawer.drawer)) collapsed.add(drawer.drawer);
        invalidate();
    }

    // ---------------------------------------------------------------- model -> items

    private Item make(int kind, LayoutData.Btn d, LayoutData.Drawer dr, int index) {
        Item it = new Item();
        it.kind = kind;
        it.d = d;
        it.drawer = dr;
        it.index = index;
        if (kind == BUTTON || kind == SUB) {
            it.ok = false;
            for (int k : d.keys) {
                if (LayoutKeys.action(k, layout.glfwKeys()) != null) {
                    it.ok = true;
                    break;
                }
            }
        }
        return it;
    }

    /** Rebuilds the item list from the data; keeps the same control selected if it still exists. */
    void rebuild() {
        LayoutData.Btn keep = selected != null ? selected.d : null;
        items.clear();
        drawerItems.clear();
        selected = null;
        for (LayoutData.Btn b : layout.buttons) items.add(make(BUTTON, b, null, 0));
        for (LayoutData.Drawer dr : layout.drawers) {
            Item di = make(DRAWER, dr.props, dr, 0);
            items.add(di);
            drawerItems.put(dr, di);
            for (int i = 0; i < dr.subs.size(); i++) items.add(make(SUB, dr.subs.get(i), dr, i));
        }
        for (LayoutData.Btn j : layout.joysticks) items.add(make(JOY, j, null, 0));
        if (keep != null) for (Item it : items) if (it.d == keep) selected = it;
        relayout();
    }

    // ---------------------------------------------------------------- geometry (same rules as ControlsView)

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        relayout();
    }

    /** Recomputes every control's pixel box from its expressions. Call after editing sizes or expressions. */
    void relayout() {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        for (Item it : items) {
            if (it.kind == SUB && !it.isFreeSub()) {
                Item dr = drawerItems.get(it.drawer);
                it.w = dr.w;
                it.h = dr.h;
                it.x = dr.x;
                it.y = dr.y;
                float stepX = (dr.w + margin) * (it.index + 1), stepY = (dr.h + margin) * (it.index + 1);
                switch (it.drawer.orientation == null ? "LEFT" : it.drawer.orientation) {
                    case "RIGHT": it.x += stepX; break;
                    case "LEFT": it.x -= stepX; break;
                    case "UP": it.y -= stepY; break;
                    case "DOWN": it.y += stepY; break;
                    default: break;
                }
            } else {
                it.w = it.d.w * density * scale / 100f;
                it.h = it.d.h * density * scale / 100f;
                position(it, W, H);
            }
            it.box.x = it.x;
            it.box.y = it.y;
            it.box.w = it.w;
            it.box.h = it.h;
            it.box.wDp = it.d.w;
            it.box.hDp = it.d.h;
            it.box.dx = it.d.dx;
            it.box.dy = it.d.dy;
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
        it.x = LayoutExpr.eval(it.d.dx, vars, density);
        it.y = LayoutExpr.eval(it.d.dy, vars, density);
    }

    private boolean visible(Item it) {
        return !(it.kind == SUB && collapsed.contains(it.drawer));
    }

    // ---------------------------------------------------------------- drawing

    private static int withAlpha(int color, float a) {
        return Color.argb(Math.round(Color.alpha(color) * a), Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override
    protected void onDraw(Canvas c) {
        drawGrid(c);
        for (Item it : items) {
            if (!visible(it)) continue;
            if (it.kind == JOY) drawJoystick(c, it);
            else drawButton(c, it);
        }
        if (selected != null && visible(selected)) {
            line.setPathEffect(null);
            line.setStrokeWidth(2 * density);
            line.setColor(0xFF4FC3F7);
            rect.set(selected.x - 2 * density, selected.y - 2 * density, selected.x + selected.w + 2 * density, selected.y + selected.h + 2 * density);
            c.drawRoundRect(rect, 4 * density, 4 * density, line);
        }
        if (items.isEmpty()) {
            text.setColor(0xFF8899AA);
            text.setTextSize(15 * density);
            c.drawText("Пусто. Меню ▸ Добавить кнопку", getWidth() / 2f, getHeight() / 2f, text);
        }
    }

    private void drawGrid(Canvas c) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x14FFFFFF);
        float step = 40 * density;
        for (float x = step; x < getWidth(); x += step) c.drawRect(x, 0, x + 1, getHeight(), fill);
        for (float y = step; y < getHeight(); y += step) c.drawRect(0, y, getWidth(), y + 1, fill);
    }

    private void drawButton(Canvas c, Item it) {
        LayoutData.Btn d = it.d;
        float a = Math.max(0.35f, Math.min(1f, d.opacity));
        float r = Math.min(it.w, it.h) / 2f * d.radius / 100f;
        rect.set(it.x, it.y, it.x + it.w, it.y + it.h);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(withAlpha(d.bg, a));
        c.drawRoundRect(rect, r, r, fill);
        // a faint outline so fully transparent buttons can still be found and grabbed
        line.setPathEffect(dash);
        line.setStrokeWidth(Math.max(1f, density));
        line.setColor(it.ok ? 0x55FFFFFF : 0xCCFF5252);
        c.drawRoundRect(rect, r, r, line);
        line.setPathEffect(null);
        if (d.strokeW > 0) {
            float sw = d.strokeW * density;
            line.setStrokeWidth(sw);
            line.setColor(withAlpha(d.stroke, a));
            rect.inset(sw / 2f, sw / 2f);
            c.drawRoundRect(rect, r, r, line);
            rect.set(it.x, it.y, it.x + it.w, it.y + it.h);
        }
        String label = d.name == null ? "" : d.name;
        if (it.kind == DRAWER) label = (label.isEmpty() ? "▤" : label) + (collapsed.contains(it.drawer) ? " ▸" : "");
        if (!label.isEmpty()) {
            float size = 13 * density, room = it.w - 6 * density;
            text.setTextSize(size);
            float tw = text.measureText(label);
            if (tw > room && room > 0) {
                size = Math.max(7 * density, size * room / tw);
                text.setTextSize(size);
            }
            text.setColor(withAlpha(0xFFFFFFFF, Math.max(a, 0.6f)));
            Paint.FontMetrics fm = text.getFontMetrics();
            c.drawText(label, it.x + it.w / 2f, it.y + it.h / 2f - (fm.ascent + fm.descent) / 2f, text);
        }
        if (!it.ok) {                                  // no key Horizon understands: the game hides this button
            text.setColor(0xFFFF5252);
            text.setTextSize(11 * density);
            c.drawText("✕", it.x + it.w - 7 * density, it.y + 11 * density, text);
        }
    }

    private void drawJoystick(Canvas c, Item it) {
        LayoutData.Btn d = it.d;
        float a = Math.max(0.35f, Math.min(1f, d.opacity));
        float radius = Math.min(it.w, it.h) / 2f;
        float cx = it.x + it.w / 2f, cy = it.y + it.h / 2f;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(withAlpha(d.bg, a));
        c.drawCircle(cx, cy, radius, fill);
        line.setPathEffect(dash);
        line.setStrokeWidth(Math.max(1f, density));
        line.setColor(0x55FFFFFF);
        c.drawCircle(cx, cy, radius, line);
        line.setPathEffect(null);
        if (d.strokeW > 0) {
            line.setStrokeWidth(d.strokeW * density);
            line.setColor(withAlpha(d.stroke, a));
            c.drawCircle(cx, cy, radius, line);
        }
        fill.setColor(withAlpha(0x88FFFFFF, a));
        c.drawCircle(cx, cy, radius * 0.4f, fill);
    }

    // ---------------------------------------------------------------- touches

    private static boolean contains(Item it, float x, float y) {
        return x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h;
    }

    private Item hit(float x, float y) {
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (visible(it) && contains(it, x, y)) return it;
        }
        return null;
    }

    private static boolean canDrag(Item it) {
        return it.kind != SUB || it.isFreeSub();      // sub-buttons of a directional drawer follow the drawer
    }

    void select(Item it) {
        selected = it;
        invalidate();
        if (listener != null) listener.onSelect(it);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = e.getPointerId(0);
                downX = e.getX();
                downY = e.getY();
                touched = hit(downX, downY);
                dragging = false;
                if (touched != null) {
                    offX = downX - touched.x;
                    offY = downY - touched.y;
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(pointer);
                if (i < 0 || touched == null) return true;
                float x = e.getX(i), y = e.getY(i);
                if (!dragging) {
                    if (Math.hypot(x - downX, y - downY) < slop || !canDrag(touched)) return true;
                    dragging = true;
                    if (selected != touched) {
                        selected = touched;
                        if (listener != null) listener.onSelect(touched);
                    }
                }
                drag(touched, x - offX, y - offY);
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (e.getPointerId(e.getActionIndex()) != pointer) return true;
                if (dragging) {
                    relayout();
                    if (listener != null) {
                        listener.onEdited();
                        listener.onSelect(selected);   // the panel may need to switch sides and refresh the formulas
                    }
                } else {
                    select(touched);
                }
                touched = null;
                pointer = -1;
                dragging = false;
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                touched = null;
                pointer = -1;
                dragging = false;
                relayout();
                return true;

            default:
                return true;
        }
    }

    private void drag(Item it, float nx, float ny) {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        nx = Math.max(0, Math.min(nx, W - it.w));
        ny = Math.max(0, Math.min(ny, H - it.h));
        List<LayoutSnap.Box> others = new ArrayList<>();
        for (Item o : items) {
            if (o == it || !visible(o)) continue;
            if (it.kind == DRAWER && o.kind == SUB && o.drawer == it.drawer) continue;   // they move with the drawer
            if (o.kind == SUB && !o.isFreeSub()) continue;                               // their own formulas are unused
            others.add(o.box);
        }
        String[] r = LayoutSnap.snap(it.box, nx, ny, others, W, H, density, margin);
        it.d.dx = r[0];
        it.d.dy = r[1];
        relayout();
    }

    // ---------------------------------------------------------------- add / clone / delete

    private static final String CENTER_X = "0.5 * ${screen_width}", CENTER_Y = "0.5 * ${screen_height}";

    private Item find(LayoutData.Btn b) {
        for (Item it : items) if (it.d == b) return it;
        return null;
    }

    private void added(LayoutData.Btn b) {
        rebuild();
        if (listener != null) listener.onEdited();
        select(find(b));
    }

    void addButton() {
        LayoutData.Btn b = new LayoutData.Btn();
        b.name = "New";
        b.dx = CENTER_X;
        b.dy = CENTER_Y;
        layout.buttons.add(b);
        added(b);
    }

    void addDrawer() {
        LayoutData.Drawer dr = new LayoutData.Drawer();
        dr.props.name = "Drawer";
        dr.props.dx = CENTER_X;
        dr.props.dy = CENTER_Y;
        layout.drawers.add(dr);
        added(dr.props);
    }

    void addJoystick() {
        LayoutData.Btn j = new LayoutData.Btn();
        j.name = "";
        j.w = 100;       // Mojo's default is 50 dp, too small to steer with a thumb
        j.h = 100;
        j.dx = CENTER_X;
        j.dy = CENTER_Y;
        layout.joysticks.add(j);
        added(j);
    }

    /** Adds a button to the drawer that is selected (or whose sub-button is selected). */
    void addSub() {
        if (selected == null || (selected.kind != DRAWER && selected.kind != SUB)) return;
        LayoutData.Btn b = new LayoutData.Btn();
        b.name = "New";
        b.dx = CENTER_X;
        b.dy = CENTER_Y;
        selected.drawer.subs.add(b);
        collapsed.remove(selected.drawer);
        added(b);
    }

    void cloneSelected() {
        if (selected == null) return;
        LayoutData.Btn made;
        switch (selected.kind) {
            case BUTTON: {
                LayoutData.Btn b = selected.d.copy(false);
                b.dx = CENTER_X;
                b.dy = CENTER_Y;
                layout.buttons.add(b);
                made = b;
                break;
            }
            case DRAWER: {
                LayoutData.Drawer dr = selected.drawer.copy();
                dr.props.dx = CENTER_X;
                dr.props.dy = CENTER_Y;
                layout.drawers.add(dr);
                made = dr.props;
                break;
            }
            case SUB: {
                LayoutData.Btn b = selected.d.copy(false);
                if (selected.isFreeSub()) {
                    b.dx = CENTER_X;
                    b.dy = CENTER_Y;
                }
                selected.drawer.subs.add(b);
                made = b;
                break;
            }
            default: {
                LayoutData.Btn j = selected.d.copy(true);
                j.dx = CENTER_X;
                j.dy = CENTER_Y;
                layout.joysticks.add(j);
                made = j;
            }
        }
        added(made);
    }

    void deleteSelected() {
        if (selected == null) return;
        switch (selected.kind) {
            case BUTTON: layout.buttons.remove(selected.d); break;
            case DRAWER: layout.drawers.remove(selected.drawer); break;
            case SUB: selected.drawer.subs.remove(selected.d); break;
            default: layout.joysticks.remove(selected.d);
        }
        selected = null;
        rebuild();
        if (listener != null) {
            listener.onEdited();
            listener.onSelect(null);
        }
    }
}
