package dev.horizon.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;

/** Logs without a PC: this app's logcat, the game's latest.log, crash reports and Java crashes. */
public class LogActivity extends Activity {
    private static final int LOGCAT = 0, GAME = 1, CRASH = 2;
    private int source = LOGCAT;
    private TextView view;
    private ScrollView scroll;
    private String shown = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);
        root.setPadding(0, (int) (28 * dp), 0, 0);

        HorizontalScrollView bar = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        bar.addView(row);
        row.addView(btn("Logcat", v -> load(LOGCAT)));
        row.addView(btn("Игра", v -> load(GAME)));
        row.addView(btn("Краши", v -> load(CRASH)));
        row.addView(btn("Копировать", v -> copy()));
        row.addView(btn("Отправить", v -> share()));
        row.addView(btn("Очистить", v -> clear()));
        root.addView(bar);

        view = new TextView(this);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        view.setTextColor(0xFFCFD8DC);
        view.setTextIsSelectable(true);
        view.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
        scroll = new ScrollView(this);
        scroll.addView(view);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        load(LOGCAT);
    }

    private Button btn(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void load(int what) {
        source = what;
        view.setText("Читаю…");
        new Thread(() -> {
            String text;
            try {
                text = what == LOGCAT ? logcat() : what == GAME ? tail(new File(Dirs.logs(this), "latest.log")) : crashes();
            } catch (Throwable e) {
                text = "Не удалось прочитать: " + e;
            }
            if (text.trim().isEmpty()) text = "(пусто)";
            final String t = text;
            runOnUiThread(() -> {
                shown = t;
                view.setText(t);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
        }, "logs").start();
    }

    /** Only this app's own lines are visible to it, including those of its earlier, crashed processes. */
    private String logcat() throws IOException {
        Process p = new ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start();
        ArrayDeque<String> last = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            for (String line; (line = r.readLine()) != null; ) {
                last.add(line);
                if (last.size() > 600) last.removeFirst();
            }
        }
        return String.join("\n", last);
    }

    private String tail(File f) throws IOException {
        if (!f.isFile()) return "Файла нет: " + f.getAbsolutePath();
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            int n = (int) Math.min(r.length(), 120 * 1024);
            byte[] buf = new byte[n];
            r.seek(r.length() - n);
            r.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private String crashes() throws IOException {
        StringBuilder s = new StringBuilder();
        File j = Dirs.javaCrash(this);
        if (j.isFile()) s.append("=== java-crash.txt ===\n").append(tail(j)).append("\n\n");
        File[] reports = Dirs.crashes(this).listFiles();
        if (reports != null && reports.length > 0) {
            Arrays.sort(reports, Comparator.comparingLong(File::lastModified).reversed());
            s.append("=== ").append(reports[0].getName()).append(" ===\n").append(tail(reports[0]));
        }
        return s.length() == 0 ? "Краш-репортов нет." : s.toString();
    }

    private void copy() {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("log", shown));
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, shown.length() > 150_000 ? shown.substring(shown.length() - 150_000) : shown);
        startActivity(Intent.createChooser(i, "Отправить лог"));
    }

    private void clear() {
        try { new ProcessBuilder("logcat", "-c").start().waitFor(); } catch (Exception ignored) {}
        Dirs.javaCrash(this).delete();
        load(source);
    }
}
