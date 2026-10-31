package dev.horizon.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/**
 * Everything drawn over the game: the controls (a layout or the simple buttons) and the
 * mouse cursor. Also takes a real mouse (move, buttons, wheel) and asks the activity for the on-screen
 * keyboard when the game has a text field in focus.
 */
public final class GameOverlay extends FrameLayout implements ControlsView.Host {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float dp;
    private final Status status;
    private boolean vmouse, mouseUsed, wantedText;
    private float lastX = Float.NaN, lastY = Float.NaN;
    private int buttons;

    public GameOverlay(Context c, View controls) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        addView(controls, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        status = new Status(c);
        addView(status, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (controls instanceof ControlsView) ((ControlsView) controls).setHost(this);
        if (Build.VERSION.SDK_INT >= 24) setPointerIcon(PointerIcon.getSystemIcon(c, PointerIcon.TYPE_NULL));   // our own cursor is drawn
    }

    // ---------------------------------------------------------------- polling the game

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                boolean wants = HorizonInput.nativeWantsText();
                if (wants != wantedText) {
                    wantedText = wants;
                    if (getContext() instanceof HorizonActivity) ((HorizonActivity) getContext()).setKeyboard(wants);
                }
                if (vmouse || mouseUsed) status.invalidate();     // the cursor moves with the game's own state
            } catch (UnsatisfiedLinkError ignored) {
                // the game library is not loaded yet
            }
            handler.postDelayed(this, vmouse || mouseUsed ? 32 : 120);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestApplyInsets();
        handler.post(poll);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(poll);
        super.onDetachedFromWindow();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        int l = 0, t = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout dc = in.getDisplayCutout();
            if (dc != null) {
                l = dc.getSafeInsetLeft();
                t = dc.getSafeInsetTop();
            }
        }
        status.setInsets(l, t);
        return super.onApplyWindowInsets(in);
    }

    // ---------------------------------------------------------------- ControlsView.Host

    @Override
    public void toggleKeyboard() {
        if (getContext() instanceof HorizonActivity) ((HorizonActivity) getContext()).toggleKeyboard();
    }

    @Override
    public void virtualMouseChanged(boolean on) {
        vmouse = on;
        status.invalidate();
    }

    // ---------------------------------------------------------------- a real mouse

    private boolean fromMouse(MotionEvent e) {
        // DeX reports its pointer as a mouse but can also set SOURCE_TOUCHSCREEN; the tool type and
        // any mouse-like source say what it is.
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return true;
        int sources = e.getSource();
        return (sources & (InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_MOUSE_RELATIVE
                | InputDevice.SOURCE_TOUCHPAD | InputDevice.SOURCE_TRACKBALL)) != 0;
    }

    private void moved(float x, float y) {
        if (!Float.isNaN(lastX)) HorizonInput.nativeMouseMove(x - lastX, y - lastY);
        lastX = x;
        lastY = y;
        mouseUsed = true;
    }

    private void buttonState(int now) {
        int[][] map = {{MotionEvent.BUTTON_PRIMARY, 0}, {MotionEvent.BUTTON_SECONDARY, 1}, {MotionEvent.BUTTON_TERTIARY, 2}};
        for (int[] m : map) {
            boolean was = (buttons & m[0]) != 0, is = (now & m[0]) != 0;
            if (was != is) HorizonInput.nativeMouseButton(m[1], is);
        }
        buttons = now;
        mouseUsed = true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (fromMouse(e)) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_MOVE:
                case MotionEvent.ACTION_HOVER_ENTER:
                    moved(e.getX(), e.getY());
                    return true;
                case MotionEvent.ACTION_SCROLL:
                    HorizonInput.nativeScroll(e.getAxisValue(MotionEvent.AXIS_VSCROLL));
                    mouseUsed = true;
                    return true;
                case MotionEvent.ACTION_BUTTON_PRESS:
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    buttonState(e.getButtonState());
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (fromMouse(e)) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getX();
                    lastY = e.getY();
                    buttonState(e.getButtonState());
                    break;
                case MotionEvent.ACTION_MOVE:
                    moved(e.getX(), e.getY());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    buttonState(0);
                    break;
                default:
                    break;
            }
            return true;      // a mouse is not a finger: the controls never see it
        }
        return super.dispatchTouchEvent(e);
    }

    // ---------------------------------------------------------------- the drawing: FPS and the cursor

    private final class Status extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path arrow = new Path();
        private int insetL, insetT;

        Status(Context c) {
            super(c);
            setWillNotDraw(false);
            setClickable(false);
            text.setTextSize(13 * dp);
            text.setFakeBoldText(true);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.5f * dp);
            line.setColor(0xFF000000);
        }

        void setInsets(int l, int t) {
            insetL = l;
            insetT = t;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return false;    // never takes a touch from the controls below
        }

        @Override
        protected void onDraw(Canvas c) {
            if (vmouse || mouseUsed) {
                long packed;
                try {
                    packed = HorizonInput.nativeCursor();
                } catch (UnsatisfiedLinkError e) {
                    return;
                }
                if (packed < 0) return;
                float cx = (int) (packed >> 32), cy = (int) (packed & 0xFFFFFFFFL);
                float s = 20 * dp;
                arrow.reset();
                arrow.moveTo(cx, cy);
                arrow.lineTo(cx, cy + s);
                arrow.lineTo(cx + s * 0.28f, cy + s * 0.76f);
                arrow.lineTo(cx + s * 0.52f, cy + s * 1.2f);
                arrow.lineTo(cx + s * 0.68f, cy + s * 1.12f);
                arrow.lineTo(cx + s * 0.44f, cy + s * 0.7f);
                arrow.lineTo(cx + s * 0.78f, cy + s * 0.7f);
                arrow.close();
                fill.setColor(0xFFFFFFFF);
                c.drawPath(arrow, fill);
                c.drawPath(arrow, line);
            }
        }
    }
}
