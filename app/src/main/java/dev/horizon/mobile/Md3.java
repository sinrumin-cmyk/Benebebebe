package dev.horizon.mobile;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * Material 3 Expressive in plain views: tonal colour roles (light and dark), big rounded containers, pill
 * buttons that change shape under the finger, bold type, springy entrances. No Material library needed.
 */
final class Md3 {
    final Context ctx;
    final float dp;
    final boolean dark;
    // colour roles
    final int surface, container, containerHigh, containerHighest;
    final int onSurface, onVariant, outline, outlineVariant;
    final int primary, onPrimary, primaryContainer, onPrimaryContainer;
    final int secondaryContainer, onSecondaryContainer, tertiaryContainer, onTertiaryContainer;
    final int error, errorContainer, onErrorContainer;

    static final int DISPLAY = 0, HEADLINE = 1, TITLE = 2, BODY = 3, LABEL = 4;

    Md3(Context c) {
        ctx = c;
        dp = c.getResources().getDisplayMetrics().density;
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO;
        if (dark) {
            surface = 0xFF10140F; container = 0xFF1C211B; containerHigh = 0xFF272B25; containerHighest = 0xFF32362F;
            onSurface = 0xFFE0E4DA; onVariant = 0xFFC2C9BC; outline = 0xFF8C9388; outlineVariant = 0xFF424940;
            primary = 0xFF8BD88B; onPrimary = 0xFF00390F; primaryContainer = 0xFF1E5127; onPrimaryContainer = 0xFFA7F5A6;
            secondaryContainer = 0xFF384B38; onSecondaryContainer = 0xFFD2E8CD;
            tertiaryContainer = 0xFF1F4D5A; onTertiaryContainer = 0xFFBCEAF8;
            error = 0xFFFFB4AB; errorContainer = 0xFF93000A; onErrorContainer = 0xFFFFDAD6;
        } else {
            surface = 0xFFF7FBF1; container = 0xFFEBEFE5; containerHigh = 0xFFE5E9DF; containerHighest = 0xFFDFE4DA;
            onSurface = 0xFF191D17; onVariant = 0xFF424940; outline = 0xFF72796F; outlineVariant = 0xFFC2C9BC;
            primary = 0xFF2A6C2D; onPrimary = 0xFFFFFFFF; primaryContainer = 0xFFABF3A8; onPrimaryContainer = 0xFF002105;
            secondaryContainer = 0xFFD8E8D2; onSecondaryContainer = 0xFF0D1F0F;
            tertiaryContainer = 0xFFB9EAF8; onTertiaryContainer = 0xFF001F27;
            error = 0xFFBA1A1A; errorContainer = 0xFFFFDAD6; onErrorContainer = 0xFF410002;
        }
    }

    int px(float v) {
        return Math.round(v * dp);
    }

    // ---------------------------------------------------------------- shapes

