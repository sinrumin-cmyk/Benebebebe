package dev.horizon.mobile;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The main menu for a phone in a viewer. The menu is drawn once as a flat panel and put on screen twice, one copy
 * per eye, bent by the same lens distortion the game uses (K1, K2 from vr.txt, vr.wgsl's formula), so it sits right
 * behind Cardboard lenses. It is driven by a gamepad (D-pad or left stick, A to choose, B to leave) and by the right
 * hand (palm moves a cursor, pinch clicks, sweep sideways changes a value), so nothing needs the touch screen.
 */
public class VrMenuActivity extends Activity {
    private static final int PANEL_W = 1280, PANEL_H = 720;
    private static final int CAMERA_REQUEST = 42;

    private static final class Item {
        final String label;
        final Supplier<String> value;
        final Runnable activate;
        final IntConsumer adjust;

        Item(String label, Supplier<String> value, Runnable activate, IntConsumer adjust) {
            this.label = label;
            this.value = value;
            this.activate = activate;
            this.adjust = adjust;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private int selected;
    private int hover = -1;
    private boolean pointerShown;
    private float pointerX, pointerY;     // 0..1 across the panel
    private long lastNav;
    private MenuView view;
    private HandTracker hands;
    private float k1 = 0.441f, k2 = 0.156f;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams a = getWindow().getAttributes();
            a.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(a);
        }
        readLens();

        items.add(new Item("Играть",
                () -> Dirs.hasResources(this) ? (Settings.startInWorld(this) ? "сразу в мир" : "главное меню игры") : "нет файлов игры",
                () -> Launch.play(this), null));
        items.add(new Item("Ввод",
                () -> Settings.inputModeName(Settings.inputMode(this)),
                () -> cycleMode(1), d -> cycleMode(d)));
        items.add(new Item("Дальность прорисовки",
                () -> Settings.prefs(this).getInt("distance", 8) + " чанков",
                null, d -> {
                    int v = Math.max(2, Math.min(24, Settings.prefs(this).getInt("distance", 8) + d));
                    Settings.prefs(this).edit().putInt("distance", v).commit();
                }));
        items.add(new Item("VR в игре",
                () -> Settings.vr(this) ? "включено" : "выключено",
                () -> toggleVr(), d -> toggleVr()));
        items.add(new Item("Выход", () -> "", () -> finish(), null));

        view = new MenuView();
        setContentView(view);
        hideSystemUi();
        if (Settings.hands(this)) hands = new HandTracker(this, new HandsOutput());
    }

    private void cycleMode(int d) {
        int m = Settings.inputMode(this) - Settings.MODE_HANDS;
        m = ((m + d) % 3 + 3) % 3 + Settings.MODE_HANDS;
        Settings.prefs(this).edit().putInt("input_mode", m).commit();
        // The camera follows the mode.
        if (hands != null) { hands.stop(); hands = null; }
        if (Settings.hands(this)) {
            hands = new HandTracker(this, new HandsOutput());
            startHands();
        } else {
            pointerShown = false;
            hover = -1;
        }
    }

    private void toggleVr() {
        Settings.prefs(this).edit().putBoolean("vr", !Settings.vr(this)).commit();
    }

    /** K1 and K2 as the game will read them (vr.txt next to game-env.txt), so the menu bends like the game does. */
    private void readLens() {
        File f = new File(Dirs.home(this), "vr.txt");
        if (!f.isFile()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                try {
                    if (line.startsWith("HORIZON_VR_K1=")) k1 = Float.parseFloat(line.substring(14).trim());
                    if (line.startsWith("HORIZON_VR_K2=")) k2 = Float.parseFloat(line.substring(14).trim());
                } catch (NumberFormatException ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ navigation

    private void move(int d) {
        selected = Math.max(0, Math.min(items.size() - 1, selected + d));
        view.invalidate();
    }

    private void adjust(int d) {
        Item it = items.get(selected);
        if (it.adjust != null) it.adjust.accept(d);
        view.invalidate();
    }

    private void activate(int index) {
        if (index < 0 || index >= items.size()) return;
        selected = index;
        Item it = items.get(index);
        if (it.activate != null) it.activate.run();
        else if (it.adjust != null) it.adjust.accept(1);
        view.invalidate();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean pad = GamepadInput.fromPad(e.getSource());
        boolean ours = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_B
                || code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BACK;
        if (!ours) return super.dispatchKeyEvent(e);
        if (pad && !Settings.pad(this)) return true;          // hands-only mode: a gamepad does nothing
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: move(-1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: move(1); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: adjust(-1); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: adjust(1); break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A: activate(selected); break;
                case KeyEvent.KEYCODE_BUTTON_START: activate(0); break;
                default: finish(); break;                       // B and Back
            }
        }
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (!GamepadInput.isPadMotion(e)) return super.dispatchGenericMotionEvent(e);
        if (!Settings.pad(this)) return true;
        long now = SystemClock.uptimeMillis();
        float y = e.getAxisValue(MotionEvent.AXIS_Y) + e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float x = e.getAxisValue(MotionEvent.AXIS_X) + e.getAxisValue(MotionEvent.AXIS_HAT_X);
        if (now - lastNav > 260) {
            if (y < -0.6f) { lastNav = now; move(-1); }
            else if (y > 0.6f) { lastNav = now; move(1); }
            else if (x < -0.6f) { lastNav = now; adjust(-1); }
            else if (x > 0.6f) { lastNav = now; adjust(1); }
        }
        return true;
    }

    /** The hands, as the menu sees them: a cursor, pinch = click, sweep = change the value, the left hand's stick = move. */
    private final class HandsOutput implements HandTracker.Output {
        private long lastStick;

        @Override public void button(int index, boolean down) {
            if (!down) return;
            if (index == HorizonInput.RT) runOnUiThread(() -> { if (hover >= 0) activate(hover); });
            else if (index == HorizonInput.LB) runOnUiThread(() -> adjust(-1));
            else if (index == HorizonInput.RB) runOnUiThread(() -> adjust(1));
        }

        @Override public void stick(float x, float y) {
            long now = SystemClock.uptimeMillis();
            if (now - lastStick < 450) return;
            if (y > 0.8f) { lastStick = now; runOnUiThread(() -> move(-1)); }
            else if (y < -0.8f) { lastStick = now; runOnUiThread(() -> move(1)); }
        }

        @Override public void pointer(boolean present, float x, float y) {
            runOnUiThread(() -> {
                if (!present) { pointerShown = false; hover = -1; view.invalidate(); return; }
                // The middle of the picture is the hand's comfortable range: its edges would be at the arm's limit.
                float nx = Math.max(0f, Math.min(1f, (x - 0.15f) / 0.7f));
                float ny = Math.max(0f, Math.min(1f, (y - 0.15f) / 0.7f));
                pointerX = pointerShown ? 0.6f * pointerX + 0.4f * nx : nx;
                pointerY = pointerShown ? 0.6f * pointerY + 0.4f * ny : ny;
                pointerShown = true;
                hover = view.itemAt(pointerX * PANEL_W, pointerY * PANEL_H);
                if (hover >= 0) selected = hover;
                view.invalidate();
            });
        }
    }

    // ------------------------------------------------------------------ hands, lifecycle

    private void startHands() {
        if (hands == null) return;
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) hands.start();
        else requestPermissions(new String[] {android.Manifest.permission.CAMERA}, CAMERA_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != CAMERA_REQUEST || hands == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) hands.start();
        else android.widget.Toast.makeText(this, "Без доступа к камере руки не отслеживаются", android.widget.Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startHands();
        view.invalidate();
    }

    @Override
    protected void onPause() {
        if (hands != null) hands.stop();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------ drawing

    private final class MenuView extends View {
        private static final int GRID = 16;
        private static final int TOP = 190, STEP = 96, HEIGHT = 80, LEFT = 100, RIGHT = PANEL_W - 100;

        private final Bitmap panel = Bitmap.createBitmap(PANEL_W, PANEL_H, Bitmap.Config.ARGB_8888);
        private final Canvas pc = new Canvas(panel);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float[][] mesh = new float[2][];
        private int meshW, meshH;

        MenuView() {
            super(VrMenuActivity.this);
            setFocusable(false);
        }

        /** The item under a point of the panel, or -1. */
        int itemAt(float x, float y) {
            for (int i = 0; i < items.size(); i++) {
                int top = TOP + i * STEP;
                if (x >= LEFT && x <= RIGHT && y >= top && y <= top + HEIGHT) return i;
            }
            return -1;
        }

        private void drawPanel() {
            pc.drawColor(0xFF12161C);
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setColor(Color.WHITE);
            paint.setTextSize(72);
            paint.setTextAlign(Paint.Align.LEFT);
            pc.drawText("Horizon VR", LEFT, 110, paint);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setColor(0xFF8D99A8);
            paint.setTextSize(30);
            pc.drawText("Меню для шлема", LEFT, 152, paint);

            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                int top = TOP + i * STEP;
                rect.set(LEFT, top, RIGHT, top + HEIGHT);
                paint.setColor(i == selected ? 0xFF2F6FE4 : 0xFF212833);
                pc.drawRoundRect(rect, 28, 28, paint);
                paint.setColor(Color.WHITE);
                paint.setTextSize(42);
                paint.setTextAlign(Paint.Align.LEFT);
                pc.drawText(it.label, LEFT + 36, top + 54, paint);
                String v = it.value.get();
                if (!v.isEmpty()) {
                    paint.setTextAlign(Paint.Align.RIGHT);
                    paint.setColor(i == selected ? Color.WHITE : 0xFFB5C0CE);
                    paint.setTextSize(36);
                    pc.drawText(it.adjust != null ? "<  " + v + "  >" : v, RIGHT - 36, top + 52, paint);
                }
            }

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(0xFF8D99A8);
            paint.setTextSize(28);
            String pad = GamepadInput.connected() ? "есть" : "нет";
            String hand = Settings.hands(VrMenuActivity.this) ? "камера" : "выкл";
            pc.drawText("Геймпад: " + pad + "     Руки: " + hand, LEFT, PANEL_H - 56, paint);
            pc.drawText("D-pad / стик, A — выбрать, B — назад.  Руки: ладонь двигает, щипок — выбрать, взмах — менять", LEFT, PANEL_H - 22, paint);

            if (pointerShown) {
                paint.setColor(0xFFFFD54F);
                pc.drawCircle(pointerX * PANEL_W, pointerY * PANEL_H, 16, paint);
                paint.setColor(0x66000000);
                pc.drawCircle(pointerX * PANEL_W, pointerY * PANEL_H, 6, paint);
            }
        }

        /**
         * Where the panel's grid points land on the screen. The game's shader finds, for every screen point p, the
         * eye image point q = p * (1 + k1 r^2 + k2 r^4) * scale (r = |p|, x stretched by the aspect); here the panel
         * point q is given, so p is found by bisection on the radius.
         */
        private float[] buildMesh(int eye, int w, int h) {
            final float eyeW = w / 2f, eyeH = h;
            float pw = eyeW * 0.80f, ph = pw * PANEL_H / PANEL_W;
            if (ph > eyeH * 0.85f) { ph = eyeH * 0.85f; pw = ph * PANEL_W / PANEL_H; }
            final float shift = (eye == 0 ? 1 : -1) * 0.010f * eyeW;    // a little parallax: the panel floats in front
            final float asp = eyeW / eyeH;
            final float scale = 1f / Math.max(0.1f, 1f + k1 + k2);
            float[] v = new float[(GRID + 1) * (GRID + 1) * 2];
            int k = 0;
            for (int j = 0; j <= GRID; j++) {
                for (int i = 0; i <= GRID; i++) {
                    float px = eyeW / 2 + (i / (float) GRID - 0.5f) * pw + shift;
                    float py = eyeH / 2 + (j / (float) GRID - 0.5f) * ph;
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

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            if (w != meshW || h != meshH) {
                mesh[0] = buildMesh(0, w, h);
                mesh[1] = buildMesh(1, w, h);
                meshW = w;
                meshH = h;
            }
            drawPanel();
            canvas.drawColor(Color.BLACK);
            for (int eye = 0; eye < 2; eye++) {
                canvas.save();
                canvas.clipRect(eye * w / 2f, 0, (eye + 1) * w / 2f, h);
                canvas.drawBitmapMesh(panel, GRID, GRID, mesh[eye], 0, null, 0, null);
                canvas.restore();
            }
            postInvalidateDelayed(700);    // the gamepad may be plugged in while the menu is open
        }
    }
}
