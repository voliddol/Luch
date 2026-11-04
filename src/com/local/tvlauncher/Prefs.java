package com.local.tvlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings live in device-protected storage, so the early home screen and the key service
 * can read them before the user is unlocked.
 */
final class Prefs {
    static final String WHALE = "com.zeasn.whale.saas";

    private static final String FAVORITES = "favorites";
    private static final String HIDDEN = "hidden";
    private static final String AUTOSTART = "autostart";
    private static final String AUTOSTART_BOOT = "autostart_boot";
    private static final String HDMI = "hdmi";
    private static final String INTERCEPT_HOME = "intercept_home";
    private static final String REPLACE_STOCK = "replace_stock";
    private static final String STOCK = "stock";
    private static final String IDLE = "idle_minutes";
    private static final String IDLE_MODE = "idle_mode";
    private static final String KEN_BURNS = "ken_burns";
    private static final String GRAIN = "grain";
    private static final String ANIM_FPS = "anim_fps";
    private static final String MARGIN = "safe_margin";
    private static final String BEAM_BRIGHT = "beam_bright";
    private static final String BEAM_FROM = "beam_from";
    private static final String BEAM_COLOR = "beam_color";
    private static final String FLICKER = "flicker";
    private static final String BEAM_DUST = "beam_dust";
    private static final String BEAM_WIDTH = "beam_width";
    private static final String START_TAB = "start_tab";
    private static final String RETURN_AFTER_SERVICE = "return_after_service";
    private static final String SETUP_DONE = "setup_done";
    private static final String STEPS_HIDDEN = "steps_hidden";
    private static final String BACKGROUND = "background";
    private static final String BACKGROUND_STAMP = "background_stamp";
    private static final String THEME = "theme";
    private static final String CRASHES = "crashes";
    private static final String EARLY_AUTO_OFF = "early_auto_off";
    private static final String SELF_DISABLED = "self_disabled";

    /** Crashes within this window that switch early boot off. */
    private static final int CRASH_LIMIT = 3;
    private static final long CRASH_WINDOW_MS = 3 * 60_000;

    /** Bumped on every change that affects the home screen, so it rebuilds only when needed. */
    private static volatile int version;

    private final Context ctx;
    private final SharedPreferences sp;

    Prefs(Context context) {
        ctx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        sp = ctx.createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- favorites

    /** Ordered list of flattened component names. */
    List<String> favorites() {
        String s = sp.getString(FAVORITES, "");
        List<String> out = new ArrayList<>();
        for (String c : s.split(",")) if (!c.isEmpty()) out.add(c);
        return out;
    }

    void setFavorites(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String c : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        sp.edit().putString(FAVORITES, sb.toString()).apply();
        changed();
    }

    boolean isFavorite(String component) {
        return favorites().contains(component);
    }

    void addFavorite(String component) {
        List<String> f = favorites();
        if (!f.contains(component)) f.add(component);
        setFavorites(f);
    }

    void removeFavorite(String component) {
        List<String> f = favorites();
        f.remove(component);
        setFavorites(f);
    }

    /** Moves a favorite one step left (-1) or right (+1). Returns its new index. */
    int moveFavorite(String component, int delta) {
        List<String> f = favorites();
        int i = f.indexOf(component);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= f.size()) return i;
        f.remove(i);
        f.add(j, component);
        setFavorites(f);
        return j;
    }

    // ---------------------------------------------------------------- hidden

    Set<String> hidden() {
        return new HashSet<>(sp.getStringSet(HIDDEN, new HashSet<String>()));
    }

    void setHidden(String component, boolean hide) {
        Set<String> h = hidden();
        if (hide) h.add(component); else h.remove(component);
        sp.edit().putStringSet(HIDDEN, h).apply();
        changed();
    }

    // ------------------------------------------------------------- autostart

    String autostart() {
        return sp.getString(AUTOSTART, "");
    }

    void setAutostart(String component) {
        sp.edit().putString(AUTOSTART, component == null ? "" : component).apply();
    }

    /**
     * True once per boot: the first caller after a boot gets true, later callers false.
     * A boot is identified by its start time, which stays stable within a few seconds.
     */
    boolean claimAutostartForThisBoot() {
        long bootStart = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        long last = sp.getLong(AUTOSTART_BOOT, 0);
        if (Math.abs(bootStart - last) < 60_000) return false;
        sp.edit().putLong(AUTOSTART_BOOT, bootStart).apply();
        return true;
    }

    // --------------------------------------------------------------- toggles

    boolean hdmiTile() {
        return sp.getBoolean(HDMI, true);
    }

    void setHdmiTile(boolean on) {
        sp.edit().putBoolean(HDMI, on).apply();
        changed();
    }

    boolean interceptHome() {
        return sp.getBoolean(INTERCEPT_HOME, true);
    }

    void setInterceptHome(boolean on) {
        sp.edit().putBoolean(INTERCEPT_HOME, on).apply();
    }

    boolean replaceStock() {
        return sp.getBoolean(REPLACE_STOCK, true);
    }

