package dev.horizon.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.widget.Switch;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class LauncherActivity extends Activity {
    private static final int PICK_ZIP = 1, PICK_LAYOUT = 2;
    private SharedPreferences prefs;
    private TextView status, importState, distLabel, accountLabel, layoutLabel;
    private EditText world, nick;
    private SeekBar dist;
    private LinearLayout modeRow, visRow;
    private Switch startInWorld, creativeSwitch;
    private float dp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            build();
        } catch (Throwable e) {
            saveCrash(e);
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            TextView t = new TextView(this);
            t.setText("Меню не смогло открыться:\n\n" + sw);
            t.setTextColor(Color.WHITE);
            t.setBackgroundColor(0xFF101418);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setTextIsSelectable(true);
            t.setPadding(40, 80, 40, 40);
            ScrollView sv = new ScrollView(this);
            sv.addView(t);
            setContentView(sv);
        }
    }

    /** Writes a menu-process Java failure where "Логи и ошибки" reads it. */
    private void saveCrash(Throwable e) {
        try {
            File f = Dirs.javaCrash(this);
            f.getParentFile().mkdirs();
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            try (FileWriter w = new FileWriter(f)) {
                w.write(new java.util.Date() + "\nprocess menu\nthread " + Thread.currentThread().getName() + "\n" + sw);
            }
        } catch (Throwable ignored) {}
    }

    private Md3 m;
    private TextView play, playHint, scaleLabel;
    private LinearLayout worldGroup, chipRow;
    private SeekBar scaleSeek;

    private void build() {
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        m = new Md3(this);
        Dirs.home(this); // makes Android/data/<package>/files exist

        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(m.surface);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout root = m.column();
        root.setPadding(px(16), px(16), px(16), px(28));
        scroll.addView(root);
        // edge to edge: the bars take the page's colour and the content keeps clear of them
        getWindow().setStatusBarColor(m.surface);
        getWindow().setNavigationBarColor(m.surface);
        if (!m.dark) {
            scroll.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        int n = 0;

        // ---- hero: name, state of the game files, the one big button
        LinearLayout hero = m.column();
        android.graphics.drawable.GradientDrawable heroBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{m.primaryContainer, m.container});
        heroBg.setCornerRadius(40 * dp);
        hero.setBackground(heroBg);
        hero.setPadding(px(24), px(28), px(24), px(24));
        TextView kicker = m.text("MINECRAFT JAVA 26.3  ·  RUST", Md3.LABEL, Md3.alpha(m.onPrimaryContainer, 0.75f));
        hero.addView(kicker);
        TextView title = m.text("Horizon", Md3.DISPLAY, m.onPrimaryContainer);
        title.setPadding(0, px(4), 0, px(14));
        hero.addView(title);
        status = m.text("", Md3.BODY, m.onPrimaryContainer);
        hero.addView(status);
        play = m.hero("▶   Играть", v -> play());
        hero.addView(play, m.buttonParams(20, 72));
        playHint = m.text("", Md3.BODY, Md3.alpha(m.onPrimaryContainer, 0.8f));
        playHint.setGravity(android.view.Gravity.CENTER);
        playHint.setPadding(0, px(10), 0, 0);
        hero.addView(playHint);
        root.addView(hero);
        m.enter(hero, n++);

        // ---- the game
        LinearLayout game = m.card("Игра", "⛏");
        game.addView(m.text("Дальность прорисовки", Md3.BODY, m.onVariant), m.lp(-2, -2, 14));
        distLabel = m.text("", Md3.TITLE, m.onSurface);
        game.addView(distLabel);
        dist = m.slider(22, prefs.getInt("distance", 8) - 2, new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { distLabel.setText((p + 2) + " чанков"); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) { saveSettings(); }
        });
        distLabel.setText((dist.getProgress() + 2) + " чанков");
        game.addView(dist);

        startInWorld = m.toggle(Settings.startInWorld(this), on -> {
            prefs.edit().putBoolean("start_in_world", on).commit();
            refreshPlay();
        });
        LinearLayout swRow = m.row();
        swRow.setPadding(0, px(10), 0, px(2));
        LinearLayout swTexts = m.column();
        swTexts.addView(m.text("Сразу в мир", Md3.TITLE, m.onSurface));
        TextView swSub = m.text("Пропустить главное меню Horizon и открыть мир ниже", Md3.BODY, m.onVariant);
        swSub.setPadding(0, px(2), px(12), 0);
        swTexts.addView(swSub);
        swRow.addView(swTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        swRow.addView(startInWorld);
        game.addView(swRow);

        worldGroup = m.column();
        worldGroup.addView(m.text("Название мира", Md3.BODY, m.onVariant), m.lp(-2, -2, 12));
        world = m.field(prefs.getString("world", "New World"), InputType.TYPE_CLASS_TEXT);
        world.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable e) { if (playHint != null) refreshPlay(); }
        });
        worldGroup.addView(world, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, -2, 6));
        creativeSwitch = m.toggle(prefs.getBoolean("creative", false), on -> prefs.edit().putBoolean("creative", on).commit());
        LinearLayout crRow = m.row();
        crRow.setPadding(0, px(10), 0, 0);
        LinearLayout crTexts = m.column();
        crTexts.addView(m.text("Творческий режим", Md3.TITLE, m.onSurface));
        crTexts.addView(m.text("Для нового мира", Md3.BODY, m.onVariant));
        crRow.addView(crTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        crRow.addView(creativeSwitch);
        worldGroup.addView(crRow);
        game.addView(worldGroup);
        root.addView(game, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, -2, 14));
        m.enter(game, n++);

        // ---- controls
        LinearLayout controls = m.card("Управление", "🎮");
        layoutLabel = m.text("", Md3.TITLE, m.onSurface);
        layoutLabel.setPadding(0, px(14), 0, 0);
        controls.addView(layoutLabel);
        LinearLayout layoutButtons = m.row();
        TextView choose = m.tonal("Выбрать", v -> chooseLayout());
        TextView editor = m.tonal("Редактор", v -> startActivity(new Intent(this, LayoutEditorActivity.class)));
        layoutButtons.addView(choose, new LinearLayout.LayoutParams(0, px(48), 1));
        View gap = new View(this);
        layoutButtons.addView(gap, new LinearLayout.LayoutParams(px(10), 1));
        layoutButtons.addView(editor, new LinearLayout.LayoutParams(0, px(48), 1));
        controls.addView(layoutButtons, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, px(48), 12));
        controls.addView(m.plain("Импорт раскладки Mojo (.json / .zip)", v -> pickLayout()), m.buttonParams(4, 44));
        refreshLayout();

        controls.addView(m.text("Размер раскладки", Md3.BODY, m.onVariant), m.lp(-2, -2, 16));
        scaleLabel = m.text("", Md3.TITLE, m.onSurface);
        controls.addView(scaleLabel);
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = m.row();
        chipScroll.addView(chipRow);
        controls.addView(chipScroll, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, -2, 8));
        scaleSeek = m.slider(30, (Math.min(Settings.layoutScale(this), 200) - 50) / 5, new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                if (u) setScale(50 + p * 5);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        controls.addView(scaleSeek);
        refreshScale();

        controls.addView(m.switchRow("Жесты касания", "Тап по экрану бьёт и ставит, удержание ломает. Выключено: это делают кнопки раскладки и мышь",
                Settings.touchGestures(this), on -> prefs.edit().putBoolean("touch_gestures", on).commit(), null));
        controls.addView(m.switchRow("Виртуальная мышь", "Палец двигает курсор как тачпад, тап щёлкает. Включается и кнопкой раскладки",
                Settings.virtualMouse(this), on -> prefs.edit().putBoolean("virtual_mouse", on).commit(), null));
        root.addView(controls, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, -2, 14));
        m.enter(controls, n++);

        // ---- screen
        LinearLayout screen = m.card("Экран", "📈");
        screen.addView(m.switchRow("Счётчик FPS", "Счётчик самой игры, в углу экрана. Тоже включается в игре: Options → Horizon Settings → Extras",
                Settings.showFps(this), on -> { prefs.edit().putBoolean("show_fps", on).commit(); ExtrasFile.setFps(this, on); }, null));
        screen.addView(m.switchRow("VR (Cardboard)", "Два глаза рядом, гироскоп телефона поворачивает голову. Вставь телефон в шлем после запуска игры. Тонкая настройка: файл vr.txt (см. README)",
                Settings.vr(this), on -> prefs.edit().putBoolean("vr", on).commit(), null));
        screen.addView(m.switchRow("Кнопки на экране в VR", "Выключено: экран чистый, управление только с клавиатуры, мыши или геймпада",
                Settings.vrControls(this), on -> prefs.edit().putBoolean("vr_controls", on).commit(), null));
        screen.addView(m.plain("Камеры (для трекинга рук)", v -> new android.app.AlertDialog.Builder(this)
                .setTitle("Камеры").setMessage(CameraInfo.dump(this)).setPositiveButton("Ок", null).show()), m.buttonParams(4, 44));
        root.addView(screen, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, -2, 14));
        m.enter(screen, n++);

        // ---- how the game is controlled: hands, gamepad, both. The VR menu changes the same setting.
        LinearLayout input = m.card("Ввод: руки и геймпад", "🎮");
        input.addView(m.text("Руки: задняя камера. Левая ходит (ладонь вперёд, назад, в стороны), кулак прыгает; правая: щипок бьёт, "
                + "кулак использует, взмах в сторону листает слоты. Нужен шлем с окном под камеру, при первом запуске спросит доступ к камере. "
                + "Геймпад: любой Bluetooth/USB, кнопки как в консольном Minecraft. Смешанный режим: что нажато хоть где, то и работает.",
                Md3.BODY, m.onVariant));
        modeRow = m.row();
        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(false);
        modeScroll.addView(modeRow);
        input.addView(modeScroll, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, px(40), 12));
        refreshModes();
        input.addView(m.text("Показ рук: «Руки в сцене» рисует поверх игры руки, повторяющие настоящие (по суставам, глубина по размеру ладони); "
                + "«Окошко» — скелет и зона левой руки для настройки.", Md3.BODY, m.onVariant), m.lp(ViewGroup.LayoutParams.MATCH_PARENT, -2, 12));
        visRow = m.row();
        HorizontalScrollView visScroll = new HorizontalScrollView(this);
        visScroll.setHorizontalScrollBarEnabled(false);
        visScroll.addView(visRow);
        input.addView(visScroll, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, px(40), 8));
        refreshVis();
        input.addView(m.tonal("Проверка рук и выбор камеры", v -> startActivity(new Intent(this, HandTestActivity.class))), m.buttonParams(12, 48));
        input.addView(m.tonal("Открыть VR-меню (руки / геймпад)", v -> startActivity(new Intent(this, VrMenuActivity.class))), m.buttonParams(12, 48));
        root.addView(input, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, -2, 14));
        m.enter(input, n++);

        // ---- account
        LinearLayout account = m.card("Аккаунт", "👤");
        accountLabel = m.text("", Md3.BODY, m.onVariant);
        accountLabel.setPadding(0, px(12), 0, 0);
        account.addView(accountLabel);
        LinearLayout accountButtons = m.row();
        accountButtons.addView(m.filled("Войти через Ely.by", v -> elyDialog()), new LinearLayout.LayoutParams(0, px(52), 1));
        View gap2 = new View(this);
        accountButtons.addView(gap2, new LinearLayout.LayoutParams(px(10), 1));
        accountButtons.addView(m.outlinedButton("Выйти", v -> { Ely.signOut(prefs); refreshAccount(); }), new LinearLayout.LayoutParams(px(120), px(52)));
        account.addView(accountButtons, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, px(52), 14));
        account.addView(m.text("Ник без аккаунта (офлайн)", Md3.BODY, m.onVariant), m.lp(-2, -2, 16));
        nick = m.field(prefs.getString("nick", ""), InputType.TYPE_CLASS_TEXT);
        account.addView(nick, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, -2, 6));
        root.addView(account, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, -2, 14));
        m.enter(account, n++);

        // ---- game files
        LinearLayout files = m.card("Файлы игры", "📦");
        TextView help = m.text("Файлы делаются на ПК шагом prepare из твоей копии Minecraft Java (папка " + Dirs.VERSION
                + " из game-files/horizon-client). Их запаковывают в zip и импортируют сюда.", Md3.BODY, m.onVariant);
        help.setPadding(0, px(12), 0, 0);
        files.addView(help);
        files.addView(m.tonal("Импорт файлов игры (.zip)", v -> pickZip()), m.buttonParams(14, 48));
        importState = m.text("", Md3.BODY, m.onVariant);
        importState.setPadding(0, px(8), 0, 0);
        files.addView(importState);
        root.addView(files, m.lp(ViewGroup.LayoutParams.MATCH_PARENT, -2, 14));
        m.enter(files, n++);

        root.addView(m.outlinedButton("Логи и ошибки", v -> startActivity(new Intent(this, LogActivity.class))), m.buttonParams(14, 48));

        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            root.setPadding(px(16) + in.getSystemWindowInsetLeft(), px(16) + in.getSystemWindowInsetTop(),
                    px(16) + in.getSystemWindowInsetRight(), px(28) + in.getSystemWindowInsetBottom());
            return in;
        });
        setContentView(scroll);
        refreshPlay();
    }

    private void refreshVis() {
        visRow.removeAllViews();
        int current = Settings.handVis(this);
        for (int v = 0; v <= 2; v++) {
            final int vv = v;
            TextView chip = m.chip(Settings.handVisName(v), v == current, x -> {
                prefs.edit().putInt("hand_vis", vv).commit();
                refreshVis();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40));
            lp.rightMargin = px(8);
            visRow.addView(chip, lp);
        }
    }

    private void refreshModes() {
        modeRow.removeAllViews();
        int current = Settings.inputMode(this);
        for (int mode = Settings.MODE_HANDS; mode <= Settings.MODE_PAD; mode++) {
            final int mm = mode;
            TextView chip = m.chip(Settings.inputModeName(mode), mode == current, v -> {
                prefs.edit().putInt("input_mode", mm).commit();
                refreshModes();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40));
            lp.rightMargin = px(8);
            modeRow.addView(chip, lp);
        }
    }

    private TextView scaleChip(int value) {
        final int v = value;
        return m.chip(value + "%", Settings.layoutScale(this) == value, x -> setScale(v));
    }

    private void setScale(int percent) {
        prefs.edit().putInt("layout_scale", percent).commit();
        scaleSeek.setProgress((Math.min(percent, 200) - 50) / 5);
        refreshScale();
    }

    private void refreshScale() {
        int now = Settings.layoutScale(this);
        scaleLabel.setText(now + " %");
        chipRow.removeAllViews();
        for (int v : new int[]{50, 75, 90, 100, 110, 120, 130, 150, 175, 200}) {
            TextView c = scaleChip(v);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40));
            p.rightMargin = px(8);
            chipRow.addView(c, p);
        }
    }

    /** The big button says where it goes: the main menu, or straight into the world. */
    private void refreshPlay() {
        boolean direct = startInWorld.isChecked();
        worldGroup.setVisibility(direct ? View.VISIBLE : View.GONE);
        playHint.setText(direct ? "Откроется мир «" + world.getText().toString().trim() + "»" : "Откроется главное меню Horizon");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refreshStatus();
        if (layoutLabel != null) refreshLayout();   // the editor may have changed the chosen layout
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
    }

    private void refreshStatus() {
        boolean ok = Dirs.hasResources(this);
        StringBuilder s = new StringBuilder();
        s.append(ok ? "✔  Файлы игры на месте" : "✖  Файлов игры нет: сначала импорт");
        if (crashedSinceLastLaunch()) s.append("\n⚠  Прошлый запуск завершился ошибкой: смотри «Логи и ошибки»");
        status.setText(s);
        status.setTextColor(ok ? m.onPrimaryContainer : m.onErrorContainer);
        status.setBackground(m.shape(ok ? Md3.alpha(m.primary, 0.22f) : m.errorContainer, 18));
        status.setPadding(px(14), px(10), px(14), px(10));
        refreshAccount();
    }

    private void refreshAccount() {
        String n = Ely.name(prefs);
        accountLabel.setText(n == null
                ? "Не вошли: офлайн (одиночная игра, LAN, офлайн-серверы)"
                : "Ely.by: " + n + "\nОнлайн-игра только на серверах с авторизацией Ely.by");
    }

    private void elyDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(8), px(20), 0);
        EditText user = new EditText(this); user.setHint("Ник или email Ely.by"); user.setSingleLine(true);
        EditText pass = new EditText(this); pass.setHint("Пароль"); pass.setSingleLine(true);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText code = new EditText(this); code.setHint("Код 2FA (если включён)"); code.setSingleLine(true);
        box.addView(user); box.addView(pass); box.addView(code);
        m.show(new android.app.AlertDialog.Builder(this).setTitle("Вход через Ely.by").setView(box)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Войти", (d, w) -> {
                    String u = user.getText().toString().trim(), pw = pass.getText().toString(), cd = code.getText().toString().trim();
                    new Thread(() -> {
                        String msg;
                        try { Ely.signIn(prefs, u, pw, cd); msg = "Вошли как " + Ely.name(prefs); }
                        catch (Throwable e) { msg = "Не вошли: " + e.getMessage(); }
                        final String m = msg;
                        runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); refreshAccount(); });
                    }, "ely").start();
                }));
    }

    private boolean crashedSinceLastLaunch() {
        long since = prefs.getLong("last_launch", 0);
        if (since == 0) return false;
        if (Dirs.javaCrash(this).lastModified() > since) return true;
        File[] reports = Dirs.crashes(this).listFiles();
        if (reports != null) for (File f : reports) if (f.lastModified() > since) return true;
        return false;
    }

    private void saveSettings() {
        if (dist == null) return;
        prefs.edit().putInt("distance", dist.getProgress() + 2)
                .putString("world", world.getText().toString().trim())
                .putString("nick", nick.getText().toString().trim())
                .putBoolean("creative", creativeSwitch.isChecked()).commit();
    }

    private void play() {
        saveSettings();
        Launch.play(this);
    }

    private void refreshLayout() {
        layoutLabel.setText(LayoutStore.title(LayoutStore.active(this)));
    }

    private void chooseLayout() {
        final List<String> ids = LayoutStore.list(this);
        String[] names = new String[ids.size()];
        for (int i = 0; i < names.length; i++) names[i] = LayoutStore.title(ids.get(i));
        m.show(new android.app.AlertDialog.Builder(this).setTitle("Раскладка управления")
                .setItems(names, (d, which) -> {
                    String id = ids.get(which);
                    if (!LayoutStore.SIMPLE.equals(id)) {
                        try { LayoutStore.load(this, id); }
                        catch (Throwable e) { Toast.makeText(this, "Не подходит: " + e.getMessage(), Toast.LENGTH_LONG).show(); return; }
                    }
                    LayoutStore.setActive(this, id);
                    refreshLayout();
                }));
    }

    private void pickLayout() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_LAYOUT);
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(i, PICK_ZIP);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != PICK_ZIP && req != PICK_LAYOUT) || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == PICK_LAYOUT) {
            try {
                String id = LayoutStore.importFrom(this, uri);
                LayoutStore.setActive(this, id);
                refreshLayout();
                Toast.makeText(this, "Раскладка «" + LayoutStore.title(id) + "» добавлена и выбрана", Toast.LENGTH_LONG).show();
            } catch (Throwable e) {
                Toast.makeText(this, "Не импортировалась: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        importState.setText("Начинаю…");
        new Thread(() -> {
            try {
                ZipImport.run(getContentResolver(), uri, Dirs.resources(this), t -> runOnUiThread(() -> importState.setText(t)));
            } catch (Throwable e) {
                runOnUiThread(() -> importState.setText("Ошибка импорта: " + e.getMessage()));
            }
            runOnUiThread(this::refreshStatus);
        }, "import").start();
    }

    // ---- small UI helper ----
    private int px(int v) { return Math.round(v * dp); }
}
