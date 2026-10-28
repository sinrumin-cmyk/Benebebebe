package dev.horizon.mobile;

import android.app.NativeActivity;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

public class HorizonActivity extends NativeActivity {
    @Override
    protected void onCreate(Bundle state) {
        // A Java-side failure (a library that won't load, say) is written where the Logs screen reads it.
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                File f = Dirs.javaCrash(this);
                f.getParentFile().mkdirs();
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                try (FileWriter w = new FileWriter(f)) { w.write(new java.util.Date() + "\nthread " + t.getName() + "\n" + sw); }
            } catch (Throwable ignored) {}
            if (old != null) old.uncaughtException(t, e);
        });

        // Draw under the display cutout (the black strip on the left in landscape).
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams a = getWindow().getAttributes();
            a.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(a);
        }

        super.onCreate(state);
        hideSystemUi();
    }

    private View overlay;

    /**
     * NativeActivity takes the window's surface, so views added to its own window are never drawn
     * and never get touches. The controls live in a separate panel window above the game instead.
     */
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (overlay != null) return;
        try {
            overlay = new ControlOverlay(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.token = getWindow().getDecorView().getWindowToken();
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                        ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            getWindowManager().addView(overlay, lp);
            hideSystemUi();
        } catch (Throwable e) {
            overlay = null;
            try {
                File f = Dirs.javaCrash(this);
                f.getParentFile().mkdirs();
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                try (FileWriter w = new FileWriter(f)) { w.write(new java.util.Date() + "\ncontrol overlay\n" + sw); }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        try { if (overlay != null) getWindowManager().removeViewImmediate(overlay); } catch (Throwable ignored) {}
        super.onDestroy();
        // android_main can only run once per process: leave the :game process so the next Play starts clean.
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