    GradientDrawable shape(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * dp);
        return g;
    }

    GradientDrawable outlined(int fill, int stroke, float radiusDp, float strokeDp) {
        GradientDrawable g = shape(fill, radiusDp);
        g.setStroke(Math.max(1, px(strokeDp)), stroke);
        return g;
    }

    /** A drawable with the touch ripple on top. */
    android.graphics.drawable.Drawable ripple(android.graphics.drawable.Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor & 0x00FFFFFF | 0x33000000), content, null);
    }

    // ---------------------------------------------------------------- type

    TextView text(String s, int role, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(color);
        style(t, role);
        return t;
    }

    void style(TextView t, int role) {
        switch (role) {
            case DISPLAY:
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46);
                t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                t.setLetterSpacing(-0.02f);
                break;
            case HEADLINE:
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                break;
            case TITLE:
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                t.setLetterSpacing(0.01f);
                break;
            case LABEL:
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                t.setLetterSpacing(0.06f);
                break;
            default:
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                break;
        }
    }

    // ---------------------------------------------------------------- containers

    LinearLayout column() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout.LayoutParams lp(int w, int h, float top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = px(top);
        return p;
    }

    /** A rounded tonal surface with a small heading, for one group of settings. */
    LinearLayout card(String heading, String icon) {
        LinearLayout c = column();
        c.setBackground(shape(container, 28));
        c.setPadding(px(20), px(18), px(20), px(20));
        if (heading != null) {
            LinearLayout h = row();
            if (icon != null) {
                TextView i = text(icon, TITLE, primary);
                i.setGravity(Gravity.CENTER);
                i.setBackground(shape(secondaryContainer, 14));
                h.addView(i, new LinearLayout.LayoutParams(px(32), px(32)));
            }
            TextView t = text(heading, TITLE, onSurface);
            t.setPadding(px(icon != null ? 12 : 0), 0, 0, 0);
            h.addView(t);
            c.addView(h);
        }
        return c;
    }

    /** Slides a view up and fades it in; `index` staggers a list of them. */
    void enter(View v, int index) {
        v.setAlpha(0f);
        v.setTranslationY(28 * dp);
        v.animate().alpha(1f).translationY(0f).setStartDelay(60L * index).setDuration(420)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    // ---------------------------------------------------------------- buttons

    private static final int FILLED = 0, TONAL = 1, OUTLINED = 2, PLAIN = 3;

    private TextView button(String label, View.OnClickListener click, int kind, float heightDp) {
        final TextView b = text(label, TITLE, kind == FILLED ? onPrimary : kind == TONAL ? onSecondaryContainer : primary);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        final float round = heightDp / 2f;
        final GradientDrawable bg;
        switch (kind) {
            case FILLED: bg = shape(primary, round); break;
            case TONAL: bg = shape(secondaryContainer, round); break;
            case OUTLINED: bg = outlined(0, outline, round, 1); break;
            default: bg = shape(0, round); break;
        }
        b.setBackground(ripple(bg, kind == FILLED ? onPrimary : primary));
        b.setPadding(px(22), 0, px(22), 0);
        b.setOnClickListener(click);
        // expressive: the shape squares up under the finger and springs back
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: morph(v, bg, round, Math.min(round, 12), 0.97f); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: morph(v, bg, Math.min(round, 12), round, 1f); break;
                default: break;
            }
            return false;
        });
        return b;
    }

    private void morph(View v, final GradientDrawable bg, float fromDp, float toDp, float scale) {
        ValueAnimator a = ValueAnimator.ofFloat(fromDp, toDp);
        a.setDuration(toDp < fromDp ? 120 : 260);
        a.setInterpolator(toDp < fromDp ? new DecelerateInterpolator() : new OvershootInterpolator(2.2f));
        a.addUpdateListener(x -> bg.setCornerRadius((float) x.getAnimatedValue() * dp));
        a.start();
        v.animate().scaleX(scale).scaleY(scale).setDuration(toDp < fromDp ? 100 : 240)
                .setInterpolator(new OvershootInterpolator(2f)).start();
    }

    TextView filled(String label, View.OnClickListener click) {
        return button(label, click, FILLED, 52);
    }

    TextView tonal(String label, View.OnClickListener click) {
        return button(label, click, TONAL, 48);
    }

    TextView outlinedButton(String label, View.OnClickListener click) {
        return button(label, click, OUTLINED, 48);
    }

    TextView plain(String label, View.OnClickListener click) {
        return button(label, click, PLAIN, 44);
    }

    /** The big one: a pill that is the first thing on the screen. */
    TextView hero(String label, View.OnClickListener click) {
        TextView b = button(label, click, FILLED, 72);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setLetterSpacing(0.02f);
        return b;
    }

    LinearLayout.LayoutParams buttonParams(float top, float heightDp) {
        return lp(ViewGroup.LayoutParams.MATCH_PARENT, px(heightDp), top);
    }

    // ---------------------------------------------------------------- inputs

    EditText field(String text, int inputType) {
        final EditText e = new EditText(ctx);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setText(text);
        e.setTextColor(onSurface);
        e.setHintTextColor(onVariant);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setPadding(px(16), px(14), px(16), px(14));
        e.setBackground(outlined(0, outline, 16, 1));
        e.setOnFocusChangeListener((v, focus) -> e.setBackground(focus ? outlined(0, primary, 16, 2) : outlined(0, outline, 16, 1)));
        return e;
    }

    Switch toggle(boolean checked, Consumer<Boolean> change) {
        Switch s = new Switch(ctx);
        s.setChecked(checked);
        s.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{onPrimary, outline}));
        s.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{primary, containerHighest}));
        s.setOnCheckedChangeListener((b, on) -> change.accept(on));
        return s;
    }

    /** Title and a line of explanation on the left, a switch on the right. Returns the row; `out[0]` is the switch. */
    LinearLayout switchRow(String title, String subtitle, boolean checked, Consumer<Boolean> change, Switch[] out) {
        LinearLayout r = row();
        r.setPadding(0, px(10), 0, px(2));
        LinearLayout texts = column();
        texts.addView(text(title, TITLE, onSurface));
        if (subtitle != null) {
            TextView s = text(subtitle, BODY, onVariant);
            s.setPadding(0, px(2), px(12), 0);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = toggle(checked, change);
        if (out != null) out[0] = sw;
        r.addView(sw);
        return r;
    }

    SeekBar slider(int max, int value, SeekBar.OnSeekBarChangeListener l) {
        SeekBar s = new SeekBar(ctx);
        s.setMax(max);
        s.setProgress(value);
        s.setThumbTintList(ColorStateList.valueOf(primary));
        s.setProgressTintList(ColorStateList.valueOf(primary));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(containerHighest));
        s.setOnSeekBarChangeListener(l);
        return s;
    }

    /** A filter chip: filled and ticked when selected, outlined when not. */
    TextView chip(String label, boolean selected, View.OnClickListener click) {
        TextView c = text(selected ? "✓  " + label : label, LABEL, selected ? onSecondaryContainer : onVariant);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        c.setGravity(Gravity.CENTER);
        c.setPadding(px(16), 0, px(16), 0);
        c.setClickable(true);
        c.setBackground(ripple(selected ? shape(secondaryContainer, 20) : outlined(0, outline, 20, 1), primary));
        c.setOnClickListener(click);
        return c;
    }

    /** A small tonal pill that says one thing (the game files are there / are not). */
    TextView statusChip(String label, boolean good) {
        TextView c = text(label, LABEL, good ? onPrimaryContainer : onErrorContainer);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        c.setPadding(px(14), px(8), px(14), px(8));
        c.setBackground(shape(good ? tertiaryContainer : errorContainer, 16));
        if (good) c.setTextColor(onTertiaryContainer);
        return c;
    }

    // ---------------------------------------------------------------- dialogs

    /** Shows a dialog on a rounded tonal surface with accent-coloured buttons. */
    AlertDialog show(AlertDialog.Builder b) {
        AlertDialog d = b.create();
        d.show();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(shape(containerHigh, 28));
        int[] ids = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
        for (int id : ids) {
            android.widget.Button btn = d.getButton(id);
            if (btn != null) btn.setTextColor(primary);
        }
        TextView title = d.findViewById(ctx.getResources().getIdentifier("alertTitle", "id", "android"));
        if (title != null) {
            title.setTextColor(onSurface);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) msg.setTextColor(onVariant);
        return d;
    }

    static int alpha(int color, float a) {
        return Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }
}
