package dev.horizon.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/**
 * Editor for control layouts, modelled on MojoLauncher's (LGPL-3.0): drag controls around, tap one to
 * change its keys, size, colours and behaviour, add buttons / drawers / joysticks, save, load, export.
 * Layouts are the same JSON files Mojo uses, so they can be moved between the two apps.
 */
public final class LayoutEditorActivity extends Activity implements LayoutEditorView.Listener {
    private static final int REQ_IMPORT = 11, REQ_EXPORT = 12;
    private static final int PANEL_W = 290, BAR_H = 44;

    private LayoutEditorView view;
    private FrameLayout root;
    private ScrollView panel;
    private LinearLayout body;
    private TextView titleView;
    private float dp;

    private LayoutData layout;
    private String fileId;          // null: not saved yet (a new layout, or a copy of a built-in one)
    private boolean modified;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            build();
        } catch (Throwable e) {
            saveCrash(e);
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            TextView t = new TextView(this);
            t.setText("Редактор не смог открыться:\n\n" + sw);
            t.setTextColor(Color.WHITE);
            t.setBackgroundColor(0xFF101418);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setTextIsSelectable(true);
            t.setPadding(40, 80, 40, 40);
            ScrollView sv = new ScrollView(this);
            sv.addView(t);
            setContentView(sv);
        }
    }

    /** Writes the failure where "Логи и ошибки" in the menu reads it. */
    private void saveCrash(Throwable e) {
        try {
            java.io.File f = Dirs.javaCrash(this);
            f.getParentFile().mkdirs();
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            try (java.io.FileWriter w = new java.io.FileWriter(f)) {
                w.write(new java.util.Date() + "\nprocess editor\nthread " + Thread.currentThread().getName() + "\n" + sw);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void build() {
        dp = getResources().getDisplayMetrics().density;
        root = new FrameLayout(this);

        view = new LayoutEditorView(this);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // side panel with the properties of the selected control
        panel = new ScrollView(this);
        panel.setBackgroundColor(0xF0161B22);
        panel.setVisibility(View.GONE);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(12), px(8), px(12), px(16));
        panel.addView(body);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(px(PANEL_W), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        pp.topMargin = px(BAR_H);
        root.addView(panel, pp);

        // top bar: menu and the layout name
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xAA000000);
        bar.setPadding(px(8), 0, px(12), 0);
        Button menu = new Button(this);
        menu.setText("☰  Меню");
        menu.setAllCaps(false);
        menu.setTextColor(Color.WHITE);
        menu.setBackgroundColor(0xFF2B3A4A);
        menu.setOnClickListener(v -> showMenu());
        bar.addView(menu, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36)));
        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleView.setPadding(px(12), 0, 0, 0);
        titleView.setSingleLine(true);
        bar.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(BAR_H), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.addView(bar, bp);

        setContentView(root);
        view.setListener(this);

        String id = getIntent().getStringExtra("id");
        loadId(id != null ? id : LayoutStore.active(this));
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    // ---------------------------------------------------------------- loading and saving

    private void loadId(String id) {
        if (LayoutStore.SIMPLE.equals(id)) id = LayoutStore.MOJO;   // the simple controls are not a layout file
        try {
            layout = LayoutStore.load(this, id);
            fileId = LayoutStore.MOJO.equals(id) ? null : id;
        } catch (IOException e) {
            Toast.makeText(this, "Не открылась раскладка: " + e.getMessage(), Toast.LENGTH_LONG).show();
            try {
                layout = LayoutStore.load(this, LayoutStore.MOJO);
            } catch (IOException e2) {
                layout = LayoutData.blank();
            }
            fileId = null;
        }
        modified = false;
        view.setLayout(layout);
        updateTitle();
    }

    private void newBlank() {
        layout = LayoutData.blank();
        fileId = null;
        modified = false;
        view.setLayout(layout);
        updateTitle();
    }

    private void updateTitle() {
        String n = fileId != null ? LayoutStore.title(fileId) : "новая (не сохранена)";
        titleView.setText(n + (modified ? "  ●" : ""));
    }

    private void markModified() {
        if (!modified) {
            modified = true;
            updateTitle();
        }
    }

    private void save(Runnable after) {
        if (fileId == null) saveAs(after);
        else doSave(fileId, after);
    }

    private void doSave(String id, Runnable after) {
        try {
            LayoutStore.save(this, id, layout);
        } catch (IOException e) {
            Toast.makeText(this, "Не сохранилось: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        fileId = id;
        modified = false;
        updateTitle();
        Toast.makeText(this, "Сохранено: " + LayoutStore.title(id), Toast.LENGTH_SHORT).show();
        if (after != null) after.run();
    }

    private void saveAs(Runnable after) {
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(fileId != null ? LayoutStore.title(fileId) : "Моя раскладка");
        name.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Название раскладки")
                .setView(name)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, w) -> {
                    final String id = LayoutStore.idFor(name.getText().toString());
                    if (!id.equals(fileId) && LayoutStore.exists(this, id)) {
                        new AlertDialog.Builder(this)
                                .setMessage("Раскладка «" + LayoutStore.title(id) + "» уже есть. Заменить её?")
                                .setNegativeButton("Нет", null)
                                .setPositiveButton("Заменить", (d2, w2) -> doSave(id, after))
                                .show();
                    } else {
                        doSave(id, after);
                    }
                })
                .show();
    }

    /** Runs `next` right away if nothing is unsaved; otherwise asks first. */
    private void confirmDiscard(Runnable next) {
        if (!modified) {
            next.run();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Есть несохранённые изменения")
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Не сохранять", (d, w) -> next.run())
                .setPositiveButton("Сохранить", (d, w) -> save(next))
                .show();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        confirmDiscard(this::finish);
    }

    // ---------------------------------------------------------------- menu

    private void showMenu() {
        final String[] items = {
                "Добавить кнопку", "Добавить ящик", "Добавить джойстик",
                "Загрузить…", "Импорт из файла…", "Новая пустая",
                "Сохранить", "Сохранить как…", "Сделать активной",
                "Экспорт в файл…", "Удалить эту раскладку", "Выход"};
        new AlertDialog.Builder(this).setItems(items, (d, which) -> {
            switch (which) {
                case 0: view.addButton(); break;
                case 1: view.addDrawer(); break;
                case 2: view.addJoystick(); break;
                case 3: confirmDiscard(this::chooseLayout); break;
                case 4: confirmDiscard(this::pickImport); break;
                case 5: confirmDiscard(this::newBlank); break;
                case 6: save(null); break;
                case 7: saveAs(null); break;
                case 8: activate(); break;
                case 9: export(); break;
                case 10: deleteLayout(); break;
                default: confirmDiscard(this::finish);
            }
        }).show();
    }

    private void chooseLayout() {
        final java.util.List<String> ids = new java.util.ArrayList<>(LayoutStore.list(this));
        ids.remove(LayoutStore.SIMPLE);
        String[] names = new String[ids.size()];
        for (int i = 0; i < names.length; i++) names[i] = LayoutStore.title(ids.get(i));
        new AlertDialog.Builder(this).setTitle("Загрузить раскладку")
                .setItems(names, (d, which) -> loadId(ids.get(which)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void activate() {
        final Runnable use = () -> {
            LayoutStore.setActive(this, fileId);
            Toast.makeText(this, "Игра будет использовать «" + LayoutStore.title(fileId) + "»", Toast.LENGTH_LONG).show();
        };
        if (fileId == null || modified) save(use);
        else use.run();
    }

    private void export() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, (fileId != null ? LayoutStore.title(fileId) : "layout") + ".json");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void deleteLayout() {
        if (fileId == null) {
            Toast.makeText(this, "Эта раскладка ещё не сохранена, удалять нечего", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("Удалить раскладку «" + LayoutStore.title(fileId) + "» с телефона?")
                .setNegativeButton("Нет", null)
                .setPositiveButton("Удалить", (d, w) -> {
                    try {
                        LayoutStore.delete(this, fileId);
                    } catch (IOException e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    loadId(LayoutStore.active(this));
                })
                .show();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_IMPORT) {
            try {
                loadId(LayoutStore.importFrom(this, uri));
            } catch (IOException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (req == REQ_EXPORT) {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Не открывается файл для записи");
                out.write(layout.toJson().getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Файл записан", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(this, "Не записалось: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------------------------------------------------------------- canvas callbacks

    @Override
    public void onEdited() {
        markModified();
    }

    @Override
    public void onSelect(LayoutEditorView.Item it) {
        if (panel == null) return;
        if (it == null) {
            panel.setVisibility(View.GONE);
            return;
        }
        // the panel sits on the side away from the control, so it never hides what is being edited
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) panel.getLayoutParams();
        boolean controlOnLeft = it.x + it.w / 2f < view.getWidth() / 2f;
        p.gravity = controlOnLeft ? Gravity.END : Gravity.START;
        panel.setLayoutParams(p);
        buildPanel(it);
        panel.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- the property panel

    private void buildPanel(final LayoutEditorView.Item it) {
        final LayoutData.Btn d = it.d;
        final boolean joy = it.kind == LayoutEditorView.JOY;
        final boolean drawer = it.kind == LayoutEditorView.DRAWER;
        final boolean sub = it.kind == LayoutEditorView.SUB;
        final boolean sized = !(sub && !it.isFreeSub());     // size and position come from the drawer otherwise

        body.removeAllViews();
        panel.scrollTo(0, 0);
        body.addView(heading(joy ? "Джойстик" : drawer ? "Ящик" : sub ? "Кнопка ящика" : "Кнопка"));

        if (!joy) {
            body.addView(caption("Название"));
            body.addView(field(d.name, InputType.TYPE_CLASS_TEXT, s -> {
                d.name = s;
                changed(false);
            }));
        }

        if (sized) {
            if (joy) {
                body.addView(caption("Размер, dp"));
                body.addView(field(num(d.w), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, s -> {
                    float v = parse(s);
                    if (v >= 1) {
                        d.w = v;
                        d.h = v;               // joysticks are round
                        changed(true);
                    }
                }));
            } else {
                body.addView(caption("Ширина × высота, dp"));
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.addView(field(num(d.w), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, s -> {
                    float v = parse(s);
                    if (v >= 1) {
                        d.w = v;
                        changed(true);
                    }
                }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(field(num(d.h), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, s -> {
                    float v = parse(s);
                    if (v >= 1) {
                        d.h = v;
                        changed(true);
                    }
                }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                body.addView(row);
            }
        }

        if (!joy && !drawer) {
            body.addView(caption("Клавиши (до четырёх сразу)"));
            for (int i = 0; i < 4; i++) body.addView(keyButton(it, i));
            body.addView(switchRow("Переключатель (нажал — включено)", d.toggle, v -> {
                d.toggle = v;
                changed(false);
            }));
            body.addView(switchRow("Скольжение: пальцем с одной кнопки на другую", d.swipe, v -> {
                d.swipe = v;
                changed(false);
            }));
            body.addView(switchRow("Пропускать касание в игру (в Horizon пока не работает)", d.passThru, v -> {
                d.passThru = v;
                changed(false);
            }));
        }

        if (joy) {
            body.addView(switchRow("Фиксация вперёд (в Horizon пока не работает)", d.forwardLock, v -> {
                d.forwardLock = v;
                changed(false);
            }));
            body.addView(switchRow("Абсолютное слежение: стик прыгает под палец", d.absolute, v -> {
                d.absolute = v;
                changed(false);
            }));
        }

        if (drawer) {
            body.addView(caption("Куда раскрывается"));
            final Button o = button(orientationName(it.drawer.orientation), null);
            o.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setItems(new String[]{"Вниз", "Влево", "Вверх", "Вправо", "Свободно"}, (dlg, which) -> {
                        it.drawer.orientation = new String[]{"DOWN", "LEFT", "UP", "RIGHT", "FREE"}[which];
                        o.setText(orientationName(it.drawer.orientation));
                        changed(true);
                    }).show());
            body.addView(o);
        }

        body.addView(seekRow("Прозрачность", 100, Math.round(d.opacity * 100), p -> p + " %", p -> {
            d.opacity = p / 100f;
            changed(false);
        }));
        body.addView(seekRow("Толщина рамки", 100, Math.round(d.strokeW * 10), p -> (p / 10f) + " dp", p -> {
            d.strokeW = p / 10f;
            changed(false);
        }));
        if (!joy) {
            body.addView(seekRow("Скругление углов", 100, Math.round(d.radius), p -> p + " %", p -> {
                d.radius = p;
                changed(false);
            }));
        }
        body.addView(colorRow("Цвет фона", () -> d.bg, c -> {
            d.bg = c;
            changed(false);
        }, true));
        body.addView(colorRow("Цвет рамки", () -> d.stroke, c -> {
            d.stroke = c;
            changed(false);
        }, false));

        if (sized) {
            body.addView(caption("Положение: формулы"));
            body.addView(hint("${screen_width}, ${margin}, ${width}, ${right}, ${bottom}… — как в Mojo. Тащите кнопку, и формулы подставятся сами."));
            body.addView(field(d.dx, InputType.TYPE_CLASS_TEXT, s -> {
                d.dx = s;
                changed(true);
            }));
            body.addView(field(d.dy, InputType.TYPE_CLASS_TEXT, s -> {
                d.dy = s;
                changed(true);
            }));
        }

        body.addView(space());
        if (drawer || sub) {
            body.addView(button("+ Кнопка в ящик", v -> view.addSub()));
        }
        if (drawer) {
            final Button fold = button(view.isCollapsed(it) ? "Показать кнопки ящика" : "Свернуть кнопки ящика", null);
            fold.setOnClickListener(v -> {
                view.toggleCollapsed(it);
                fold.setText(view.isCollapsed(it) ? "Показать кнопки ящика" : "Свернуть кнопки ящика");
            });
            body.addView(fold);
        }
        body.addView(button("Копировать", v -> view.cloneSelected()));
        Button del = button("Удалить", v -> new AlertDialog.Builder(this)
                .setMessage(drawer ? "Удалить ящик вместе с его кнопками?" : "Удалить этот элемент?")
                .setNegativeButton("Нет", null)
                .setPositiveButton("Удалить", (dlg, w) -> view.deleteSelected())
                .show());
        del.setBackgroundColor(0xFF5A2B2B);
        body.addView(del);
    }

    /** A change from the panel. `geometry`: the size or position changed, so every box must be recomputed. */
    private void changed(boolean geometry) {
        markModified();
        if (geometry) view.relayout();
        else view.invalidate();
    }

    private static String orientationName(String o) {
        if (o == null) return "Влево";
        switch (o) {
            case "DOWN": return "Вниз";
            case "UP": return "Вверх";
            case "RIGHT": return "Вправо";
            case "FREE": return "Свободно";
            default: return "Влево";
        }
    }

    private static String num(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static float parse(String s) {
        try {
            return Float.parseFloat(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- key picker

    private Button keyButton(final LayoutEditorView.Item it, final int slot) {
        final boolean glfw = layout.glfwKeys();
        final Button b = button("", null);
        b.setText((slot + 1) + ":  " + LayoutKeys.label(it.d.keys[slot], glfw));
        b.setOnClickListener(v -> {
            final LayoutKeys.Opt[] opts = LayoutKeys.OPTS;
            String[] names = new String[opts.length + 2];
            names[0] = "— нет —";
            for (int i = 0; i < opts.length; i++) names[i + 1] = opts[i].label;
            names[names.length - 1] = "Другой код клавиши…";
            new AlertDialog.Builder(this).setTitle("Клавиша " + (slot + 1))
                    .setItems(names, (dlg, which) -> {
                        if (which == names.length - 1) {
                            customKey(it, slot, b);
                            return;
                        }
                        setKey(it, slot, b, which == 0 ? 0 : opts[which - 1].code(glfw));
                    }).show();
        });
        return b;
    }

    private void customKey(final LayoutEditorView.Item it, final int slot, final Button b) {
        final EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        code.setHint(layout.glfwKeys() ? "код GLFW" : "код Android KeyEvent");
        new AlertDialog.Builder(this).setTitle("Код клавиши")
                .setMessage("Такие клавиши в Horizon ничего не делают, но сохранятся в файле для Mojo.")
                .setView(code)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Готово", (d, w) -> {
                    try {
                        setKey(it, slot, b, Integer.parseInt(code.getText().toString().trim()));
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Нужно целое число", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void setKey(LayoutEditorView.Item it, int slot, Button b, int code) {
        it.d.keys[slot] = code;
        b.setText((slot + 1) + ":  " + LayoutKeys.label(code, layout.glfwKeys()));
        markModified();
        view.rebuild();      // re-checks whether Horizon understands any key of this button
    }

    // ---------------------------------------------------------------- colour picker

    private View colorRow(final String label, final IntSupplier get, final IntConsumer set, final boolean alpha) {
        final Button b = button(label, null);
        final Runnable paint = () -> {
            GradientDrawable g = new GradientDrawable();
            g.setColor(alpha ? get.getAsInt() : (get.getAsInt() | 0xFF000000));
            g.setStroke(px(2), 0x88FFFFFF);
            g.setCornerRadius(px(4));
            b.setBackground(g);
        };
        paint.run();
        b.setOnClickListener(v -> pickColor(label, get.getAsInt(), alpha, c -> {
            set.accept(c);
            paint.run();
        }));
        return b;
    }

    private void pickColor(String title, int start, boolean alpha, final IntConsumer apply) {
        final int[] argb = {alpha ? Color.alpha(start) : 255, Color.red(start), Color.green(start), Color.blue(start)};
        final View preview = new View(this);
        final Runnable update = () -> {
            int c = Color.argb(argb[0], argb[1], argb[2], argb[3]);
            preview.setBackgroundColor(c);
            apply.accept(c);
        };
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(8), px(20), 0);
        box.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40)));
        final String[] names = {"Прозрачность", "Красный", "Зелёный", "Синий"};
        for (int i = alpha ? 0 : 1; i < 4; i++) {
            final int ch = i;
            TextView t = new TextView(this);
            t.setText(names[i] + ": " + argb[i]);
            box.addView(t);
            SeekBar s = new SeekBar(this);
            s.setMax(255);
            s.setProgress(argb[i]);
            s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    t.setText(names[ch] + ": " + p);
                    if (fromUser) {
                        argb[ch] = p;
                        update.run();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {}

                @Override
                public void onStopTrackingTouch(SeekBar sb) {}
            });
            box.addView(s);
        }
        preview.setBackgroundColor(Color.argb(argb[0], argb[1], argb[2], argb[3]));
        new AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Готово", null).show();
    }

    // ---------------------------------------------------------------- small widgets

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF4FC3F7);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setPadding(0, px(4), 0, px(6));
        return t;
    }

    private TextView caption(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFB0BEC5);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, px(10), 0, px(2));
        return t;
    }

    private TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF78909C);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        return t;
    }

    private View space() {
        return new View(this) {
            @Override
            protected void onMeasure(int w, int h) {
                setMeasuredDimension(1, px(12));
            }
        };
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF2B3A4A);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44));
        p.topMargin = px(6);
        b.setLayoutParams(p);
        return b;
    }

    private abstract static class Watch implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void afterTextChanged(Editable e) {
            changed(e.toString());
        }

        abstract void changed(String s);
    }

    private EditText field(String value, int inputType, final Consumer<String> onChange) {
        EditText e = new EditText(this);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setText(value == null ? "" : value);
        e.addTextChangedListener(new Watch() {
            @Override
            void changed(String s) {
                onChange.accept(s);
            }
        });
        return e;
    }

    private Switch switchRow(String label, boolean value, final java.util.function.Consumer<Boolean> onChange) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Color.WHITE);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        s.setChecked(value);
        s.setPadding(0, px(8), 0, px(4));
        s.setOnCheckedChangeListener((b, checked) -> onChange.accept(checked));
        return s;
    }

    private View seekRow(final String label, int max, int value, final IntFunction<String> fmt, final IntConsumer onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        final TextView t = caption(label + ": " + fmt.apply(value));
        row.addView(t);
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(value);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                t.setText(label + ": " + fmt.apply(p));
                if (fromUser) onChange.accept(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        row.addView(s);
        return row;
    }
}
