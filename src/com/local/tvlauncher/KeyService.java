package com.local.tvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Accessibility service, the same trick Projectivy uses:
 * - the Home key on the remote always opens this launcher;
 * - when a vendor launcher window appears (e.g. WHALE at boot), this launcher replaces it.
 * Runs in direct boot too, so it can react before the user storage is unlocked.
 */
public class KeyService extends AccessibilityService {
    private static final String TAG = "TvLauncher";
    /** Do not fight a vendor launcher in a tight loop if starting ours keeps failing. */
    private static final long REPLACE_PAUSE_MS = 1500;
    /** Repeated launcher-to-launcher transitions can be a firmware ping-pong. */
    private static final int REPLACE_LIMIT = 5;
    private static final long REPLACE_WINDOW_MS = 30_000;

    private Prefs prefs;
    private String lastWindowPkg = "";
    private String lastWindowClass = "";
    private boolean takingHomePress;
    private long lastReplace;
    private final long[] replaceTimes = new long[REPLACE_LIMIT];
    private int replaceCount;
    private long replacePausedUntil;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable replaceIfStillStock = new Runnable() {
        @Override public void run() {
            if (!canOpenHome() || !prefs.replaceStock() || !prefs.stockLaunchers().contains(lastWindowPkg)
                    || !isStockHomeWindow()) return;
            long now = SystemClock.elapsedRealtime();
            if (!allowReplace(now)) return;
            lastReplace = now;
            openHome();
        }
    };

    private final BroadcastReceiver unlocked = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // The regular home activity can only start after unlock; retry now if a vendor launcher is up.
            handler.removeCallbacks(replaceIfStillStock);
            replaceIfStillStock.run();
        }
    };

    @Override
    protected void onServiceConnected() {
        prefs = new Prefs(this);
        registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        if (prefs.returnAfterService()) {
            prefs.setReturnAfterService(false);
            Toast.makeText(this, "Готово: кнопка «Домой» подключена", Toast.LENGTH_LONG).show();
            openHome();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(replaceIfStillStock);
        try {
            unregisterReceiver(unlocked);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                takingHomePress = canOpenHome() && prefs.interceptHome() && !inSystemChooser()
                        && homeIntent().resolveActivityInfo(getPackageManager(), 0) != null;
            }
            return takingHomePress;
        }
        if (event.getAction() != KeyEvent.ACTION_UP || !takingHomePress) return false;
        takingHomePress = false;
        if (!event.isCanceled() && !openHome()) {
            // The chosen component may have become unavailable between key down and key up.
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "system home unavailable", unavailable);
            }
        }
        return true;
    }

    private boolean inSystemChooser() {
        if ("android".equals(lastWindowPkg) || "com.android.intentresolver".equals(lastWindowPkg)
                || "com.google.android.intentresolver".equals(lastWindowPkg)) {
            return lastWindowClass.endsWith(".ResolverActivity") || lastWindowClass.endsWith(".ChooserActivity");
        }
        boolean roleController = "com.android.permissioncontroller".equals(lastWindowPkg)
                || "com.google.android.permissioncontroller".equals(lastWindowPkg);
        return roleController && (lastWindowClass.endsWith(".RequestRoleActivity")
                || lastWindowClass.endsWith(".HomeSettingsActivity") || lastWindowClass.endsWith(".DefaultAppActivity"));
    }

    private boolean isStockHomeWindow() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(lastWindowPkg);
        for (ResolveInfo candidate : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)) {
            ActivityInfo info = candidate.activityInfo;
            if (info != null && (lastWindowClass.equals(info.name) || lastWindowClass.equals(info.targetActivity))) return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        CharSequence cls = event.getClassName();
        String windowClass = cls == null ? "" : cls.toString();
        if (p == null || lastWindowPkg.contentEquals(p) && lastWindowClass.equals(windowClass)) return;
        handler.removeCallbacks(replaceIfStillStock);
        lastWindowPkg = p.toString();
        lastWindowClass = windowClass;
        if (!prefs.replaceStock() || !prefs.stockLaunchers().contains(lastWindowPkg)) return;
        long now = SystemClock.elapsedRealtime();
        long delay = lastReplace == 0 ? 0 : lastReplace + REPLACE_PAUSE_MS - now;
        if (delay > 0) handler.postDelayed(replaceIfStillStock, delay);
        else replaceIfStillStock.run();
    }

    /** After a burst, wait for a new stock window after the cooldown. Never retry on a timer alone. */
    private boolean allowReplace(long now) {
        if (now < replacePausedUntil) return false;
        int slot = replaceCount % REPLACE_LIMIT;
        if (replaceCount >= REPLACE_LIMIT && now - replaceTimes[slot] < REPLACE_WINDOW_MS) {
            replacePausedUntil = now + REPLACE_WINDOW_MS;
            replaceCount = 0;
            Toast.makeText(this, "Luch: оболочка производителя открывается снова и снова, "
                    + "замена приостановлена на 30 секунд", Toast.LENGTH_LONG).show();
            return false;
        }
        replaceTimes[slot] = now;
        replaceCount++;
        return true;
    }

    @Override
    public void onInterrupt() {
    }

    private boolean canOpenHome() {
        if (prefs == null || prefs.selfDisabled()) return false;
        UserManager um = (UserManager) getSystemService(USER_SERVICE);
        return prefs.earlyBoot() || um == null || um.isUserUnlocked();
    }

    private Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(prefs.home())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    private boolean openHome() {
        if (!canOpenHome()) return false;
        try {
            startActivity(homeIntent());
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot open home", e);
            return false;
        }
    }
}
