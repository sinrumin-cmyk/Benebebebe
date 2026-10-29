package dev.horizon.mobile;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Edits horizon-extras.txt (the game's own settings file, next to options.txt): the FPS counter is the game's. */
final class ExtrasFile {
    /** Turns the game's FPS counter on or off; every other line of the file stays as it is. */
    static void setFps(Context c, boolean on) {
        try {
            File f = new File(Dirs.home(c), "horizon-extras.txt");
            List<String> lines = new ArrayList<>();
            if (f.isFile()) lines.addAll(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
            String line = "fps:" + on;
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("fps:")) {
                    lines.set(i, line);
                    found = true;
                }
            }
            if (!found) lines.add(line);
            Files.write(f.toPath(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // the counter is a convenience; the game starts without it
        }
    }
}
