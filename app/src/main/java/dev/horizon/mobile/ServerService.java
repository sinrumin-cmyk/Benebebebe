package dev.horizon.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in server: Pumpkin (a Minecraft server written in Rust) for Minecraft Java 26.3, the version of the game
 * this app is built for. It is a foreground service so Android keeps it alive while the game runs in its own process.
 * The server executable is shipped as lib/arm64-v8a/libpumpkin.so (only the file name is that of a library: Android
 * extracts such files to a folder apps may run programs from).
 */
public class ServerService extends Service {
    /** The one version the server speaks. Not a setting. */
    static final String MINECRAFT_VERSION = "26.3";
    static final int PORT = 25565;
    static final String ACTION_STOP = "dev.horizon.mobile.SERVER_STOP";

    private static final int LOG_LINES = 300;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static volatile boolean running;
    private static volatile String state = "остановлен";

    private volatile Process process;
    private Thread worker;

    // ------------------------------------------------------------------ for the launcher

    static boolean available(Context c) {
        return binary(c).isFile();
    }

    static boolean running() { return running; }
    static String state() { return state; }

    static synchronized List<String> logTail(int n) {
        List<String> all = new ArrayList<>(LOG);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    static void start(Context c) {
        Intent i = new Intent(c, ServerService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) {
        c.startService(new Intent(c, ServerService.class).setAction(ACTION_STOP));
    }

    /** The phone's addresses a friend on the same Wi-Fi can use. */
    static List<String> addresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress() + ":" + PORT);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    static File dir(Context c) {
        File base = c.getExternalFilesDir(null);
        return new File(base != null ? base : c.getFilesDir(), "server");
    }

    private static File binary(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libpumpkin.so");
    }

    // ------------------------------------------------------------------ service

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        startForeground(1, notification());
        worker = new Thread(this::run, "pumpkin");
        worker.start();
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("server", "Сервер", NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, "server")
                .setContentTitle("Сервер Pumpkin, Minecraft " + MINECRAFT_VERSION)
                .setContentText("Порт " + PORT)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void run() {
        running = true;
        try {
            File bin = binary(this), dir = dir(this);
            if (!bin.isFile()) { log("Нет libpumpkin.so: сервер не вошёл в эту сборку"); return; }
            dir.mkdirs();
            if (!hasConfig(dir)) {
                // First start: let Pumpkin write its own configuration (its file names and keys changed between
                // versions), then stop it, change what a phone's server needs, and start it for real.
                state = "первый запуск: создаю настройки";
                Process first = launch(bin, dir);
                process = first;
                for (int i = 0; i < 60 && first.isAlive() && !hasConfig(dir); i++) Thread.sleep(500);
                stopProcess(first);
            }
            patchConfig(dir);
            state = "запускается";
            Process p = launch(bin, dir);
            process = p;
            pump(p);
            int code = p.waitFor();
            log("Сервер завершился, код " + code);
        } catch (Throwable e) {
            log("Ошибка сервера: " + e);
        } finally {
            running = false;
            state = "остановлен";
            stopForeground(true);
            stopSelf();
        }
    }

    private Process launch(File bin, File dir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath()).directory(dir).redirectErrorStream(true);
        pb.environment().put("RUST_BACKTRACE", "1");
        pb.environment().put("HOME", dir.getAbsolutePath());
        return pb.start();
    }

    /** Copies the server's output into the log (and server.log) until it ends. Starts a reader thread. */
    private void pump(final Process p) {
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 FileWriter w = new FileWriter(new File(dir(this), "server.log"), true)) {
                for (String line; (line = r.readLine()) != null; ) {
                    log(line);
                    w.write(line + "\n");
                    w.flush();
                    if (line.contains("Started") || line.contains("Done") || line.contains("listening")) state = "работает, порт " + PORT;
                }
            } catch (Throwable ignored) {}
        }, "pumpkin-log").start();
        state = "работает, порт " + PORT;
    }

    private void stopServer() {
        Process p = process;
        if (p != null) stopProcess(p);
    }

    /** "stop" on the console saves the world; the process is killed only if that takes too long. */
    private static void stopProcess(Process p) {
        try {
            OutputStream in = p.getOutputStream();
            in.write("stop\n".getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (Throwable ignored) {}
        try {
            for (int i = 0; i < 40 && p.isAlive(); i++) Thread.sleep(250);
        } catch (InterruptedException ignored) {}
        if (p.isAlive()) p.destroyForcibly();
    }

    // ------------------------------------------------------------------ configuration

    private static List<File> tomls(File dir) {
        List<File> out = new ArrayList<>();
        File[] top = dir.listFiles();
        if (top == null) return out;
        for (File f : top) {
            if (f.isFile() && f.getName().endsWith(".toml")) out.add(f);
            else if (f.isDirectory() && f.getName().equals("config")) {
                File[] in = f.listFiles();
                if (in != null) for (File g : in) if (g.isFile() && g.getName().endsWith(".toml")) out.add(g);
            }
        }
        return out;
    }

    private static boolean hasConfig(File dir) {
        for (File f : tomls(dir)) {
            try {
                if (new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains("online_mode")) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * A server on a phone has nobody to check accounts with: no online mode (and so no encryption or signed chat,
     * which Pumpkin requires to go with it). Only these three switches are touched, whichever file they are in.
     */
    private static void patchConfig(File dir) {
        Pattern p = Pattern.compile("(?m)^(\\s*(?:online_mode|encryption|allow_chat_reports)\\s*=\\s*)true");
        for (File f : tomls(dir)) {
            try {
                String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                Matcher m = p.matcher(text);
                if (!m.find()) continue;
                Files.write(f.toPath(), m.replaceAll("$1false").getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {}
        }
    }

    private static synchronized void log(String line) {
        if (LOG.size() >= LOG_LINES) LOG.removeFirst();
        LOG.addLast(line);
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }
}
