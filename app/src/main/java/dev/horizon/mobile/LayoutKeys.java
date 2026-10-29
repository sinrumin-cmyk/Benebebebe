package dev.horizon.mobile;

/**
 * What a Mojo key does in Horizon. Horizon takes gamepad-style input, so a key becomes a pad button,
 * a left-stick direction, a hotbar slot or the "hide controls" switch. Keys with no counterpart
 * (F3, Tab, letters the pad has no use for...) go to the game as real key presses, see {@link #action}.
 */
final class LayoutKeys {
    static final int NONE = 0, BTN = 1, DIR = 2, UI = 3, SLOT = 4;
    /** Beyond the pad: a real key {INJECT, scheme, code}, the on-screen keyboard, the virtual mouse, the middle button, the FPS counter. */
    static final int INJECT = 10, KEYBOARD = 11, VMOUSE = 12, MMB = 13, FPS = 14;
    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3;

    private static int[] act(int type, int arg) { return new int[]{type, arg}; }

    /** @param glfw true for layouts older than version 9 (GLFW key codes), false for version 9 (Android key codes) */
    static int[] map(int key, boolean glfw) {
        if (key < 0) {
            switch (key) {
                case -2: return act(UI, 0);                               // GUI: hide / show the controls
                case -3: return act(BTN, HorizonInput.RT);                // primary mouse: attack
                case -4: return act(BTN, HorizonInput.LT);                // secondary mouse: use / place
                case -7: return act(BTN, HorizonInput.LB);                // scroll up: previous slot
                case -8: return act(BTN, HorizonInput.RB);                // scroll down: next slot
                case -9: return act(BTN, HorizonInput.START);             // menu
                default: return null;
            }
        }
        if (key == 0) return null;
        return glfw ? fromGlfw(key) : fromAndroid(key);
    }

    private static int[] fromGlfw(int k) {
        if (k >= 49 && k <= 57) return act(SLOT, k - 49);
        switch (k) {
            case 87: return act(DIR, UP);            // W
            case 83: return act(DIR, DOWN);          // S
            case 65: return act(DIR, LEFT);          // A
            case 68: return act(DIR, RIGHT);         // D
            case 32: case 257: return act(BTN, HorizonInput.SOUTH);          // Space, Enter
            case 340: case 344: return act(BTN, HorizonInput.RSTICK);        // Shift: sneak
            case 341: case 345: return act(BTN, HorizonInput.LSTICK);        // Ctrl: sprint
            case 69: return act(BTN, HorizonInput.NORTH);                    // E: inventory
            case 81: return act(BTN, HorizonInput.EAST);                     // Q: drop
            case 70: return act(BTN, HorizonInput.WEST);                     // F: swap hands
            case 84: return act(BTN, HorizonInput.DRIGHT);                   // T: chat
            case 256: return act(BTN, HorizonInput.START);                   // Esc
            case 294: return act(BTN, HorizonInput.DUP);                     // F5: camera
            default: return null;
        }
    }

    private static int[] fromAndroid(int k) {
        if (k >= 8 && k <= 16) return act(SLOT, k - 8);
        switch (k) {
            case 51: return act(DIR, UP);            // KEYCODE_W
            case 47: return act(DIR, DOWN);          // KEYCODE_S
            case 29: return act(DIR, LEFT);          // KEYCODE_A
            case 32: return act(DIR, RIGHT);         // KEYCODE_D
            case 62: case 66: return act(BTN, HorizonInput.SOUTH);           // Space, Enter
            case 59: case 60: return act(BTN, HorizonInput.RSTICK);          // Shift
            case 113: case 114: return act(BTN, HorizonInput.LSTICK);        // Ctrl
            case 33: return act(BTN, HorizonInput.NORTH);                    // E
            case 45: return act(BTN, HorizonInput.EAST);                     // Q
            case 34: return act(BTN, HorizonInput.WEST);                     // F
            case 48: return act(BTN, HorizonInput.DRIGHT);                   // T
            case 111: return act(BTN, HorizonInput.START);                   // Escape
            case 135: return act(BTN, HorizonInput.DUP);                     // F5 (KEYCODE_F5 is 135; 136 is F6)
            default: return null;
        }
    }

    // ---------------------------------------------------------------- everything else a layout can press

    private static boolean in(int v, int lo, int hi) {
        return v >= lo && v <= hi;
    }

    /** Keys the game can be sent as real key presses (kept in step with touch_input.rs's tables). */
    static boolean injectable(int key, boolean glfw) {
        if (glfw) {
            if (in(key, 65, 90) || in(key, 48, 57) || in(key, 290, 301)) return true;
            switch (key) {
                case 32: case 256: case 257: case 335: case 258: case 259: case 260: case 261: case 262: case 263:
                case 264: case 265: case 266: case 267: case 268: case 269: case 280: case 340: case 341: case 342:
                case 343: case 344: case 345: case 346: case 347: case 44: case 45: case 46: case 47: case 59:
                case 61: case 91: case 92: case 93: case 96: case 39:
                    return true;
                default:
                    return false;
            }
        }
        if (in(key, 29, 54) || in(key, 7, 16) || in(key, 131, 142)) return true;
        switch (key) {
            case 61: case 66: case 160: case 62: case 67: case 112: case 111: case 19: case 20: case 21: case 22:
            case 122: case 123: case 92: case 93: case 124: case 59: case 60: case 113: case 114: case 57: case 58:
            case 117: case 118: case 115: case 55: case 56: case 76: case 74: case 75: case 71: case 72: case 73:
            case 69: case 70: case 68:
                return true;
            default:
                return false;
        }
    }

