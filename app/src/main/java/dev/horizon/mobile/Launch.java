package dev.horizon.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/** Starts the game from the saved launcher settings. Used by the launcher's Play and by the VR menu. */
final class Launch {
    private Launch() {}

    static void play(final Activity act) {
        final SharedPreferences prefs = Settings.prefs(act);
        if (!Dirs.hasResources(act)) {
            Toast.makeText(act, "Сначала импортируй файлы игры", Toast.LENGTH_LONG).show();
            return;
        }
        final boolean direct = Settings.startInWorld(act);
        final boolean gestures = Settings.touchGestures(act);
        final List<String> a = new ArrayList<>();
        a.add("--resources"); a.add(Dirs.resources(act).getAbsolutePath());
        a.add("--render-distance"); a.add(String.valueOf(prefs.getInt("distance", 8)));
        // No --world: the game opens Horizon's own main menu (worlds, servers, options).
        String w = prefs.getString("world", "").trim();
        if (direct && !w.isEmpty()) {
            a.add("--world"); a.add(w);
            if (prefs.getBoolean("creative", false)) a.add("--creative");
        }
        final String offlineNick = prefs.getString("nick", "").trim();

        new Thread(() -> {
            File home = Dirs.home(act);
            File env = new File(home, "game-env.txt"), session = new File(home, "session.json");
            env.delete();
            session.delete();
            ExtrasFile.setFps(act, Settings.showFps(act));
            final StringBuilder envText = new StringBuilder();
            if (gestures) envText.append("HORIZON_TOUCH_GESTURES=1\n");
            envText.append("HORIZON_GPU_CULL=shader\n");
            if (Settings.vr(act)) {
                envText.append("HORIZON_VR=1\n");
                // vr.txt next to game-env.txt: HORIZON_VR_IPD=0.064, HORIZON_VR_FOV=90, HORIZON_VR_K1=0.441, HORIZON_VR_K2=0.156
                File tune = new File(home, "vr.txt");
                if (tune.isFile()) {
                    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(tune))) {
                        for (String line; (line = r.readLine()) != null; ) {
                            line = line.trim();
                            if (line.startsWith("HORIZON_VR_") && line.indexOf('=') > 0) envText.append(line).append('\n');
                        }
                    } catch (Throwable ignored) {}
                }
            }
            String warning = null;
            boolean signed = false;
            if (Ely.signedIn(prefs)) {
                try {
                    Ely.refresh(prefs);
                    Ely.writeSession(prefs, session);          // the game reads it within two minutes, then deletes it
                    a.add("--session-file"); a.add(session.getAbsolutePath());
                    envText.append("HORIZON_JOIN_URL=").append(Ely.JOIN_URL).append('\n');
                    signed = true;
                } catch (Throwable e) {
                    warning = "Ely.by: " + e.getMessage() + ". Играю офлайн, войди заново.";
                }
            }
            if (!signed && !offlineNick.isEmpty()) { a.add("--name"); a.add(offlineNick); }
            try {
                if (envText.length() > 0) try (FileWriter f = new FileWriter(env)) { f.write(envText.toString()); }
                try (FileWriter f = new FileWriter(new File(home, "game-args.txt"))) {
                    f.write(String.join("\n", a));
                }
            } catch (Throwable e) {
                final String msg = "Не записал аргументы: " + e;
                act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_LONG).show());
                return;
            }
            final String warn = warning;
            act.runOnUiThread(() -> {
                if (warn != null) Toast.makeText(act, warn, Toast.LENGTH_LONG).show();
                prefs.edit().putLong("last_launch", System.currentTimeMillis()).commit();
                act.startActivity(new Intent(act, HorizonActivity.class));
            });
        }, "play").start();
    }
}
