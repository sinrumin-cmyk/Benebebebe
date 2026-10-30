package dev.horizon.mobile;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand tracking check: what the camera sees, with the hands the model found drawn on it, and a step-by-step status
 * (model loaded, camera opened, frames arriving, hands found) that shows where it stops working. Here the user also
 * picks the camera and turns the picture if it comes out sideways.
 */
public class HandTestActivity extends Activity {
    private static final int CAMERA_REQUEST = 43;

    private HandTracker tracker;
    private volatile HandFrame frame;
    private PreviewView preview;
    private TextView status;
    private Button cameraButton, rotationButton;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private List<HandTracker.CameraOption> cameras = new ArrayList<>();
    private int lastFrames;
    private long lastTick;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        final float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF0E1116);

        preview = new PreviewView();
        root.addView(preview, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding((int) (12 * dp), (int) (12 * dp), (int) (12 * dp), (int) (12 * dp));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        side.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        cameraButton = button("", v -> nextCamera());
        rotationButton = button("", v -> nextRotation());
        side.addView(cameraButton);
        side.addView(rotationButton);
        side.addView(button("Закрыть", v -> finish()));
        root.addView(side, new LinearLayout.LayoutParams((int) (300 * dp), ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        cameras = HandTracker.cameras(this);
        refreshButtons();

        tracker = new HandTracker(this, new HandTracker.Output() {
            @Override public void button(int index, boolean down) {}
            @Override public void stick(float x, float y) {}
            @Override public void pointer(boolean present, float x, float y) {}
            @Override public void frame(HandFrame f) {
                frame = f;
                preview.postInvalidateOnAnimation();
            }
        });
        tracker.setPreview(true);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void refreshButtons() {
        String want = Settings.handCamera(this);
        String name = "Авто (самая широкая)";
        for (HandTracker.CameraOption o : cameras) if (o.id.equals(want)) name = o.label;
        cameraButton.setText("Камера: " + name + "  (сменить)");
        rotationButton.setText("Поворот кадра: +" + 90 * Settings.handRotation(this) + "°  (сменить)");
    }

    /** Auto, then each back camera in turn. */
    private void nextCamera() {
        String want = Settings.handCamera(this);
        int at = -1;
        for (int i = 0; i < cameras.size(); i++) if (cameras.get(i).id.equals(want)) at = i;
        String next = at + 1 < cameras.size() ? cameras.get(at + 1).id : "";
        Settings.prefs(this).edit().putString("hand_camera", next).commit();
        refreshButtons();
        restart();
    }

    private void nextRotation() {
        Settings.prefs(this).edit().putInt("hand_rotation", (Settings.handRotation(this) + 1) % 4).commit();
        refreshButtons();   // read for every frame: nothing to restart
    }

    private void restart() {
        tracker.stop();
        startTracker();
    }

    private void startTracker() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) tracker.start();
        else requestPermissions(new String[] {android.Manifest.permission.CAMERA}, CAMERA_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != CAMERA_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) tracker.start();
        else Toast.makeText(this, "Без доступа к камере руки не отслеживаются", Toast.LENGTH_LONG).show();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            int frames = tracker.framesSeen;
            float fps = lastTick == 0 ? 0 : (frames - lastFrames) * 1000f / Math.max(1, now - lastTick);
            lastFrames = frames;
            lastTick = now;
            HandFrame f = frame;
            StringBuilder t = new StringBuilder(tracker.report());
            t.append("\nКадров в секунду: ").append(Math.round(fps));
            t.append("\nЛевая рука: ").append(f != null && f.present[HandFrame.LEFT] ? name(f.state[HandFrame.LEFT]) : "нет");
            t.append("\nПравая рука: ").append(f != null && f.present[HandFrame.RIGHT] ? name(f.state[HandFrame.RIGHT]) : "нет");
            t.append("\n\nПокажи ладонь камере на 20–50 см. Картинка боком или вверх ногами — «Поворот кадра». "
                    + "Чёрная или пустая — смени камеру. Левая рука — та, что в левой половине кадра.");
            status.setText(t);
            ui.postDelayed(this, 300);
        }
    };

    private static String name(int state) {
        return state == HandFrame.PINCH ? "щипок" : state == HandFrame.FIST ? "кулак" : "открыта";
    }

    @Override
    protected void onResume() {
        super.onResume();
        startTracker();
        lastTick = 0;
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        tracker.stop();
        super.onPause();
    }

    /** The camera picture with the skeleton drawn on it, as large as fits. */
    private final class PreviewView extends View {
        private final HandRender render = new HandRender();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PreviewView() { super(HandTestActivity.this); }

        @Override
        protected void onDraw(Canvas c) {
            HandFrame f = frame;
            float w = getWidth(), h = getHeight();
            if (f == null || f.preview == null) {
                paint.setColor(0xFF8D99A8);
                paint.setTextSize(36);
                paint.setTextAlign(Paint.Align.CENTER);
                c.drawText("Нет кадров с камеры", w / 2, h / 2, paint);
                return;
            }
            float pw = w, ph = pw / f.aspect;
            if (ph > h) { ph = h; pw = ph * f.aspect; }
            render.debug(c, f, (w - pw) / 2, (h - ph) / 2, pw, ph);
        }
    }
}
