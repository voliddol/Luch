package com.local.tvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    /** More replacements than this within the window means a ping-pong: stop replacing until reboot. */
    private static final int REPLACE_LIMIT = 5;
    private static final long REPLACE_WINDOW_MS = 30_000;

    private Prefs prefs;
    private String lastWindowPkg = "";
    private long lastReplace;
    private final long[] replaceTimes = new long[REPLACE_LIMIT];
    private int replaceCount;
    private boolean replaceGaveUp;

    private final BroadcastReceiver unlocked = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // The regular home activity can only start after unlock; retry now if a vendor launcher is up.
            if (prefs.replaceStock() && prefs.stockLaunchers().contains(lastWindowPkg)) {
                lastReplace = 0;
                openHome();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        prefs = new Prefs(this);
        registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(unlocked);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (prefs == null || event.getKeyCode() != KeyEvent.KEYCODE_HOME || !prefs.interceptHome()
                || prefs.selfDisabled()) {
            return false;
        }
        // Swallow both down and up so the system Home handling never runs; act on release.
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openHome();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        lastWindowPkg = p.toString();
        if (!prefs.replaceStock() || prefs.selfDisabled() || !prefs.stockLaunchers().contains(lastWindowPkg)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastReplace < REPLACE_PAUSE_MS || !allowReplace(now)) return;
        lastReplace = now;
        openHome();
    }

    /** Ring buffer of the last replacements; gives up after REPLACE_LIMIT of them in REPLACE_WINDOW_MS. */
    private boolean allowReplace(long now) {
        if (replaceGaveUp) return false;
        int slot = replaceCount % REPLACE_LIMIT;
        if (replaceCount >= REPLACE_LIMIT && now - replaceTimes[slot] < REPLACE_WINDOW_MS) {
            replaceGaveUp = true;
            Toast.makeText(this, "Luch: оболочка производителя открывается снова и снова, "
                    + "замена приостановлена до перезагрузки", Toast.LENGTH_LONG).show();
            return false;
        }
        replaceTimes[slot] = now;
        replaceCount++;
        return true;
    }

    @Override
    public void onInterrupt() {
    }

    private void openHome() {
        if (prefs.selfDisabled()) return;
        boolean early = prefs.earlyBoot();
        UserManager um = (UserManager) getSystemService(USER_SERVICE);
        // Before unlock only the directBootAware home activity can run.
        if (!early && um != null && !um.isUserUnlocked()) return;
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(prefs.home())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(home);
        } catch (Exception e) {
            // Never crash the service: without it the Home key would fall back to the system.
            Log.w(TAG, "cannot open home", e);
        }
    }
}
