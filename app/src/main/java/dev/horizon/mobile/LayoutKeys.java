package dev.horizon.mobile;

/**
 * What a Mojo key does in Horizon. Horizon takes gamepad-style input, so a key becomes a pad button,
 * a left-stick direction, a hotbar slot or the "hide controls" switch. Keys with no counterpart
 * (F3, Tab, the on-screen keyboard...) return NONE and their buttons are not shown.
 */
final class LayoutKeys {
    static final int NONE = 0, BTN = 1, DIR = 2, UI = 3, SLOT = 4;
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
        String name;
        switch (key) {
            case -1: name = "Экранная клавиатура"; break;
            case -5: name = "Виртуальная мышь"; break;
            case -6: name = "Средняя кнопка мыши"; break;
            case -10: name = "Прокрутка клавиатуры"; break;
            default: name = "Клавиша, код " + key;
        }
        return map(key, glfw) == null ? name + " (не работает в Horizon)" : name;
    }
}
