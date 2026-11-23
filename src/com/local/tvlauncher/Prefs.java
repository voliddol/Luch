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
    private static final String UNFINISHED_HOME_STARTS = "unfinished_home_starts";
    static final String WHALE = "com.zeasn.whale.saas";

    private static final String FAVORITES = "favorites";
    private static final String HIDDEN = "hidden";
    private static final String AUTOSTART = "autostart";
    private static final String AUTOSTART_BOOT = "autostart_boot";
    private static final String AUTOSTART_BOOT_COUNT = "autostart_boot_count";
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
    private static final String HIDE_NAMES = "hide_app_names";
    private static final String TILE_SIZE = "tile_size";
    private static final String TWO_ROWS = "two_app_rows";
    private static final String TEMPERATURE = "show_led_temperature";
    private static final String VIDEO_URI = "wallpaper_video_uri";
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
    private static final String CRASH_BOOT = "crashes_boot";
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
        // A choice made in settings applies on the next boot, not on return to Home.
        String selected = component == null ? "" : component;
        if (selected.equals(autostart())) return;
        writeAutostartBoot(sp.edit().putString(AUTOSTART, selected));
        changed();
    }

    /** Prefer the system boot count so correcting the clock does not replay a launch. */
    boolean autostartHandled() {
        int boot = bootCount();
        int recorded = sp.getInt(AUTOSTART_BOOT_COUNT, -1);
        if (boot >= 0 && recorded >= 0) return recorded == boot;
        // Old installations only have this estimate. Keep it for devices hiding BOOT_COUNT.
        long last = sp.getLong(AUTOSTART_BOOT, 0);
        long bootStart = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return last != 0 && Math.abs(bootStart - last) < 60_000;
    }

    void markAutostartHandled() {
        writeAutostartBoot(sp.edit());
    }

    private void writeAutostartBoot(SharedPreferences.Editor edit) {
        edit.putInt(AUTOSTART_BOOT_COUNT, bootCount())
                .putLong(AUTOSTART_BOOT, System.currentTimeMillis() - SystemClock.elapsedRealtime()).commit();
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
        changed();
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
    static final String[] TILE_SIZES = {"6 в ряд", "5 в ряд", "4 в ряд"};

    boolean hideNames() { return sp.getBoolean(HIDE_NAMES, false); }
    void setHideNames(boolean value) { sp.edit().putBoolean(HIDE_NAMES, value).apply(); changed(); }

    int tileSize() { return Math.max(0, Math.min(2, sp.getInt(TILE_SIZE, 0))); }
    int tilesPerRow() { return 6 - tileSize(); }
    void setTileSize(int value) { sp.edit().putInt(TILE_SIZE, value).apply(); changed(); }

    boolean twoRows() { return sp.getBoolean(TWO_ROWS, false); }
    void setTwoRows(boolean value) { sp.edit().putBoolean(TWO_ROWS, value).apply(); changed(); }

    boolean showTemperature() { return sp.getBoolean(TEMPERATURE, false); }
    void setShowTemperature(boolean value) { sp.edit().putBoolean(TEMPERATURE, value).apply(); changed(); }

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

    static final String[] IDLE_MODES = {"Часы на чёрном", "Слайд-шоу из галереи", "Луч ищет часы"};
    static final int IDLE_CLOCK = 0;
    static final int IDLE_SLIDES = 1;
    static final int IDLE_SEARCH = 2;

    int idleMode() {
        return sp.getInt(IDLE_MODE, IDLE_CLOCK);
    }

    void setIdleMode(int m) {
        sp.edit().putInt(IDLE_MODE, m).apply();
        changed();
    }

    // Projector beam look; indexes into the lists in {@link Ambient}.
    static final int DEFAULT_BEAM_BRIGHT = 1, DEFAULT_BEAM_FROM = 0, DEFAULT_BEAM_COLOR = 0;
    static final int DEFAULT_BEAM_WIDTH = 1, DEFAULT_BEAM_DUST = 2;
    static final boolean DEFAULT_FLICKER = true;

    int beamBright() {
        return sp.getInt(BEAM_BRIGHT, DEFAULT_BEAM_BRIGHT);
    }

    int beamFrom() {
        return sp.getInt(BEAM_FROM, DEFAULT_BEAM_FROM);
    }

    int beamColor() {
        return sp.getInt(BEAM_COLOR, DEFAULT_BEAM_COLOR);
    }

    /** Faint shutter flicker of a film projector in the beam. */
    boolean flicker() {
        return sp.getBoolean(FLICKER, DEFAULT_FLICKER);
    }

    /** Index into {@link Ambient#WIDTHS}. */
    int beamWidth() {
        return sp.getInt(BEAM_WIDTH, DEFAULT_BEAM_WIDTH);
    }

    void setBeamWidth(int width) {
        sp.edit().putInt(BEAM_WIDTH, width).putLong(BACKGROUND_STAMP, System.currentTimeMillis()).apply();
        changed();
    }

    /** Index into {@link Ambient#DUST}. */
    int beamDust() {
        return sp.getInt(BEAM_DUST, DEFAULT_BEAM_DUST);
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

    String videoUri() { return sp.getString(VIDEO_URI, ""); }

    void setVideo(String uri) {
        sp.edit().putString(VIDEO_URI, uri).putInt(BACKGROUND, Wallpaper.VIDEO)
                .putLong(BACKGROUND_STAMP, System.currentTimeMillis()).apply();
        changed();
    }

    static int version() {
        return version;
    }

    static void changed() {
        version++;
    }

    // --------------------------------------------------------------- crashes

    private int bootCount() {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (SecurityException denied) {
            return -1;
        }
    }

    /** Old APKs saved uptime alone. Similar uptimes on separate days must not disable HOME. */
    private List<Long> recentCrashes(int boot, long now) {
        List<Long> recent = new ArrayList<>();
        if (sp.getInt(CRASH_BOOT, -2) != boot) return recent;
        for (String s : sp.getString(CRASHES, "").split(",")) {
            if (s.isEmpty()) continue;
            try {
                long t = Long.parseLong(s);
                if (t <= now && now - t < CRASH_WINDOW_MS) recent.add(t);
            } catch (NumberFormatException ignored) {
                // Ignore an invalid entry without losing the rest of the crash history.
            }
        }
        return recent;
    }

    /** Synchronous: the process is about to die. Use wall time only if boot count is unavailable. */
    void recordCrash() {
        int boot = bootCount();
        long now = boot >= 0 ? SystemClock.elapsedRealtime() : System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (long t : recentCrashes(boot, now)) sb.append(t).append(',');
        sb.append(now);
        sp.edit().putInt(CRASH_BOOT, boot).putString(CRASHES, sb.toString()).commit();
    }

    boolean tooManyCrashes() {
        int boot = bootCount();
        long now = boot >= 0 ? SystemClock.elapsedRealtime() : System.currentTimeMillis();
        return recentCrashes(boot, now).size() >= CRASH_LIMIT;
    }

    void clearCrashes() {
        sp.edit().remove(CRASHES).remove(CRASH_BOOT).remove(UNFINISHED_HOME_STARTS).commit();
    }

    /** Survives process death even when Android cannot invoke a Java exception handler. */
    boolean beginHomeStart() {
        int previous = sp.getInt(UNFINISHED_HOME_STARTS, 0);
        if (previous >= CRASH_LIMIT) return false;
        sp.edit().putInt(UNFINISHED_HOME_STARTS, previous + 1).commit();
        return true;
    }

    void completeHomeStart() {
        sp.edit().remove(UNFINISHED_HOME_STARTS).commit();
    }

    /** Recovery mode also makes the key service and autostart passive. */
    boolean selfDisabled() {
        return sp.getBoolean(SELF_DISABLED, false);
    }

    void setSelfDisabled(boolean off) {
        PackageManager pm = ctx.getPackageManager();
        int no = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        // Persist passive mode before changing HOME. On manual restore, clear it last.
        if (off) sp.edit().putBoolean(SELF_DISABLED, true).putBoolean(RETURN_AFTER_SERVICE, false).commit();
        boolean keepRegular = !off || !hasOtherHome();
        pm.setComponentEnabledSetting(regular(), keepRegular ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : no,
                PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(early(), no, PackageManager.DONT_KILL_APP);
        if (!off) sp.edit().putBoolean(SELF_DISABLED, false).commit();
    }

    boolean hasOtherHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        // The normal stock launcher may become launchable only after user storage unlocks.
        int flags = PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(home, flags)) {
            if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
            if (ri.activityInfo.permission != null && ctx.checkSelfPermission(ri.activityInfo.permission)
                    != PackageManager.PERMISSION_GRANTED) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || pkg.equals("android") || pkg.contains("setupwizard")
                    || pkg.equals("com.android.provision")) continue;
            if (!ri.activityInfo.name.endsWith(".FallbackHome") && (ri.activityInfo.targetActivity == null
                    || !ri.activityInfo.targetActivity.endsWith(".FallbackHome"))) return true;
        }
        return false;
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
        return ri != null && ri.activityInfo != null && home().equals(
                new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
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
