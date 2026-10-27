package dev.horizon.mobile;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Unpacks a prepared game folder (a zip holding horizon-resources.json) into place. */
final class ZipImport {
    interface Progress { void on(String text); }

    static void run(ContentResolver cr, Uri uri, File dest, Progress progress) throws IOException {
        String marker = "horizon-resources.json";
        String prefix = null;
        progress.on("Ищу файлы игры в архиве…");
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(cr.openInputStream(uri)))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName().replace('\\', '/');
                if (!e.isDirectory() && (n.equals(marker) || n.endsWith("/" + marker))) {
                    prefix = n.substring(0, n.length() - marker.length());
                    break;
                }
            }
        }
        if (prefix == null) throw new IOException("В архиве нет " + marker + ". Нужна подготовленная папка " + Dirs.VERSION + ".");

        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        deleteTree(tmp);
        tmp.mkdirs();
        String root = tmp.getCanonicalPath() + File.separator;
        long total = 0;
        byte[] buf = new byte[1 << 16];
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(cr.openInputStream(uri)))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName().replace('\\', '/');
                if (!n.startsWith(prefix) || n.length() == prefix.length()) continue;
                File out = new File(tmp, n.substring(prefix.length()));
                if (!out.getCanonicalPath().startsWith(root)) continue;   // zip-slip
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(out))) {
                    for (int r; (r = z.read(buf)) > 0; ) {
                        o.write(buf, 0, r);
                        total += r;
                    }
                }
                progress.on("Распаковано " + (total >> 20) + " МБ…");
            }
        }
        deleteTree(dest);
        dest.getParentFile().mkdirs();
        if (!tmp.renameTo(dest)) throw new IOException("Не удалось переместить файлы на место");
        progress.on("Готово: " + (total >> 20) + " МБ");
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
