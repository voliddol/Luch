package com.local.tvlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
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
        Ui.apply(prefs.theme());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(this, 120), Ui.dp(this, 32), Ui.dp(this, 120), Ui.dp(this, 48));
        scroll.addView(list);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        View f = getCurrentFocus();
        if (f != null && f.getTag() instanceof Integer) focusIndex = (Integer) f.getTag();
        list.removeAllViews();
        rowCount = 0;

        TextView title = Ui.light(this, 34, Ui.TEXT);
        title.setText("Настройки лаунчера");
        title.setPadding(0, 0, 0, Ui.dp(this, 8));
        list.addView(title);

        if (prefs.selfDisabled()) {
            note("⚠ Лаунчер несколько раз упал и сам отключился как главный экран, чтобы не мешать "
                    + "работе проектора. Перехват кнопки «Домой» тоже приостановлен.");
            action("Вернуть лаунчер", "Снова сделать его главным экраном (без раннего старта)", null,
                    new Runnable() { public void run() {
                        prefs.setSelfDisabled(false);
                        prefs.clearCrashes();
                        build();
                        makeDefault(false);
                    } });
        }

        // ---------------------------------------------------------------- look
        section("Оформление");
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

        // ------------------------------------------------------------- Home key
        section("Кнопка «Домой»");
        boolean service = serviceEnabled(new ComponentName(this, KeyService.class).flattenToString());
        toggle("Перехват кнопки «Домой»",
                service ? "Служба спецвозможностей включена"
                        : "Служба выключена: включите «" + getString(R.string.key_service_label)
                                + "» в спецвозможностях (строка ниже)",
                prefs.interceptHome(), new Toggle() {
                    public void set(boolean on) { prefs.setInterceptHome(on); }
                });
        action("Открыть спецвозможности", "Здесь включается и выключается служба перехвата", null,
                new Runnable() { public void run() {
                    open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } });
        if (serviceEnabledPkg(PROJECTIVY)) {
            note("⚠ Служба Projectivy тоже перехватывает кнопки. Пока она включена, кнопка «Домой» "
                    + "может открывать то один лаунчер, то другой. Выключите её в спецвозможностях.");
        }

        // ------------------------------------------------------ vendor launcher
        section("Оболочка производителя");
        toggle("Сразу заменять оболочку производителя",
                "Если появится оболочка производителя (например, WHALE), сразу открыть этот лаунчер",
                prefs.replaceStock(), new Toggle() {
                    public void set(boolean on) { prefs.setReplaceStock(on); }
                });
        action("Какие оболочки заменять", stockSummary(), null, new Runnable() {
            public void run() { pickStock(); }
        });

        // ------------------------------------------------------------- startup
        section("Включение проектора");
        if (prefs.earlyBootAutoOff()) {
            note("⚠ Ранний старт был выключен автоматически: лаунчер несколько раз упал при загрузке. "
                    + "Проектор вернулся к обычному поведению прошивки.");
        }
        toggle("Ранний старт (directBootAware)",
                "Лаунчер показывается в первые секунды загрузки вместо оболочки производителя. "
                        + "Если он упадёт при загрузке 3 раза, режим выключится сам",
                prefs.earlyBoot(), new Toggle() {
                    public void set(boolean on) {
                        if (on) confirmEarlyBoot(); else switchEarlyBoot(false);
                    }
                });
        action("Сделать главным экраном", "Сейчас главный экран: " + defaultHomeSummary()
                        + ". Нужно один раз, чтобы ранний старт срабатывал при включении", null, new Runnable() {
            public void run() { makeDefault(false); }
        });
        String auto = prefs.autostart();
        action("Автозапуск при включении",
                "Это приложение откроется само сразу после включения проектора",
                auto.isEmpty() ? "нет" : Apps.labelOfComponent(this, auto), new Runnable() {
                    public void run() { pickAutostart(); }
                });

        // --------------------------------------------------------- home screen
        section("Главный экран");
        toggle("Плитка HDMI", "Плитка в «Избранном», переключающая проектор на вход HDMI",
                prefs.hdmiTile(), new Toggle() {
                    public void set(boolean on) { prefs.setHdmiTile(on); }
                });
        int hidden = prefs.hidden().size();
        action("Скрытые приложения", "Вернуть скрытые приложения в список",
                hidden == 0 ? "нет" : String.valueOf(hidden), new Runnable() {
                    public void run() { pickHidden(); }
                });

        // --------------------------------------------------------------- other
        section("Прочее");
        action("Открыть другой лаунчер", "Например, Projectivy или оболочку производителя", null,
                new Runnable() { public void run() { pickOtherLauncher(); } });
        action("Системные настройки", null, null, new Runnable() {
            public void run() { open(new Intent(Settings.ACTION_SETTINGS)); }
        });
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
                .setTitle("Включить ранний старт?")
                .setMessage("При включении проектора сразу будет показан этот лаунчер, а оболочка "
                        + "производителя (WHALE) запускаться не будет: ни её экрана, ни её рекламы.\n\n"
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
        makeDefault(true);
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
                    + "2. Вернитесь сюда и снова нажмите «Сделать главным экраном».\n\n"
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
        final List<ResolveInfo> homes = Apps.homeApps(this);
        final Set<String> stock = prefs.stockLaunchers();
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
                        build();
                    }
                })
                .show();
    }

    private void pickOtherLauncher() {
        final List<ResolveInfo> homes = Apps.homeApps(this);
        if (homes.isEmpty()) {
            Toast.makeText(this, "Других лаунчеров нет", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[homes.size()];
        for (int i = 0; i < homes.size(); i++) labels[i] = Apps.label(this, homes.get(i).activityInfo.packageName);
        new AlertDialog.Builder(this)
                .setTitle("Открыть лаунчер")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        ResolveInfo ri = homes.get(which);
                        open(new Intent(Intent.ACTION_MAIN)
                                .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- state

    private String stockSummary() {
        Set<String> s = prefs.stockLaunchers();
        if (s.isEmpty()) return "ни одной";
        StringBuilder sb = new StringBuilder();
        for (String p : s) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Apps.label(this, p));
        }
        return sb.toString();
    }

    private String defaultHomeSummary() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null || "android".equals(ri.activityInfo.packageName)) {
            return "не выбран";
        }
        String p = ri.activityInfo.packageName;
        if (!p.equals(getPackageName())) return Apps.label(this, p);
        return ri.activityInfo.name.endsWith("HomeEarlyActivity") ? "Luch (ранний)" : "Luch";
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
        v.setPadding(Ui.dp(this, 20), 0, 0, 0);
        row.addView(v);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        list.addView(row, lp);
    }
}
