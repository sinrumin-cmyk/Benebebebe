package dev.horizon.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A MojoLauncher control layout (JSON, versions 3 to 9) in memory, readable and writable.
 * The file format and field names come from MojoLauncher (LGPL-3.0, https://github.com/MojoLauncher/MojoLauncher).
 * Sizes are in dp, positions are expressions such as "${margin} * 3 + ${width} * 2" (see LayoutExpr).
 * Every object keeps the JSON it was read from, so fields this app does not know are written back untouched.
 */
final class LayoutData {
    static final class Btn {
        JSONObject raw;
        String name = "";
        int[] keys = new int[4];
        String dx = "0", dy = "0";
        float w = 50, h = 50;
        boolean toggle, swipe, passThru, forwardLock, absolute;
        boolean displayInGame = true, displayInMenu = true;
        float opacity = 1, strokeW = 0, radius = 0;
        int bg = 0x4D000000, stroke = 0xFFFFFFFF;

        JSONObject toJson(boolean joystick) throws JSONException {
            JSONObject o = raw != null ? new JSONObject(raw.toString()) : new JSONObject();
            o.put("name", name);
            JSONArray k = new JSONArray();
            for (int i = 0; i < 4; i++) k.put(keys[i]);
            o.put("keycodes", k);
            o.put("dynamicX", dx);
            o.put("dynamicY", dy);
            o.put("width", (double) w);
            o.put("height", (double) h);
            o.put("isToggle", toggle);
            o.put("isSwipeable", swipe);
            o.put("passThruEnabled", passThru);
            o.put("displayInGame", displayInGame);
            o.put("displayInMenu", displayInMenu);
            o.put("opacity", (double) opacity);
            o.put("strokeWidth", (double) strokeW);
            o.put("cornerRadius", (double) radius);
            o.put("bgColor", bg);
            o.put("strokeColor", stroke);
            o.remove("bitmapTag");   // custom background images are not carried over (see README)
            if (joystick) {
                o.put("forwardLock", forwardLock);
                o.put("absolute", absolute);
            }
            return o;
        }

        Btn copy(boolean joystick) {
            try {
                return btn(toJson(joystick));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static final class Drawer {
        JSONObject raw;
        Btn props = new Btn();
        final List<Btn> subs = new ArrayList<>();
        String orientation = "LEFT";

        JSONObject toJson() throws JSONException {
            JSONObject o = raw != null ? new JSONObject(raw.toString()) : new JSONObject();
            o.put("properties", props.toJson(false));
            o.put("orientation", orientation == null ? "LEFT" : orientation);
            JSONArray s = new JSONArray();
            for (Btn b : subs) s.put(b.toJson(false));
            o.put("buttonProperties", s);
            return o;
        }

        Drawer copy() {
            try {
                return drawer(toJson());
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    JSONObject root;
    int version = 9;
    final List<Btn> buttons = new ArrayList<>();
    final List<Drawer> drawers = new ArrayList<>();
    final List<Btn> joysticks = new ArrayList<>();

    /** An empty layout in the current (Android key code) format. */
    static LayoutData blank() {
        return new LayoutData();
    }

    boolean glfwKeys() {
        return version < 9;
    }

    static LayoutData parse(String json) throws IOException {
        if (json != null && json.startsWith("\uFEFF")) json = json.substring(1);
        try {
            JSONObject o = new JSONObject(json);
            if (!o.has("version")) throw new IOException("Раскладка слишком старая (версия 1), её надо пересохранить в Mojo");
            int v = o.getInt("version");
            if (v < 3) throw new IOException("Раскладка слишком старая (версия " + v + "), её надо пересохранить в Mojo");
            if (v > 9) throw new IOException("Раскладка новее, чем поддерживается (версия " + v + ", максимум 9)");
            LayoutData l = new LayoutData();
            l.root = o;
            l.version = v;
            JSONArray a = o.optJSONArray("mControlDataList");
            if (a != null) for (int i = 0; i < a.length(); i++) l.buttons.add(btn(a.getJSONObject(i)));
            a = o.optJSONArray("mDrawerDataList");
            if (a != null) for (int i = 0; i < a.length(); i++) l.drawers.add(drawer(a.getJSONObject(i)));
            a = o.optJSONArray("mJoystickDataList");
            if (a != null) for (int i = 0; i < a.length(); i++) l.joysticks.add(btn(a.getJSONObject(i)));
            return l;
        } catch (JSONException e) {
            throw new IOException("Файл раскладки повреждён: " + e.getMessage());
        }
    }

    /** The layout as a Mojo-compatible JSON file. */
    String toJson() throws IOException {
        try {
            JSONObject o = root != null ? new JSONObject(root.toString()) : new JSONObject();
            o.put("version", version);
            if (!o.has("scaledAt")) o.put("scaledAt", 100.0);
            JSONArray a = new JSONArray();
            for (Btn b : buttons) a.put(b.toJson(false));
            o.put("mControlDataList", a);
            a = new JSONArray();
            for (Drawer d : drawers) a.put(d.toJson());
            o.put("mDrawerDataList", a);
            a = new JSONArray();
            for (Btn j : joysticks) a.put(j.toJson(true));
            o.put("mJoystickDataList", a);
            return o.toString(2);
        } catch (JSONException e) {
            throw new IOException("Не удалось собрать файл раскладки: " + e.getMessage());
        }
    }

    private static Drawer drawer(JSONObject d) throws JSONException {
        Drawer dr = new Drawer();
        dr.raw = d;
        dr.props = btn(d.getJSONObject("properties"));
        dr.orientation = d.optString("orientation", "LEFT");
        JSONArray s = d.optJSONArray("buttonProperties");
        if (s != null) for (int k = 0; k < s.length(); k++) dr.subs.add(btn(s.getJSONObject(k)));
        return dr;
    }

    private static Btn btn(JSONObject o) {
        Btn b = new Btn();
        b.raw = o;
        b.name = o.optString("name", "");
        JSONArray k = o.optJSONArray("keycodes");
        if (k != null) for (int i = 0; i < 4 && i < k.length(); i++) b.keys[i] = k.optInt(i, 0);
        b.dx = o.optString("dynamicX", "0");
        b.dy = o.optString("dynamicY", "0");
        b.w = (float) o.optDouble("width", 50);
        b.h = (float) o.optDouble("height", 50);
        b.toggle = o.optBoolean("isToggle", false);
        b.swipe = o.optBoolean("isSwipeable", false);
        b.passThru = o.optBoolean("passThruEnabled", false);
        b.forwardLock = o.optBoolean("forwardLock", false);
        b.absolute = o.optBoolean("absolute", false);
        b.displayInGame = o.optBoolean("displayInGame", true);
        b.displayInMenu = o.optBoolean("displayInMenu", true);
        b.opacity = (float) o.optDouble("opacity", 1);
        b.strokeW = (float) o.optDouble("strokeWidth", 0);
        b.radius = (float) o.optDouble("cornerRadius", 0);
        b.bg = (int) o.optLong("bgColor", 0x4D000000L);
        b.stroke = (int) o.optLong("strokeColor", 0xFFFFFFFFL);
        return b;
    }
}