    /**
     * What pressing this key does: a pad action (see {@link #map}), else a real key press, the keyboard, the
     * virtual mouse, the middle button or the FPS counter; null for a key nothing can do.
     */
    static int[] action(int key, boolean glfw) {
        int[] a = map(key, glfw);
        if (a != null) return a;
        switch (key) {
            case -1: return act(KEYBOARD, 0);
            case -5: return act(VMOUSE, 0);
            case -6: return act(MMB, 0);
            case -11: return act(FPS, 0);
            default: break;
        }
        if (key > 0 && injectable(key, glfw)) return new int[]{INJECT, glfw ? 1 : 0, key};
        return null;
    }

    /** More keys the editor offers, all sent to the game as real key presses (or the shell's own switches). */
    static final Opt[] EXTRA = buildExtra();

    private static Opt[] buildExtra() {
        java.util.List<Opt> l = new java.util.ArrayList<>();
        l.add(new Opt("Экранная клавиатура", -1, -1));
        l.add(new Opt("Виртуальная мышь (вкл / выкл)", -5, -5));
        l.add(new Opt("Средняя кнопка мыши", -6, -6));
        l.add(new Opt("Счётчик FPS (вкл / выкл)", -11, -11));
        l.add(new Opt("Tab (список игроков)", 61, 258));
        l.add(new Opt("Backspace", 67, 259));
        l.add(new Opt("Delete", 112, 261));
        l.add(new Opt("F1 (скрыть интерфейс)", 131, 290));
        l.add(new Opt("F2 (скриншот)", 132, 291));
        l.add(new Opt("F3 (отладка)", 133, 292));
        l.add(new Opt("F4", 134, 293));
        l.add(new Opt("F6", 136, 295));
        l.add(new Opt("F7", 137, 296));
        l.add(new Opt("F8", 138, 297));
        l.add(new Opt("F9", 139, 298));
        l.add(new Opt("F10", 140, 299));
        l.add(new Opt("F11 (полный экран)", 141, 300));
        l.add(new Opt("F12", 142, 301));
        l.add(new Opt("Стрелка вверх", 19, 265));
        l.add(new Opt("Стрелка вниз", 20, 264));
        l.add(new Opt("Стрелка влево", 21, 263));
        l.add(new Opt("Стрелка вправо", 22, 262));
        l.add(new Opt("Home", 122, 268));
        l.add(new Opt("End", 123, 269));
        l.add(new Opt("Page Up", 92, 266));
        l.add(new Opt("Page Down", 93, 267));
        l.add(new Opt("0", 7, 48));
        String letters = "BCGHIJKLMNOPRUVXYZ";
        for (int i = 0; i < letters.length(); i++) {
            char c = letters.charAt(i);
            l.add(new Opt(String.valueOf(c), 29 + (c - 'A'), c));
        }
        return l.toArray(new Opt[0]);
    }

    // ---------------------------------------------------------------- keys the editor offers

    /** A key the layout editor offers: its label and its code in both layout formats. */
    static final class Opt {
        final String label;
        final int android, glfw;

        Opt(String label, int android, int glfw) {
            this.label = label;
            this.android = android;
            this.glfw = glfw;
        }

        int code(boolean glfwFormat) {
            return glfwFormat ? glfw : android;
        }
    }

    /** Everything Horizon can do from a key, in the order the picker lists it. */
    static final Opt[] OPTS = buildOpts();

    private static Opt[] buildOpts() {
        java.util.List<Opt> l = new java.util.ArrayList<>();
        l.add(new Opt("Скрыть / показать кнопки", -2, -2));
        l.add(new Opt("Атака / ломать (ЛКМ)", -3, -3));
        l.add(new Opt("Использовать / ставить (ПКМ)", -4, -4));
        l.add(new Opt("Предыдущий слот (колесо вверх)", -7, -7));
        l.add(new Opt("Следующий слот (колесо вниз)", -8, -8));
        l.add(new Opt("Пауза / меню", -9, -9));
        l.add(new Opt("Вперёд (W)", 51, 87));
        l.add(new Opt("Назад (S)", 47, 83));
        l.add(new Opt("Влево (A)", 29, 65));
        l.add(new Opt("Вправо (D)", 32, 68));
        l.add(new Opt("Прыжок (Пробел)", 62, 32));
        l.add(new Opt("Красться (Shift)", 59, 340));
        l.add(new Opt("Бег (Ctrl)", 113, 341));
        l.add(new Opt("Инвентарь (E)", 33, 69));
        l.add(new Opt("Выбросить (Q)", 45, 81));
        l.add(new Opt("Поменять руки (F)", 34, 70));
        l.add(new Opt("Чат (T)", 48, 84));
        l.add(new Opt("Пауза (Esc)", 111, 256));
        l.add(new Opt("Камера (F5)", 135, 294));
        for (int i = 0; i < 9; i++) l.add(new Opt("Слот " + (i + 1), 8 + i, 49 + i));
        return l.toArray(new Opt[0]);
    }

    /** Human name of a key code; marks codes Horizon does nothing with. */
    static String label(int key, boolean glfw) {
        if (key == 0) return "— нет —";
        for (Opt o : OPTS) if (o.code(glfw) == key) return o.label;
        for (Opt o : EXTRA) if (o.code(glfw) == key) return o.label;
        String name = key == -10 ? "Прокрутка клавиатуры" : "Клавиша, код " + key;
        return action(key, glfw) == null ? name + " (не работает в Horizon)" : name;
    }
}
