package com.local.tvlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Launcher settings, built for the remote: a vertical list of focusable rows. */
public class SettingsActivity extends Activity {
    private static final String PROJECTIVY = "com.spocky.projengmenu";

    private Prefs prefs;
    private LinearLayout list;
    private int focusIndex;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        // The separate switch is gone: whether the key is intercepted now depends only on the service.
        if (!prefs.interceptHome()) prefs.setInterceptHome(true);
        Ui.apply(prefs.theme());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(this, 120), Ui.dp(this, 32), Ui.dp(this, 120), Ui.dp(this, 48));
        scroll.addView(list);
        setContentView(scroll);
        if (state != null) openGroup = state.getInt(OPEN_GROUP, GROUP_NONE);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(OPEN_GROUP, openGroup);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        prefs.setReturnAfterService(false);
        build();
    }

    private void build() {
        View f = getCurrentFocus();
        if (f != null && f.getTag() instanceof Integer) focusIndex = (Integer) f.getTag();
        list.removeAllViews();
        rowsIn = list;
        rowCount = 0;

        TextView title = Ui.light(this, 34, Ui.TEXT);
        title.setText("Настройки Luch");
        title.setPadding(0, 0, 0, Ui.dp(this, 8));
        list.addView(title);

        if (prefs.selfDisabled()) {
            note("⚠ Лаунчер несколько раз упал и сам отключился как главный экран, чтобы не мешать "
                    + "работе проектора. Перехват кнопки «Домой» тоже приостановлен.");
            action("Вернуть лаунчер", "Снова сделать его главным экраном (без быстрой загрузки)", null,
                    new Runnable() { public void run() {
                        prefs.setSelfDisabled(false);
                        prefs.clearCrashes();
                        build();
                        makeDefault(false);
                    } });
        }

        // ---------------------------------------------------------------- setup
        boolean home = isOurHomeDefault();
        final boolean service = prefs.serviceOn();
        if (!home) prefs.setSetupDone(false);
        else if (prefs.earlyBoot()) prefs.setSetupDone(true);
        final boolean done = prefs.setupDone() || prefs.stepsHidden();
        if (!done) {
        section("Настройка по шагам");
        note("Сначала можно просто посмотреть: откройте «Luch» из списка приложений. "
                + "Шаги ниже меняют поведение проектора, и всё это можно вернуть обратно.");
        step("1. Сделать Luch главным экраном",
                home ? "Проектор открывает Luch как главный экран"
                        : "Сейчас главный экран: " + defaultHomeSummary(),
                home ? "Готово" : "Сделать", home, new Runnable() {
                    public void run() { makeDefault(false); }
                });
        step("2. Подключить кнопку «Домой»",
                service ? "Кнопка на пульте всегда открывает Luch. Нажмите, если нужно это отключить"
                        : home ? "Нужно, только если кнопка на пульте всё равно открывает старую оболочку"
                                : "На системном экране нужно один раз включить «"
                                        + getString(R.string.key_service_label) + "»",
                service ? "Готово" : home ? "По желанию" : "Сделать", service, new Runnable() {
                    public void run() { openAccessibility(); }
                });
        toggle("3. Быстрая загрузка",
                "Luch показывается сразу при включении, без вспышки старой оболочки. "
                        + "Если он упадёт при загрузке 3 раза, режим выключится сам",
                prefs.earlyBoot(), new Toggle() {
                    public void set(boolean on) {
                        if (on) confirmEarlyBoot(); else switchEarlyBoot(false);
                    }
                });
        action("Скрыть эти шаги", "Всё это останется в разделе «Система»", null, new Runnable() {
            public void run() {
                prefs.setStepsHidden(true);
                build();
            }
        });
        }
        if (prefs.earlyBootAutoOff()) {
            note("⚠ Быстрая загрузка была выключена автоматически: лаунчер несколько раз упал при загрузке. "
                    + "Проектор вернулся к обычному поведению прошивки.");
        }
        if (serviceEnabledPkg(PROJECTIVY)) {
            note("⚠ Служба Projectivy тоже перехватывает кнопки. Пока она включена, кнопка «Домой» "
                    + "может открывать то один лаунчер, то другой. Выключите её в спецвозможностях.");
        }

        if (!done) section("Настройки");

        // ---------------------------------------------------------------- look
        group(GROUP_LOOK, "Оформление", "Тема, фон, луч проектора, анимация");
        if (openGroup == GROUP_LOOK) {
            beginGroupRows();
            action("Тема", "Цвет акцента и оттенок фона. Все темы тёмные, чтобы меню не засвечивало комнату",
                    Ui.THEMES[Math.min(prefs.theme(), Ui.THEMES.length - 1)], new Runnable() {
                        public void run() { pickTheme(); }
                    });
            action("Фон", "Своя картинка: положите её в «Загрузки» (с флешки или по сети) "
                            + "и выберите здесь", Wallpaper.MODES[prefs.background()], new Runnable() {
                        public void run() { pickBackground(); }
                    });
            if (prefs.background() == Wallpaper.IMAGE) {
                toggle("Медленный наезд", "Картинка очень медленно приближается и смещается, как в документальном кино",
                        prefs.kenBurns(), new Toggle() {
                            public void set(boolean on) { prefs.setKenBurns(on); }
                        });
            }
            if (prefs.background() == Wallpaper.BEAM) {
                action("Яркость луча", "На самом ярком луч может проходить по вкладкам сверху",
                        Ambient.BRIGHTNESS_NAMES[prefs.beamBright()], new Runnable() {
                            public void run() {
                                choose("Яркость луча", Ambient.BRIGHTNESS_NAMES, prefs.beamBright(), new Pick() {
                                    public void pick(int i) {
                                        prefs.setBeam(i, prefs.beamFrom(), prefs.beamColor(), prefs.flicker());
                                    }
                                });
                            }
                        });
                action("Откуда светит", "С какой стороны идёт свет проектора",
                        Ambient.SOURCES[prefs.beamFrom()], new Runnable() {
                            public void run() {
                                choose("Откуда светит", Ambient.SOURCES, prefs.beamFrom(), new Pick() {
                                    public void pick(int i) {
                                        prefs.setBeam(prefs.beamBright(), i, prefs.beamColor(), prefs.flicker());
                                    }
                                });
                            }
                        });
                action("Ширина луча", "Насколько широко расходится свет",
                        Ambient.WIDTH_NAMES[prefs.beamWidth()], new Runnable() {
                            public void run() {
                                choose("Ширина луча", Ambient.WIDTH_NAMES, prefs.beamWidth(), new Pick() {
                                    public void pick(int i) { prefs.setBeamWidth(i); }
                                });
                            }
                        });
                action("Цвет луча", "Тёплый — как лампа старого кинопроектора, белый — как ксеноновая",
                        Ambient.COLORS[prefs.beamColor()], new Runnable() {
                            public void run() {
                                choose("Цвет луча", Ambient.COLORS, prefs.beamColor(), new Pick() {
                                    public void pick(int i) {
                                        prefs.setBeam(prefs.beamBright(), prefs.beamFrom(), i, prefs.flicker());
                                    }
                                });
                            }
                        });
                action("Пылинки в луче", "Сколько пылинок летает в свете проектора",
                        Ambient.DUST_NAMES[prefs.beamDust()], new Runnable() {
                            public void run() {
                                choose("Пылинки в луче", Ambient.DUST_NAMES, prefs.beamDust(), new Pick() {
                                    public void pick(int i) { prefs.setBeamDust(i); }
                                });
                            }
                        });
                toggle("Мерцание плёнки", "Едва заметное подрагивание света, как у плёночного кинопроектора",
                        prefs.flicker(), new Toggle() {
                            public void set(boolean on) {
                                prefs.setBeam(prefs.beamBright(), prefs.beamFrom(), prefs.beamColor(), on);
                            }
                        });
            }
            toggle("Плёночное зерно", "Едва заметное живое зерно поверх фона, как у киноплёнки",
                    prefs.grain(), new Toggle() {
                        public void set(boolean on) { prefs.setGrain(on); }
                    });
            final int fps = prefs.animFps();
            action("Плавность анимации", "Кадров в секунду для анимированного фона, наезда и слайд-шоу. "
                            + "12 — вдвое меньше нагрузки; 24 — плавнее, как в кино",
                    fps + " кадров/с", new Runnable() {
                        public void run() {
                            prefs.setAnimFps(fps == 12 ? 24 : 12);
                            build();
                        }
                    });
            rowsIn = list;
        }

        // --------------------------------------------------------- home screen
        group(GROUP_HOME, "Главный экран", "Приложения, ожидание, отступы от краёв");
        if (openGroup == GROUP_HOME) {
            beginGroupRows();
            action("Кнопка «Домой» открывает", "Какая вкладка открывается при нажатии «Домой» и при запуске Luch",
                    Prefs.START_TABS[prefs.startTab()], new Runnable() {
                        public void run() {
                            choose("Кнопка «Домой» открывает", Prefs.START_TABS, prefs.startTab(), new Pick() {
                                public void pick(int i) { prefs.setStartTab(i); }
                            });
                        }
                    });
            if (Apps.hdmi(this) != null) {
                toggle("Плитка HDMI", "Плитка в нижнем ряду: сразу переключает проектор на вход HDMI, "
                                + "например на приставку или ноутбук",
                        prefs.hdmiTile(), new Toggle() {
                            public void set(boolean on) { prefs.setHdmiTile(on); }
                        });
            }
            int hidden = prefs.hidden().size();
            action("Скрытые приложения", "Вернуть скрытые приложения в список",
                    hidden == 0 ? "нет" : String.valueOf(hidden), new Runnable() {
                        public void run() { pickHidden(); }
                    });
            action("Затемнение при бездействии", "Меню гаснет, если долго не нажимать кнопки. "
                            + "Меньше лишнего света в тёмной комнате",
                    minutesLabel(prefs.idleMinutes()), new Runnable() {
                        public void run() { pickIdle(); }
                    });
            action("Режим ожидания", "Что показывать, когда меню погасло. Слайд-шоу берёт фото из папок "
                            + "«Изображения», «Камера» и «Загрузки»",
                    Prefs.IDLE_MODES[prefs.idleMode()], new Runnable() {
                        public void run() { pickIdleMode(); }
                    });
            action("Отступы от краёв", "Если проектор обрезает края картинки (зум, трапеция)",
                    prefs.safeMargin() == 0 ? "нет" : prefs.safeMargin() + "%", new Runnable() {
                        public void run() { pickMargin(); }
                    });
            rowsIn = list;
        }

        // -------------------------------------------------------------- system
        group(GROUP_SYSTEM, "Система", "Кнопка «Домой», оболочка производителя, автозапуск");
        if (openGroup == GROUP_SYSTEM) {
            beginGroupRows();
            if (prefs.stepsHidden()) {
                action("Показать настройку по шагам", "Вернуть шаги наверх этого экрана", null, new Runnable() {
                    public void run() {
                        prefs.setStepsHidden(false);
                        build();
                    }
                });
            }
            if (done && !home) {
                action("Сделать Luch главным экраном", "Сейчас главный экран: " + defaultHomeSummary(),
                        null, new Runnable() { public void run() { makeDefault(false); } });
            }
            action("Другая оболочка", "Сейчас главный экран: " + defaultHomeSummary()
                            + ". Откроется системный выбор главного экрана",
                    null, new Runnable() { public void run() { restoreOtherHome(); } });
            if (done) {
                action("Кнопка «Домой»", service ? "Кнопка на пульте всегда открывает Luch. Нажмите, чтобы отключить"
                                : "Нужно, только если кнопка на пульте всё равно открывает старую оболочку",
                        service ? "Подключена" : "Не подключена", new Runnable() {
                            public void run() {
                                if (service) turnOffService(); else openAccessibility();
                            }
                        });
                toggle("Быстрая загрузка", "Luch показывается сразу при включении, без вспышки старой оболочки",
                        prefs.earlyBoot(), new Toggle() {
                            public void set(boolean on) {
                                if (on) confirmEarlyBoot(); else switchEarlyBoot(false);
                            }
                        });
            }
            if (service) {
                action("Заменять оболочку производителя",
                        "Если прошивка откроет свою оболочку, например WHALE, сразу откроется Luch",
                        stockSummary(), new Runnable() {
                            public void run() { pickStock(); }
                        });
            }
            String auto = prefs.autostart();
            action("Автозапуск при включении",
                    "Это приложение откроется само сразу после включения проектора",
                    auto.isEmpty() ? "нет" : Apps.labelOfComponent(this, auto), new Runnable() {
                        public void run() { pickAutostart(); }
                    });
            action("Системные настройки", null, null, new Runnable() {
                public void run() { open(new Intent(Settings.ACTION_SETTINGS)); }
            });
            rowsIn = list;
        }
        note("Luch " + version());

        View target = list.findViewWithTag(Math.min(focusIndex, rowCount - 1));
        if (target != null) target.requestFocus();
    }

    // -------------------------------------------------------------- dialogs

    private static String minutesLabel(int m) {
        return m == 0 ? "никогда" : m + " мин";
    }

    private void pickIdle() {
        final int[] values = Prefs.IDLE_MINUTES;
        String[] labels = new String[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = minutesLabel(values[i]);
            if (values[i] == prefs.idleMinutes()) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Затемнение при бездействии")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        prefs.setIdleMinutes(values[which]);
                        d.dismiss();
                        build();
                    }
                })
                .show();
    }

    private static final int REQ_SLIDES = 8;

    private void pickIdleMode() {
        new AlertDialog.Builder(this)
                .setTitle("Режим ожидания")
                .setSingleChoiceItems(Prefs.IDLE_MODES, prefs.idleMode(), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which == Prefs.IDLE_SLIDES && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                                != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_SLIDES);
                            return;
                        }
                        prefs.setIdleMode(which);
                        build();
                    }
                })
                .show();
    }

    private interface Pick {
        void pick(int index);
    }

    /** A list with one choice; picking an item saves it and rebuilds the screen. */
    private void choose(String title, String[] items, int checked, final Pick pick) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        pick.pick(which);
                        d.dismiss();
                        build();
                    }
                })
                .show();
    }

    private void pickMargin() {
        final int[] values = Prefs.MARGINS;
        String[] labels = new String[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] == 0 ? "Без отступов" : values[i] + "% с каждой стороны";
            if (values[i] == prefs.safeMargin()) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Отступы от краёв")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        prefs.setSafeMargin(values[which]);
                        d.dismiss();
                        build();
                    }
                })
                .show();
    }

    private void pickBackground() {
        new AlertDialog.Builder(this)
                .setTitle("Фон")
                .setSingleChoiceItems(Wallpaper.MODES, prefs.background(), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which == Wallpaper.IMAGE) pickImage();
                        else {
                            prefs.setBackground(which);
                            build();
                        }
                    }
                })
                .show();
    }

    private static final int REQ_STORAGE = 7;

    private void pickImage() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        final List<File> files = Wallpaper.candidates();
        if (files.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Картинок не найдено")
                    .setMessage("Положите JPG или PNG в папку «Загрузки» (Download) или «Изображения» "
                            + "(Pictures) на проекторе, например с флешки через файловый менеджер, и выберите снова.")
                    .setPositiveButton("Понятно", null)
                    .show();
            return;
        }
        String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            names[i] = f.getName() + "   ·   " + f.getParentFile().getName();
        }
        new AlertDialog.Builder(this)
                .setTitle("Картинка для фона")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        final File src = files.get(which);
                        final Toast wait = Toast.makeText(SettingsActivity.this, "Готовлю фон…", Toast.LENGTH_SHORT);
                        wait.show();
                        new Thread(new Runnable() {
                            @Override public void run() {
                                String err = null;
                                try {
                                    Wallpaper.set(SettingsActivity.this, src);
                                } catch (Throwable e) {
                                    err = e.getMessage();
                                }
                                final String error = err;
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (error != null) {
                                            Toast.makeText(SettingsActivity.this, "Не получилось: " + error,
                                                    Toast.LENGTH_LONG).show();
                                            return;
                                        }
                                        prefs.setBackground(Wallpaper.IMAGE);
                                        build();
                                    }
                                });
                            }
                        }, "wallpaper").start();
                    }
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == REQ_SLIDES) {
            if (granted) {
                prefs.setIdleMode(Prefs.IDLE_SLIDES);
                build();
            } else {
                Toast.makeText(this, "Без доступа к файлам слайд-шоу не показать", Toast.LENGTH_LONG).show();
            }
        } else if (code == REQ_STORAGE && granted) {
            pickImage();
        } else if (code == REQ_STORAGE) {
            Toast.makeText(this, "Без доступа к файлам картинку не выбрать", Toast.LENGTH_LONG).show();
        }
    }

    private void pickTheme() {
        new AlertDialog.Builder(this)
                .setTitle("Тема")
                .setSingleChoiceItems(Ui.THEMES, prefs.theme(), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        prefs.setTheme(which);
                        d.dismiss();
                        recreate();
                    }
                })
                .show();
    }

    private void confirmEarlyBoot() {
        new AlertDialog.Builder(this)
                .setTitle("Включить быструю загрузку?")
                .setMessage("При включении проектора сразу будет показан этот лаунчер, а оболочка "
                        + "производителя запускаться не будет: ни её экрана, ни её рекламы.\n\n"
                        + "Функции прошивки от неё не зависят: источники, автофокус, трапеция и "
                        + "трансляция работают отдельными приложениями.\n\n"
                        + "Если лаунчер 3 раза упадёт при загрузке, режим выключится сам.")
                .setPositiveButton("Включить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { switchEarlyBoot(true); }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void switchEarlyBoot(boolean on) {
        if (prefs.selfDisabled()) prefs.setSelfDisabled(false);
        prefs.setEarlyBoot(on);
        prefs.setEarlyBootAutoOff(false);
        prefs.clearCrashes();
        build();
        if (isOurHomeDefault()) {
            Toast.makeText(this, on ? "Быстрая загрузка включена" : "Быстрая загрузка выключена",
                    Toast.LENGTH_LONG).show();
        } else {
            makeDefault(true);
        }
    }

    // -------------------------------------------------------- default home

    /** What Android starts as the home screen right now (the chooser "android" if nothing is set). */
    private ResolveInfo currentHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        return getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
    }

    private boolean resolves(Intent i) {
        return getPackageManager().resolveActivity(i, 0) != null;
    }

    /**
     * Walks the user through making this launcher the default home screen. An app may not do it by
     * itself (Android protects the choice), so this opens the right system screen at each step:
     * - already the default: says so;
     * - another launcher is the default: shows which, and where to clear that choice;
     * - nothing chosen: opens the system "choose home screen" window right away.
     */
    private void makeDefault(boolean afterToggle) {
        final ComponentName mine = prefs.home();
        ResolveInfo ri = currentHome();
        String pkg = ri == null || ri.activityInfo == null ? "android" : ri.activityInfo.packageName;
        String prefix = afterToggle ? "Режим переключён.\n\n" : "";

        if (pkg.equals(getPackageName()) && ri.activityInfo.name.equals(mine.getClassName())) {
            new AlertDialog.Builder(this)
                    .setTitle("Главный экран")
                    .setMessage(prefix + "Luch уже главный экран. Больше ничего делать не нужно.")
                    .setPositiveButton("Хорошо", null)
                    .show();
            return;
        }

        final Intent homeSettings = new Intent(Settings.ACTION_HOME_SETTINGS);
        final boolean hasHomeSettings = resolves(homeSettings);

        // Android 10+: the official one-tap way, a system "make it the home app?" question.
        // Not for a system launcher with a raised priority: it would still win afterwards.
        if (Build.VERSION.SDK_INT >= 29 && (ri == null || ri.priority <= 0) && requestHomeRole()) return;

        if ("android".equals(pkg)) {
            // Nothing is chosen: the system chooser appears when the home screen is requested.
            new AlertDialog.Builder(this)
                    .setTitle("Сделать главным экраном")
                    .setMessage(prefix + "Сейчас откроется окно выбора главного экрана.\n\n"
                            + "Выберите «Luch» и нажмите «Всегда».")
                    .setPositiveButton("Открыть выбор", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        }
                    })
                    .setNegativeButton("Позже", null)
                    .show();
            return;
        }

        final String other = pkg;
        String otherName = other.equals(getPackageName()) ? "Luch (другой режим)" : Apps.label(this, other);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Сделать главным экраном");
        if (ri.priority > 0) {
            // A system launcher with a raised priority wins regardless of the user's choice.
            b.setMessage(prefix + "Сейчас главный экран — «" + otherName + "». Это системная оболочка "
                    + "с повышенным приоритетом: Android выбирает её всегда, и сменить её можно только "
                    + "через ADB.\n\n" + adbHint(mine, other));
            b.setPositiveButton("Понятно", null);
        } else if (hasHomeSettings) {
            b.setMessage(prefix + "Сейчас главный экран — «" + otherName + "».\n\n"
                    + "Откроется системный выбор главного экрана: выберите в нём «Luch».");
            b.setPositiveButton("Открыть", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { open(homeSettings); }
            });
            b.setNegativeButton("Позже", null);
        } else if (other.equals(getPackageName())) {
            // Our other mode was chosen; that choice became invalid with the switch.
            b.setMessage(prefix + "Сейчас откроется окно выбора главного экрана.\n\n"
                    + "Выберите «Luch» и нажмите «Всегда».");
            b.setPositiveButton("Открыть выбор", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            });
            b.setNegativeButton("Позже", null);
        } else {
            b.setMessage(prefix + "Сейчас главный экран — «" + otherName + "», и он выбран «Всегда». "
                    + "Пока этот выбор стоит, Android не покажет окно выбора.\n\n"
                    + "1. Нажмите «Открыть " + otherName + "» → «Открывать по умолчанию» или "
                    + "«Очистить действия по умолчанию».\n"
                    + "2. Вернитесь сюда и снова нажмите шаг 1.\n\n"
                    + "Или с компьютера, одной командой:\n" + adbHint(mine, null));
            b.setPositiveButton("Открыть " + otherName, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + other)));
                }
            });
            b.setNegativeButton("Позже", null);
        }
        b.show();
    }

    private static final int REQ_HOME_ROLE = 9;

    /** Asks the system to give this app the home role. False if the system cannot ask. */
    private boolean requestHomeRole() {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_HOME)) return false;
            Intent ask = rm.createRequestRoleIntent(RoleManager.ROLE_HOME);
            if (!resolves(ask)) return false;
            startActivityForResult(ask, REQ_HOME_ROLE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_HOME_ROLE) return;
        build();
        ResolveInfo ri = currentHome();
        boolean done = ri != null && ri.activityInfo != null
                && ri.activityInfo.packageName.equals(getPackageName())
                && ri.activityInfo.name.equals(prefs.home().getClassName());
        if (done) {
            Toast.makeText(this, "Готово: Luch теперь главный экран", Toast.LENGTH_LONG).show();
        } else {
            // Declined, or the system remembered "don't ask again": fall back to the step-by-step way.
            new AlertDialog.Builder(this)
                    .setTitle("Главный экран не изменён")
                    .setMessage("Можно выбрать вручную в системных настройках главного экрана.")
                    .setPositiveButton("Открыть", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            Intent hs = new Intent(Settings.ACTION_HOME_SETTINGS);
                            if (resolves(hs)) open(hs);
                            else open(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        }
                    })
                    .setNegativeButton("Позже", null)
                    .show();
        }
    }

    private String adbHint(ComponentName mine, String disable) {
        String s = "adb shell cmd package set-home-activity " + mine.flattenToShortString();
        if (disable != null) s = "adb shell pm disable-user --user 0 " + disable + "\n" + s;
        return s;
    }

    private void pickAutostart() {
        final List<Apps.App> apps = Apps.load(this);
        final List<String> labels = new ArrayList<>();
        labels.add("Не запускать ничего");
        int checked = 0;
        String cur = prefs.autostart();
        for (int i = 0; i < apps.size(); i++) {
            labels.add(apps.get(i).label);
            if (apps.get(i).component.equals(cur)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle("Автозапуск при включении")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        prefs.setAutostart(which == 0 ? "" : apps.get(which - 1).component);
                        d.dismiss();
                        build();
                    }
                })
                .show();
    }

    private void pickHidden() {
        final List<Apps.App> apps = Apps.load(this);
        final Set<String> hidden = prefs.hidden();
        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
            checked[i] = hidden.contains(apps.get(i).component);
        }
        new AlertDialog.Builder(this)
                .setTitle("Отмеченные скрыты")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean isChecked) {
                        prefs.setHidden(apps.get(which).component, isChecked);
                        if (isChecked) prefs.removeFavorite(apps.get(which).component);
                    }
                })
                .setPositiveButton("Готово", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { build(); }
                })
                .show();
    }

    private void pickStock() {
        final List<ResolveInfo> homes = Apps.replaceableHomes(this);
        final Set<String> stock = prefs.replaceStock() ? prefs.stockLaunchers() : new HashSet<String>();
        String[] labels = new String[homes.size()];
        boolean[] checked = new boolean[homes.size()];
        for (int i = 0; i < homes.size(); i++) {
            String p = homes.get(i).activityInfo.packageName;
            labels[i] = Apps.label(this, p) + "\n" + p;
            checked[i] = stock.contains(p);
        }
        new AlertDialog.Builder(this)
                .setTitle("Заменять эти оболочки")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean isChecked) {
                        String p = homes.get(which).activityInfo.packageName;
                        if (isChecked) stock.add(p); else stock.remove(p);
                    }
                })
                .setPositiveButton("Готово", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.setStockLaunchers(new HashSet<>(stock));
                        prefs.setReplaceStock(!stock.isEmpty());
                        build();
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- state

    private boolean isHomeApp(String pkg) {
        for (ResolveInfo ri : Apps.homeApps(this)) {
            if (ri.activityInfo.packageName.equals(pkg)) return true;
        }
        return false;
    }

    private String stockSummary() {
        Set<String> s = prefs.stockLaunchers();
        if (s.isEmpty() || !prefs.replaceStock()) return "нет";
        StringBuilder sb = new StringBuilder();
        for (String p : s) {
            // The default list names launchers this device may not have.
            if (getPackageManager().getLaunchIntentForPackage(p) == null
                    && getPackageManager().getLeanbackLaunchIntentForPackage(p) == null && !isHomeApp(p)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(Apps.label(this, p));
        }
        return sb.length() == 0 ? "нет" : sb.toString();
    }

    private String defaultHomeSummary() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null || "android".equals(ri.activityInfo.packageName)) {
            return "не выбран";
        }
        String p = ri.activityInfo.packageName;
        if (!p.equals(getPackageName())) return Apps.label(this, p);
        return ri.activityInfo.name.endsWith("HomeEarlyActivity") ? "Luch (быстрая загрузка)" : "Luch";
    }

    private boolean serviceEnabled(String flattened) {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        String shortName = ComponentName.unflattenFromString(flattened).flattenToShortString();
        for (String part : s.split(":")) {
            if (part.equalsIgnoreCase(flattened) || part.equalsIgnoreCase(shortName)) return true;
        }
        return false;
    }

    private boolean serviceEnabledPkg(String pkg) {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(pkg + "/");
    }

    private String version() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ---------------------------------------------------------- accessibility

    private static final String WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS";
    /** Time for a screen to appear; a stub closes itself much sooner. */
    private static final long SCREEN_WAIT_MS = 2000;

    private boolean resumed;
    private boolean leftScreen;

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        leftScreen = true;
    }

    private void openAccessibility() {
        if (turnOnServiceDirectly()) {
            Toast.makeText(this, "Готово: кнопка «Домой» подключена", Toast.LENGTH_LONG).show();
            build();
            return;
        }
        tryScreen(accessibilityScreens(), 0);
    }

    /**
     * Only an ADB-granted permission lets an app switch its own service on:
     * adb shell pm grant com.local.tvlauncher android.permission.WRITE_SECURE_SETTINGS
     */
    private boolean turnOnServiceDirectly() {
        String mine = new ComponentName(this, KeyService.class).flattenToString();
        if (serviceEnabled(mine) || checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    on == null || on.isEmpty() ? mine : on + ":" + mine);
            Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Every screen that may show the accessibility settings. Android TV answers the standard
     * request with a stub that closes at once when the firmware lacks the real screen, so stubs
     * are skipped and well-known screens are tried by name as well.
     */
    private List<Intent> accessibilityScreens() {
        Set<String> names = new HashSet<>();
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0)) {
            if (!ri.activityInfo.packageName.contains("stubs")) {
                names.add(ri.activityInfo.packageName + "/" + ri.activityInfo.name);
            }
        }
        List<Intent> out = new ArrayList<>();
        for (String n : names) out.add(screen(n));
        for (String n : new String[]{
                "com.android.tv.settings/com.android.tv.settings.oemlink.AccessibilitySettingsActivity",
                "com.android.tv.settings/com.android.tv.settings.accessibility.AccessibilityActivity",
                "com.android.settings/com.android.settings.Settings$AccessibilitySettingsActivity"}) {
            if (names.add(n)) out.add(screen(n));
        }
        // Vendor firmwares often have a second settings app with its own screen: look inside them too.
        for (PackageInfo p : settingsApps()) {
            if (p.activities == null) continue;
            for (ActivityInfo a : p.activities) {
                String n = p.packageName + "/" + a.name;
                if (a.exported && a.enabled && a.name.toLowerCase().contains("accessib") && names.add(n)) {
                    out.add(screen(n));
                }
            }
        }
        return out;
    }

    /** Installed apps with "settings" in the package name, with their activities. */
    private List<PackageInfo> settingsApps() {
        List<PackageInfo> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        try {
            for (PackageInfo p : pm.getInstalledPackages(0)) {
                String name = p.packageName;
                if (name.toLowerCase().contains("setting") && !name.contains("stubs")) {
                    out.add(pm.getPackageInfo(name, PackageManager.GET_ACTIVITIES));
                }
            }
        } catch (Exception ignored) {
            // A firmware may refuse this list; the screens found so far are still tried.
        }
        return out;
    }

    /** Settings of the device. The standard request may land in a stub too, so a real app is preferred. */
    private void openDeviceSettings() {
        Intent standard = new Intent(Settings.ACTION_SETTINGS);
        ResolveInfo ri = getPackageManager().resolveActivity(standard, 0);
        if (ri != null && !ri.activityInfo.packageName.contains("stubs")) {
            open(standard);
            return;
        }
        PackageManager pm = getPackageManager();
        for (PackageInfo p : settingsApps()) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(p.packageName);
            if (i == null) i = pm.getLaunchIntentForPackage(p.packageName);
            if (i != null) {
                open(i);
                return;
            }
        }
        Toast.makeText(this, "Настройки устройства не найдены. Откройте их кнопкой на пульте", Toast.LENGTH_LONG).show();
    }

    private static Intent screen(String flattened) {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .setComponent(ComponentName.unflattenFromString(flattened));
    }

    /** With the ADB permission the service is removed at once; otherwise the user does it on the system screen. */
    private void turnOffService() {
        String mine = new ComponentName(this, KeyService.class).flattenToString();
        if (checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED) {
            try {
                String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                List<String> left = new ArrayList<>();
                if (on != null) {
                    for (String s : on.split(":")) {
                        ComponentName c = ComponentName.unflattenFromString(s);
                        if (c != null && !c.getPackageName().equals(getPackageName())) left.add(s);
                    }
                }
                Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        android.text.TextUtils.join(":", left));
                Toast.makeText(this, "Кнопка «Домой» отключена", Toast.LENGTH_LONG).show();
                build();
                return;
            } catch (Exception ignored) {
            }
        }
        tryScreen(accessibilityScreens(), 0);
    }

    /**
     * Back to another home screen. While the key service is on it would still bring Luch up on
     * the Home key, so that goes first.
     */
    private void restoreOtherHome() {
        if (prefs.serviceOn()) {
            new AlertDialog.Builder(this)
                    .setTitle("Сначала отключите кнопку «Домой»")
                    .setMessage("Пока она подключена, пульт будет открывать Luch. Выключите на системном экране «"
                            + getString(R.string.key_service_label)
                            + "», вернитесь сюда и нажмите «Другая оболочка» ещё раз.")
                    .setPositiveButton("Отключить", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { turnOffService(); }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        Intent chooser = new Intent(Settings.ACTION_HOME_SETTINGS);
        if (resolves(chooser)) {
            open(chooser);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Другая оболочка")
                .setMessage("Откроется страница приложения Luch. Выберите «Открывать по умолчанию» или "
                        + "«Очистить действия по умолчанию»: при следующем нажатии «Домой» система "
                        + "предложит выбрать оболочку.")
                .setPositiveButton("Открыть", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Starts screen {@code i}; if this screen is still in front a moment later, tries the next. */
    private void tryScreen(final List<Intent> screens, final int i) {
        if (i >= screens.size()) {
            accessibilityHelp();
            return;
        }
        leftScreen = false;
        if (!prefs.serviceOn()) prefs.setReturnAfterService(true);
        try {
            startActivity(screens.get(i));
        } catch (Exception e) {
            tryScreen(screens, i + 1);
            return;
        }
        list.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                if (resumed && !leftScreen) {
                    tryScreen(screens, i + 1);
                } else if (!resumed && !serviceEnabled(
                        new ComponentName(SettingsActivity.this, KeyService.class).flattenToString())) {
                    Toast.makeText(SettingsActivity.this, "Найдите в списке «"
                            + getString(R.string.key_service_label) + "» и включите", Toast.LENGTH_LONG).show();
                }
            }
        }, SCREEN_WAIT_MS);
    }

    /** The firmware opened nothing: simple steps first, the computer only as the last resort. */
    private void accessibilityHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Включите вручную")
                .setMessage("На этом устройстве нужный экран не открывается сам. Это просто:\n\n"
                        + "1. Откройте настройки устройства.\n"
                        + "2. Найдите «Специальные возможности». Обычно они в разделе "
                        + "«Настройки устройства» или «Система».\n"
                        + "3. Включите «" + getString(R.string.key_service_label) + "».")
                .setPositiveButton("Открыть настройки", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { openDeviceSettings(); }
                })
                .setNeutralButton("Такого пункта нет", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { computerHelp(); }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void computerHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Если пункта нет")
                .setMessage("Luch работает и без этого. Откройте его из списка приложений или сделайте "
                        + "главным экраном: шаг 1 в этих настройках.\n\n"
                        + "Для опытных: если на устройстве включён ADB, выполните один раз с компьютера\n"
                        + "adb shell pm grant com.local.tvlauncher " + WRITE_SECURE + "\n"
                        + "и снова нажмите шаг 2.")
                .setPositiveButton("Понятно", null)
                .show();
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Экран недоступен на этом устройстве", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- rows

    private interface Toggle {
        void set(boolean on);
    }

    private int rowCount;
    private LinearLayout rowsIn;
    private TextView lastTitle, lastValue;

    private static final String OPEN_GROUP = "open_group";
    private static final int GROUP_NONE = -1, GROUP_LOOK = 0, GROUP_HOME = 1, GROUP_SYSTEM = 2;
    /** Only one group is open at a time, so the list stays short on a remote. */
    private int openGroup = GROUP_NONE;
    private int focusGroup = GROUP_NONE;

    /** A group header: opens or closes the rows under it and keeps the focus on itself. */
    private void group(final int id, String title, String summary) {
        if (focusGroup == id) {
            focusIndex = rowCount;
            focusGroup = GROUP_NONE;
        }
        row(title, summary, openGroup == id ? "▾" : "›", Ui.TEXT_DIM, new Runnable() {
            public void run() {
                openGroup = openGroup == id ? GROUP_NONE : id;
                focusGroup = id;
                build();
            }
        });
        if (openGroup == id) {
            lastTitle.setTextColor(Ui.ACCENT);
            lastValue.setTextColor(Ui.ACCENT);
            lastValue.setTextSize(24);
        }
    }

    /** The rows of an open group sit under its header: indented, with an accent line on the left. */
    private void beginGroupRows() {
        LinearLayout nested = new LinearLayout(this);
        nested.setOrientation(LinearLayout.HORIZONTAL);
        View line = new View(this);
        line.setBackgroundColor(Ui.alpha(Ui.ACCENT, 0.7f));
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(Ui.dp(this, 3), -1);
        lineLp.setMargins(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        nested.addView(line, lineLp);
        rowsIn = new LinearLayout(this);
        rowsIn.setOrientation(LinearLayout.VERTICAL);
        nested.addView(rowsIn, new LinearLayout.LayoutParams(0, -2, 1));
        list.addView(nested, new LinearLayout.LayoutParams(-1, -2));
    }

    /** A setup step: "done" is shown in the accent colour. */
    private void step(String title, String summary, String value, boolean done, Runnable r) {
        row(title, summary, value, done ? Ui.ACCENT : Ui.TEXT, r);
    }

    private boolean isOurHomeDefault() {
        ResolveInfo ri = currentHome();
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName)
                && ri.activityInfo.name.equals(prefs.home().getClassName());
    }

    private void section(String name) {
        TextView t = Ui.caption(this, name);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 26), 0, Ui.dp(this, 8));
        list.addView(t);
    }

    private void note(String text) {
        TextView t = Ui.text(this, 14, Ui.TEXT_DIM, false);
        t.setText(text);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        list.addView(t);
    }

    private void toggle(String title, String summary, final boolean on, final Toggle t) {
        row(title, summary, on ? "Вкл" : "Выкл", on ? Ui.ACCENT : Ui.TEXT_DIM, new Runnable() {
            public void run() {
                t.set(!on);
                build();
            }
        });
    }

    private void action(String title, String summary, String value, Runnable r) {
        row(title, summary, value == null ? "›" : value, Ui.TEXT_DIM, r);
    }

    private void row(String title, String summary, String value, int valueColor, final Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setTag(rowCount++);
        row.setBackground(Ui.focusable(this, Ui.CARD, Ui.CARD_FOCUS, 12));
        row.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 14));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                r.run();
            }
        });

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, 18, Ui.TEXT, false);
        t.setText(title);
        lastTitle = t;
        texts.addView(t);
        if (summary != null) {
            TextView s = Ui.text(this, 13, Ui.TEXT_DIM, false);
            s.setText(summary);
            s.setPadding(0, Ui.dp(this, 3), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        TextView v = Ui.text(this, 17, valueColor, true);
        v.setText(value);
        lastValue = v;
        v.setPadding(Ui.dp(this, 20), 0, 0, 0);
        row.addView(v);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        rowsIn.addView(row, lp);
    }
}
