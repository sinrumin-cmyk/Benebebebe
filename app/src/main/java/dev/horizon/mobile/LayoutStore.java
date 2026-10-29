package dev.horizon.mobile;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Control layouts: the bundled Mojo default, the old simple Horizon controls, and layouts the user
 * imported (plain JSON, or the zip a Mojo export makes). The menu and the game process share the choice
 * through a file, because they are two processes.
 */
final class LayoutStore {
    static final String MOJO = "@mojo", SIMPLE = "@horizon";
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    static File dir(Context c) {
        File d = new File(Dirs.home(c), "controls");
        d.mkdirs();
        return d;
    }

    static String active(Context c) {
        try (BufferedReader r = new BufferedReader(new FileReader(new File(dir(c), "active.txt")))) {
            String s = r.readLine();
            if (s != null && !s.trim().isEmpty()) return s.trim();
        } catch (IOException ignored) {}
        return MOJO;
    }

    static void setActive(Context c, String id) {
        try (FileWriter w = new FileWriter(new File(dir(c), "active.txt"))) {
            w.write(id);
        } catch (IOException ignored) {}
    }

    static List<String> list(Context c) {
        List<String> l = new ArrayList<>();
        l.add(MOJO);
        l.add(SIMPLE);
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) {
            Arrays.sort(fs);
            for (File f : fs) l.add(f.getName());
        }
        return l;
    }

    static String title(String id) {
        if (MOJO.equals(id)) return "Mojo (стандартная)";
        if (SIMPLE.equals(id)) return "Horizon (простая)";
        return id.endsWith(".json") ? id.substring(0, id.length() - 5) : id;
    }

    static LayoutData load(Context c, String id) throws IOException {
        String json;
        if (MOJO.equals(id)) {
            try (InputStream in = c.getAssets().open("controls/mojo_default.json")) {
                json = new String(readAll(in), StandardCharsets.UTF_8);
            }
        } else {
            json = new String(Files.readAllBytes(file(c, id).toPath()), StandardCharsets.UTF_8);
        }
        return LayoutData.parse(json);
    }

    /** The controls for the game: the chosen layout, or the simple built-in ones if it will not load. */
    static View createOverlay(Context c) {
        String id = active(c);
        View controls = null;
        if (!SIMPLE.equals(id)) {
            try {
                controls = new ControlsView(c, load(c, id));
            } catch (Throwable e) {
                Toast.makeText(c, "Раскладка «" + title(id) + "» не загрузилась: " + e.getMessage() + ". Включены простые кнопки.", Toast.LENGTH_LONG).show();
            }
        }
        if (controls == null) controls = new ControlOverlay(c);
        return new GameOverlay(c, controls);
    }

    /** Copies a picked .json / .zip into the layouts folder as plain JSON; returns its id. */
    static String importFrom(Context c, Uri uri) throws IOException {
        ContentResolver cr = c.getContentResolver();
        byte[] data;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Не открывается файл");
            data = readAll(in);
        }
        String json;
        if (data.length > 4 && data[0] == 'P' && data[1] == 'K') {
            json = null;
            try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
                for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                    if (!e.isDirectory() && e.getName().equals("layout.json")) {
                        json = new String(readAll(z), StandardCharsets.UTF_8);
                        break;
                    }
                }
            }
            if (json == null) throw new IOException("В архиве нет layout.json");
        } else {
            json = new String(data, StandardCharsets.UTF_8);
        }
        LayoutData.parse(json);   // refuses a file that is not a layout

        String name = baseName(cr, uri);
        File out = new File(dir(c), name + ".json");
        for (int n = 2; out.exists() && n < 100; n++) out = new File(dir(c), name + " (" + n + ").json");
        Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return out.getName();
    }

    private static String baseName(ContentResolver cr, Uri uri) {
        String n = null;
        try (Cursor q = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) n = q.getString(0);
        } catch (Throwable ignored) {}
        if (n == null) n = "layout";
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        StringBuilder b = new StringBuilder();
        for (char ch : n.toCharArray()) b.append(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == ' ' ? ch : '_');
        String s = b.toString().trim();
        return s.isEmpty() ? "layout" : s;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1; ) {
            o.write(buf, 0, n);
            if (o.size() > MAX_BYTES) throw new IOException("Файл слишком большой");
        }
        return o.toByteArray();
    }

    /** File id for a name the user typed: unsafe characters become "_". */
    static String idFor(String name) {
        StringBuilder b = new StringBuilder();
        for (char ch : (name == null ? "" : name).toCharArray()) b.append(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == ' ' ? ch : '_');
        String s = b.toString().trim();
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return (s.isEmpty() ? "layout" : s) + ".json";
    }

    static boolean exists(Context c, String id) {
        try {
            return !MOJO.equals(id) && !SIMPLE.equals(id) && file(c, id).exists();
        } catch (IOException e) {
            return false;
        }
    }

    private static File file(Context c, String id) throws IOException {
        File f = new File(dir(c), id);
        if (!f.getCanonicalPath().startsWith(dir(c).getCanonicalPath() + File.separator)) throw new IOException("Плохое имя раскладки");
        return f;
    }

    /** Writes the layout as plain Mojo JSON; the built-in layouts cannot be overwritten. */
    static void save(Context c, String id, LayoutData l) throws IOException {
        if (MOJO.equals(id) || SIMPLE.equals(id)) throw new IOException("Встроенную раскладку нельзя перезаписать");
        byte[] data = l.toJson().getBytes(StandardCharsets.UTF_8);
        File f = file(c, id);
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), data);
        if (!tmp.renameTo(f)) {   // renameTo cannot replace on some filesystems
            Files.write(f.toPath(), data);
            tmp.delete();
        }
    }

    static void delete(Context c, String id) throws IOException {
        if (MOJO.equals(id) || SIMPLE.equals(id)) throw new IOException("Встроенную раскладку нельзя удалить");
        File f = file(c, id);
        if (f.exists() && !f.delete()) throw new IOException("Не удалось удалить файл");
        if (id.equals(active(c))) setActive(c, MOJO);
    }
}
