package dev.horizon.mobile;

import android.content.Context;
import java.io.File;

/** Same folder the Rust side uses as HORIZON_HOME (getExternalFilesDir, else the internal one). */
final class Dirs {
    static final String VERSION = "26.3";

    static File home(Context c) {
        File f = c.getExternalFilesDir(null);
        if (f == null) f = c.getFilesDir();
        f.mkdirs();
        return f;
    }
    static File resources(Context c) { return new File(home(c), ".local/horizon-client/" + VERSION); }
    static boolean hasResources(Context c) { return new File(resources(c), "horizon-resources.json").isFile(); }
    static File logs(Context c) { return new File(home(c), "logs"); }
    static File crashes(Context c) { return new File(home(c), "crash-reports"); }
    static File javaCrash(Context c) { return new File(logs(c), "java-crash.txt"); }
}
