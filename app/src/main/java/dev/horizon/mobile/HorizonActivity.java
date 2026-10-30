package dev.horizon.mobile;

import android.app.NativeActivity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

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
        if (Settings.vr(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            vr = new VrSensors(this);
            if (!vr.available()) {
                android.widget.Toast.makeText(this, "В телефоне нет датчика поворота: VR без гироскопа", android.widget.Toast.LENGTH_LONG).show();
            }
        }
        try {
            // The overlay window cannot take the keyboard (it must not take the focus from the game), so a 1x1
            // view inside the game's own window does: it is what Gboard, Yandex Keyboard and the rest talk to.
            ime = new ImeView(this);
            addContentView(ime, new ViewGroup.LayoutParams(1, 1));
        } catch (Throwable e) {
            ime = null;
        }
    }

    private ImeView ime;
    private VrSensors vr;
    private boolean keyboardShown;

    /** Shows or hides the on-screen keyboard (any thread). */
    void setKeyboard(final boolean show) {
        runOnUiThread(() -> {
            if (ime == null) return;
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            keyboardShown = show;
            if (show) {
                ime.requestFocus();
                imm.restartInput(ime);
                imm.showSoftInput(ime, 0);
            } else {
                imm.hideSoftInputFromWindow(ime.getWindowToken(), 0);
            }
        });
    }

    void toggleKeyboard() {
        setKeyboard(!keyboardShown);
    }

    /**
     * What the on-screen keyboard types into. NativeActivity has no text input of its own, so letters from a
     * composing keyboard never arrived. Typed text, backspace and enter go to the game as real keys.
     */
    private static final class ImeView extends View {
        ImeView(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return true;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo out) {
            out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
            return new Connection(this);
        }
    }

    private static final class Connection extends BaseInputConnection {
        /** What the keyboard is composing now (a word being typed); the game has already been sent it. */
        private String composing = "";

        Connection(View v) {
            super(v, false);
        }

        private static void key(int code, boolean down) {
            HorizonInput.nativeKey(0, code, down);
        }

        /** Makes what the game has typed equal to `now`: backspaces over what changed, then the new tail. */
        private void replaceComposing(String now) {
            int common = 0, max = Math.min(composing.length(), now.length());
            while (common < max && composing.charAt(common) == now.charAt(common)) common++;
            for (int i = composing.length(); i > common; i--) {
                key(KeyEvent.KEYCODE_DEL, true);
                key(KeyEvent.KEYCODE_DEL, false);
            }
            if (now.length() > common) HorizonInput.nativeText(now.substring(common));
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursor) {
            String now = text.toString();
            replaceComposing(now);
            composing = now;
            return true;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursor) {
            replaceComposing(text.toString());
            composing = "";
            return true;
        }

        @Override
        public boolean finishComposingText() {
            composing = "";
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            for (int i = 0; i < before; i++) {
                key(KeyEvent.KEYCODE_DEL, true);
                key(KeyEvent.KEYCODE_DEL, false);
            }
            for (int i = 0; i < after; i++) {
                key(KeyEvent.KEYCODE_FORWARD_DEL, true);
                key(KeyEvent.KEYCODE_FORWARD_DEL, false);
            }
            composing = "";
            return true;
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            key(KeyEvent.KEYCODE_ENTER, true);
            key(KeyEvent.KEYCODE_ENTER, false);
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent e) {
            int code = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_MULTIPLE && e.getCharacters() != null) {
                HorizonInput.nativeText(e.getCharacters());
                return true;
            }
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            int unicode = e.getUnicodeChar();
            if (unicode != 0 && code != KeyEvent.KEYCODE_ENTER) {
                if (down) HorizonInput.nativeText(new String(Character.toChars(unicode)));
                return true;
            }
            key(code, down);
            return true;
        }
    }

    private View overlay;

    /**
     * NativeActivity takes the window's surface, so views added to its own window are never drawn
     * and never get touches. The controls live in a separate panel window above the game instead.
     */
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (overlay != null || (Settings.vr(this) && !Settings.vrControls(this))) return;
        try {
            overlay = LayoutStore.createOverlay(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM   // keeps it below the keyboard, which then gets its own touches
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
    protected void onResume() {
        super.onResume();
        if (vr != null) vr.start();
    }

    @Override
    protected void onPause() {
        if (vr != null) vr.stop();
        super.onPause();
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