    void setReplaceStock(boolean on) {
        sp.edit().putBoolean(REPLACE_STOCK, on).apply();
    }

    /** Vendor launchers that get replaced by ours as soon as their window shows up. */
    Set<String> stockLaunchers() {
        Set<String> def = new HashSet<>(Arrays.asList(WHALE));
        return new HashSet<>(sp.getStringSet(STOCK, def));
    }

    void setStockLaunchers(Set<String> pkgs) {
        sp.edit().putStringSet(STOCK, pkgs).apply();
    }

    int theme() {
        return sp.getInt(THEME, 0);
    }

    void setTheme(int theme) {
        sp.edit().putInt(THEME, theme).apply();
        changed();
    }

    // ------------------------------------------------------------ usage

    /** Counts launches from this launcher, for "часто используемые". Kept in its own file. */
    void countLaunch(String component) {
        SharedPreferences u = usage();
        u.edit().putInt(component, u.getInt(component, 0) + 1).apply();
    }

    int launches(String component) {
        return usage().getInt(component, 0);
    }

    private SharedPreferences usage() {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences("usage", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- comfort

    static final int[] IDLE_MINUTES = {0, 1, 3, 5, 10};
    static final int[] MARGINS = {0, 2, 4, 6};

    /** Minutes without a key press before the menu dims to a clock; 0 = never. */
    int idleMinutes() {
        return sp.getInt(IDLE, 3);
    }

    void setIdleMinutes(int m) {
        sp.edit().putInt(IDLE, m).apply();
        changed();
    }

    /** Extra margin around the menu, in percent of the screen, for projectors that crop edges. */
    int safeMargin() {
        return sp.getInt(MARGIN, 0);
    }

    void setSafeMargin(int percent) {
        sp.edit().putInt(MARGIN, percent).apply();
        changed();
    }

    /** Slow zoom and pan over the user's picture (Ken Burns). */
    boolean kenBurns() {
        return sp.getBoolean(KEN_BURNS, true);
    }

    void setKenBurns(boolean on) {
        sp.edit().putBoolean(KEN_BURNS, on).putLong(BACKGROUND_STAMP, System.currentTimeMillis()).apply();
        changed();
    }

    static final int[] FPS = {12, 24};

    /** Frames per second of the background animations: 12 is half the load of 24. */
    int animFps() {
        return sp.getInt(ANIM_FPS, 12);
    }

    void setAnimFps(int fps) {
        sp.edit().putInt(ANIM_FPS, fps).putLong(BACKGROUND_STAMP, System.currentTimeMillis()).apply();
        changed();
    }

    /** Film grain over any background. */
    boolean grain() {
        return sp.getBoolean(GRAIN, false);
    }

    void setGrain(boolean on) {
        sp.edit().putBoolean(GRAIN, on).putLong(BACKGROUND_STAMP, System.currentTimeMillis()).apply();
        changed();
    }

    static final String[] IDLE_MODES = {"Часы на чёрном", "Слайд-шоу из галереи"};
    static final int IDLE_CLOCK = 0;
    static final int IDLE_SLIDES = 1;

    int idleMode() {
        return sp.getInt(IDLE_MODE, IDLE_CLOCK);
    }

    void setIdleMode(int m) {
        sp.edit().putInt(IDLE_MODE, m).apply();
        changed();
    }

    // Projector beam look; indexes into the lists in {@link Ambient}.
    int beamBright() {
        return sp.getInt(BEAM_BRIGHT, 1);
    }

    int beamFrom() {
        return sp.getInt(BEAM_FROM, 0);
    }

    int beamColor() {
        return sp.getInt(BEAM_COLOR, 0);
    }

    /** Faint shutter flicker of a film projector in the beam. */
    boolean flicker() {
        return sp.getBoolean(FLICKER, true);
    }

    /** Index into {@link Ambient#WIDTHS}. */
    int beamWidth() {
        return sp.getInt(BEAM_WIDTH, 1);
    }

    void setBeamWidth(int width) {
        sp.edit().putInt(BEAM_WIDTH, width).putLong(BACKGROUND_STAMP, System.currentTimeMillis()).apply();
        changed();
    }

    /** Index into {@link Ambient#DUST}. */
    int beamDust() {
        return sp.getInt(BEAM_DUST, 2);
    }

    void setBeamDust(int amount) {
        sp.edit().putInt(BEAM_DUST, amount).putLong(BACKGROUND_STAMP, System.currentTimeMillis()).apply();
        changed();
    }

    /** What the Home key opens; the order matches the tabs of the home screen. */
    static final String[] START_TABS = {"Главная", "Приложения"};

    int startTab() {
        return sp.getInt(START_TAB, 0);
    }

    void setStartTab(int tab) {
        sp.edit().putInt(START_TAB, tab).apply();
    }

    void setBeam(int bright, int from, int color, boolean flicker) {
        sp.edit().putInt(BEAM_BRIGHT, bright).putInt(BEAM_FROM, from).putInt(BEAM_COLOR, color)
                .putBoolean(FLICKER, flicker).putLong(BACKGROUND_STAMP, System.currentTimeMillis()).apply();
        changed();
    }

    /** One of the {@link Wallpaper} modes. The projector beam is the signature look. */
    int background() {
        return sp.getInt(BACKGROUND, Wallpaper.BEAM);
    }

    void setBackground(int mode) {
        sp.edit().putInt(BACKGROUND, mode).putLong(BACKGROUND_STAMP, System.currentTimeMillis()).apply();
        changed();
    }

    /** Changes whenever the background mode or picture changes, so the home screen redraws it. */
    long backgroundStamp() {
        return sp.getLong(BACKGROUND_STAMP, 0) + background();
    }

    static int version() {
        return version;
    }

    static void changed() {
        version++;
    }

    // --------------------------------------------------------------- crashes

    /** Synchronous: the process is about to die. Uses the uptime clock, which restarts at boot. */
    void recordCrash() {
        long now = SystemClock.elapsedRealtime();
        StringBuilder sb = new StringBuilder();
        for (String s : sp.getString(CRASHES, "").split(",")) {
            if (s.isEmpty()) continue;
            long t = Long.parseLong(s);
            if (t <= now && now - t < CRASH_WINDOW_MS) sb.append(t).append(',');
        }
        sb.append(now);
        sp.edit().putString(CRASHES, sb.toString()).commit();
    }

    boolean tooManyCrashes() {
        long now = SystemClock.elapsedRealtime();
        int n = 0;
        for (String s : sp.getString(CRASHES, "").split(",")) {
            if (s.isEmpty()) continue;
            long t = Long.parseLong(s);
            if (t <= now && now - t < CRASH_WINDOW_MS) n++;
        }
        return n >= CRASH_LIMIT;
    }

    void clearCrashes() {
        sp.edit().remove(CRASHES).commit();
    }

    /**
     * The crash guard took this launcher out of the home role: both home activities are disabled
     * and the key service stays passive. Turning it back on restores the regular home activity.
     */
    boolean selfDisabled() {
        return sp.getBoolean(SELF_DISABLED, false);
    }

    void setSelfDisabled(boolean off) {
        sp.edit().putBoolean(SELF_DISABLED, off).commit();
        PackageManager pm = ctx.getPackageManager();
        int no = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (off) {
            pm.setComponentEnabledSetting(regular(), no, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(early(), no, PackageManager.DONT_KILL_APP);
        } else {
            pm.setComponentEnabledSetting(regular(), PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(early(), no, PackageManager.DONT_KILL_APP);
        }
    }

    /** Early boot was switched off by the crash guard; shown once in settings. */
    boolean earlyBootAutoOff() {
        return sp.getBoolean(EARLY_AUTO_OFF, false);
    }

    void setEarlyBootAutoOff(boolean on) {
        sp.edit().putBoolean(EARLY_AUTO_OFF, on).commit();
    }

    // ----------------------------------------------------------------- setup

    /** The system's default home screen is this launcher. */
    boolean isDefaultHome() {
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && ctx.getPackageName().equals(ri.activityInfo.packageName);
    }

    boolean serviceOn() {
        String s = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(ctx.getPackageName() + "/");
    }

    /** False while the user is only looking around: the system still opens another launcher on Home. */
    boolean isSetUp() {
        return isDefaultHome() || serviceOn();
    }

    /** The setup steps were completed once; they stay hidden until this is no longer the home screen. */
    boolean setupDone() {
        return sp.getBoolean(SETUP_DONE, false);
    }

    void setSetupDone(boolean done) {
        sp.edit().putBoolean(SETUP_DONE, done).apply();
    }

    /** The user hid the setup steps by hand; they stay hidden until asked for again. */
    boolean stepsHidden() {
        return sp.getBoolean(STEPS_HIDDEN, false);
    }

    void setStepsHidden(boolean hidden) {
        sp.edit().putBoolean(STEPS_HIDDEN, hidden).apply();
    }

    /** The key service brings this launcher back as soon as the user has switched it on. */
    boolean returnAfterService() {
        return sp.getBoolean(RETURN_AFTER_SERVICE, false);
    }

    void setReturnAfterService(boolean on) {
        sp.edit().putBoolean(RETURN_AFTER_SERVICE, on).commit();
    }

    // ------------------------------------------------------------ early boot

    /** The early (directBootAware) home activity is used instead of the regular one. */
    boolean earlyBoot() {
        return ctx.getPackageManager().getComponentEnabledSetting(early())
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    /** Enables exactly one of the two home activities. */
    void setEarlyBoot(boolean on) {
        PackageManager pm = ctx.getPackageManager();
        int yes = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        int no = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        // Enable the new one first so there is never a moment without a home activity.
        pm.setComponentEnabledSetting(on ? early() : regular(), yes, PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(on ? regular() : early(), no, PackageManager.DONT_KILL_APP);
    }

    /** The home activity that is currently enabled. */
    ComponentName home() {
        return earlyBoot() ? early() : regular();
    }

    ComponentName regular() {
        return new ComponentName(ctx, HomeActivity.class);
    }

    ComponentName early() {
        return new ComponentName(ctx, HomeEarlyActivity.class);
    }
}
