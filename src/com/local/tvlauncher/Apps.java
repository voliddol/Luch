package com.local.tvlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Installed apps that can be launched from a TV launcher. */
final class Apps {
    /** Pseudo component of the HDMI input tile. */
    static final String HDMI = "hdmi";

    static final class App {
        final String component;   // flattened ComponentName, or HDMI
        final String label;
        final Drawable banner;    // 16:9 TV banner, may be null
        final Drawable icon;
        final boolean leanback;

        App(String component, String label, Drawable banner, Drawable icon, boolean leanback) {
            this.component = component;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
            this.leanback = leanback;
        }

        String pkg() {
            int slash = component.indexOf('/');
            return slash > 0 ? component.substring(0, slash) : component;
        }
    }

    private Apps() {
    }

    /** Last loaded list; survives the home screen being recreated, so it can show tiles at once. */
    private static volatile List<App> cache;

    static List<App> cached() {
        return cache;
    }

    /** Same apps with the same names, in the same order: no need to rebuild the screen. */
    static boolean same(List<App> a, List<App> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).component.equals(b.get(i).component) || !a.get(i).label.equals(b.get(i).label)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Just the given components (the favorites), which is much faster than the full list.
     * Used on a cold start so the dock appears before all banners are loaded.
     */
    static List<App> loadSome(Context ctx, List<String> components) {
        PackageManager pm = ctx.getPackageManager();
        List<App> out = new ArrayList<>();
        for (String c : components) {
            ComponentName cn = ComponentName.unflattenFromString(c);
            if (cn == null) continue;
            try {
                ActivityInfo ai = pm.getActivityInfo(cn, 0);
                Drawable banner = null;
                try {
                    banner = ai.loadBanner(pm);
                } catch (Exception ignored) {
                }
                boolean leanback = pm.getLeanbackLaunchIntentForPackage(cn.getPackageName()) != null;
                out.add(new App(c, String.valueOf(ai.loadLabel(pm)).trim(), banner, ai.loadIcon(pm), leanback));
            } catch (Exception ignored) {
                // Uninstalled since; the full load will drop it.
            }
        }
        return out;
    }

    /** All launchable apps except this launcher, sorted by name. TV (leanback) entries win. */
    static List<App> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        collect(ctx, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, byPkg);
        collect(ctx, pm, Intent.CATEGORY_LAUNCHER, false, byPkg);

        List<App> list = new ArrayList<>(byPkg.values());
        final Collator collator = Collator.getInstance(new Locale("ru"));
        Collections.sort(list, new Comparator<App>() {
            @Override public int compare(App a, App b) {
                return collator.compare(a.label, b.label);
            }
        });
        cache = list;
        return list;
    }

    private static void collect(Context ctx, PackageManager pm, String category, boolean leanback,
                                Map<String, App> byPkg) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        int flags = PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        for (ResolveInfo ri : pm.queryIntentActivities(i, flags)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(ctx.getPackageName())) continue;
            if (byPkg.containsKey(ai.packageName)) continue;
            String label = String.valueOf(ri.loadLabel(pm)).trim();
            Drawable banner = null;
            try {
                banner = ai.loadBanner(pm);
            } catch (Exception ignored) {
            }
            Drawable icon;
            try {
                icon = ri.loadIcon(pm);
            } catch (Exception e) {
                icon = pm.getDefaultActivityIcon();
            }
            String comp = new ComponentName(ai.packageName, ai.name).flattenToShortString();
            byPkg.put(ai.packageName, new App(comp, label, banner, icon, leanback));
        }
    }

    /** The HDMI tile, or null if this device has no known way to switch to HDMI. */
    static App hdmi(Context ctx) {
        if (hdmiIntent(ctx) == null && sourcePickerIntent(ctx) == null) return null;
        return new App(HDMI, "HDMI", null, null, true);
    }

    static boolean launch(Context ctx, App app) {
        return launch(ctx, app.component);
    }

    static boolean launch(Context ctx, String component) {
        Intent intent;
        if (HDMI.equals(component)) {
            return launchHdmi(ctx);
        }
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) return false;
        intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        boolean ok = start(ctx, intent, true);
        if (ok) new Prefs(ctx).countLaunch(component);
        return ok;
    }

    /**
     * Switches the projector to HDMI the same way the stock source app does
     * (com.hisilicon.tvui with SourceName 9). Falls back to the stock source picker.
     */
    private static boolean launchHdmi(Context ctx) {
        Intent direct = hdmiIntent(ctx);
        if (direct != null && start(ctx, direct, false)) return true;
        Intent picker = sourcePickerIntent(ctx);
        if (picker != null && start(ctx, picker, false)) return true;
        Toast.makeText(ctx, "Не удалось переключиться на HDMI", Toast.LENGTH_LONG).show();
        return false;
    }

    private static Intent hdmiIntent(Context ctx) {
        Intent i = new Intent()
                .setClassName("com.hisilicon.tvui", "com.hisilicon.tvui.MainActivity")
                .putExtra("SourceName", 9)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        return resolves(ctx, i) ? i : null;
    }

    private static Intent sourcePickerIntent(Context ctx) {
        Intent i = new Intent("com.aimov.action.HDMI_IN")
                .setPackage("com.coolux.source")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return resolves(ctx, i) ? i : null;
    }

    private static boolean resolves(Context ctx, Intent i) {
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(i, 0);
        return ri != null && ri.activityInfo != null && ri.activityInfo.exported;
    }

    private static boolean start(Context ctx, Intent intent, boolean toast) {
        try {
            ctx.startActivity(intent);
            return true;
        } catch (Exception e) {
            if (toast) Toast.makeText(ctx, "Не удалось запустить: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** Package names that provide a HOME screen, except this launcher. */
    static List<ResolveInfo> homeApps(Context ctx) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        int flags = PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        List<ResolveInfo> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(i, flags)) {
            String p = ri.activityInfo.packageName;
            if (p.equals(ctx.getPackageName()) || !seen.add(p)) continue;
            out.add(ri);
        }
        return out;
    }

    static String label(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static String labelOfComponent(Context ctx, String component) {
        if (component == null || component.isEmpty()) return "";
        if (HDMI.equals(component)) return "HDMI";
        ComponentName cn = ComponentName.unflattenFromString(component);
        return cn == null ? component : label(ctx, cn.getPackageName());
    }
}
