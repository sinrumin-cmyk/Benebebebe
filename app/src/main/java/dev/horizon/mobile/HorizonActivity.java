package dev.horizon.mobile;

import android.app.NativeActivity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

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

        super.onCreate(state);
        addContentView(new ControlOverlay(this),
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
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
