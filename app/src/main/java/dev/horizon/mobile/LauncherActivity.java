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

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class LauncherActivity extends Activity {
    private static final int PICK_ZIP = 1;
    private SharedPreferences prefs;
    private TextView status, importState, distLabel, accountLabel;
    private EditText world, nick;
    private SeekBar dist;
    private CheckBox creative;
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

    private void build() {
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        Dirs.home(this); // makes Android/data/<package>/files exist

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101418);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(20), px(36), px(20), px(24));
        scroll.addView(root);

        TextView title = text("Horizon", 28, Color.WHITE);
        root.addView(title);
        root.addView(text("Minecraft Java 26.3 на Rust", 13, 0xFF8899AA));

        status = text("", 14, Color.WHITE);
        status.setPadding(0, px(18), 0, px(10));
        root.addView(status);

        root.addView(button("▶  Играть", v -> play(), 0xFF2E7D32));
        root.addView(button("Импорт файлов игры (.zip)", v -> pickZip(), 0xFF2B3A4A));
        importState = text("", 12, 0xFF8899AA);
        root.addView(importState);

        root.addView(section("Аккаунт"));
        accountLabel = text("", 14, Color.WHITE);
        root.addView(accountLabel);
        root.addView(button("Войти через Ely.by", v -> elyDialog(), 0xFF2B3A4A));
        root.addView(button("Выйти из аккаунта", v -> { Ely.signOut(prefs); refreshAccount(); }, 0xFF3A2B2B));
        TextView nickHint = text("Ник без аккаунта (офлайн)", 12, 0xFF8899AA);
        nickHint.setPadding(0, px(10), 0, 0);
        root.addView(nickHint);
        nick = new EditText(this);
        nick.setSingleLine(true);
        nick.setTextColor(Color.WHITE);
        nick.setText(prefs.getString("nick", ""));
        root.addView(nick);

        root.addView(section("Настройки"));
        distLabel = text("", 14, Color.WHITE);
        root.addView(distLabel);
        dist = new SeekBar(this);
        dist.setMax(22);                                  // 2..24 chunks
        dist.setProgress(prefs.getInt("distance", 8) - 2);
        dist.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { distLabel.setText("Дальность прорисовки: " + (p + 2) + " чанков"); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        distLabel.setText("Дальность прорисовки: " + (dist.getProgress() + 2) + " чанков");
        root.addView(dist);

        root.addView(text("Название мира", 14, Color.WHITE));
        world = new EditText(this);
        world.setSingleLine(true);
        world.setInputType(InputType.TYPE_CLASS_TEXT);
        world.setTextColor(Color.WHITE);
        world.setText(prefs.getString("world", "New World"));
        root.addView(world);

        creative = new CheckBox(this);
        creative.setText("Творческий режим (для нового мира)");
        creative.setTextColor(Color.WHITE);
        creative.setChecked(prefs.getBoolean("creative", false));
        root.addView(creative);

        root.addView(section("Диагностика"));
        root.addView(button("Логи и ошибки", v -> startActivity(new Intent(this, LogActivity.class)), 0xFF2B3A4A));

        TextView help = text("Игровые файлы делаются на ПК шагом prepare из твоей копии Minecraft Java "
                + "(папка " + Dirs.VERSION + " из game-files/horizon-client), её запаковывают в zip и импортируют сюда.", 12, 0xFF8899AA);
        help.setPadding(0, px(16), 0, 0);
        root.addView(help);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
    }

    private void refreshStatus() {
        boolean ok = Dirs.hasResources(this);
        StringBuilder s = new StringBuilder();
        s.append(ok ? "✔ Файлы игры на месте" : "✖ Файлов игры нет: сначала импорт").append('\n');
        s.append(Dirs.home(this).getAbsolutePath());
        if (crashedSinceLastLaunch()) s.append("\n⚠ Прошлый запуск завершился ошибкой: смотри «Логи и ошибки»");
        status.setText(s);
        status.setTextColor(ok ? Color.WHITE : 0xFFFFB74D);
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
        new android.app.AlertDialog.Builder(this).setTitle("Вход через Ely.by").setView(box)
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
                }).show();
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
                .putBoolean("creative", creative.isChecked()).apply();
    }

    private void play() {
        if (!Dirs.hasResources(this)) {
            Toast.makeText(this, "Сначала импортируй файлы игры", Toast.LENGTH_LONG).show();
            return;
        }
        saveSettings();
        final List<String> a = new ArrayList<>();
        a.add("--resources"); a.add(Dirs.resources(this).getAbsolutePath());
        a.add("--render-distance"); a.add(String.valueOf(dist.getProgress() + 2));
        String w = world.getText().toString().trim();
        if (!w.isEmpty()) { a.add("--world"); a.add(w); }
        if (creative.isChecked()) a.add("--creative");
        final String offlineNick = nick.getText().toString().trim();

        new Thread(() -> {
            File home = Dirs.home(this);
            File env = new File(home, "game-env.txt"), session = new File(home, "session.json");
            env.delete();
            session.delete();
            String warning = null;
            boolean signed = false;
            if (Ely.signedIn(prefs)) {
                try {
                    Ely.refresh(prefs);
                    Ely.writeSession(prefs, session);          // the game reads it within two minutes, then deletes it
                    a.add("--session-file"); a.add(session.getAbsolutePath());
                    try (FileWriter f = new FileWriter(env)) { f.write("HORIZON_JOIN_URL=" + Ely.JOIN_URL); }
                    signed = true;
                } catch (Throwable e) {
                    warning = "Ely.by: " + e.getMessage() + ". Играю офлайн, войди заново.";
                }
            }
            if (!signed && !offlineNick.isEmpty()) { a.add("--name"); a.add(offlineNick); }
            try (FileWriter f = new FileWriter(new File(home, "game-args.txt"))) {
                f.write(String.join("\n", a));
            } catch (Throwable e) {
                final String m = "Не записал аргументы: " + e;
                runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
                return;
            }
            final String warn = warning;
            runOnUiThread(() -> {
                if (warn != null) Toast.makeText(this, warn, Toast.LENGTH_LONG).show();
                prefs.edit().putLong("last_launch", System.currentTimeMillis()).apply();
                startActivity(new Intent(this, HorizonActivity.class));
            });
        }, "play").start();
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
        if (req != PICK_ZIP || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
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

    // ---- small UI helpers ----
    private int px(int v) { return Math.round(v * dp); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 16, 0xFF9CCC65);
        t.setPadding(0, px(22), 0, px(6));
        return t;
    }

    private Button button(String label, View.OnClickListener l, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(color);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52));
        p.topMargin = px(8);
        b.setLayoutParams(p);
        return b;
    }
}
