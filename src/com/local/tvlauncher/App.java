package com.local.tvlauncher;

import android.app.Application;
import android.util.Log;

/**
 * Crash guard. Runs before any activity or service, also before the user is unlocked.
 * Every crash is recorded; if the early home screen keeps crashing, early boot is switched
 * off so the firmware falls back to its own launcher instead of a crash loop at power-on.
 */
public class App extends Application {
    private static final String TAG = "TvLauncher";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler system = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    new Prefs(App.this).recordCrash();
                } catch (Throwable ignored) {
                }
                if (system != null) system.uncaughtException(t, e);
            }
        });

        try {
            Prefs prefs = new Prefs(this);
            if (prefs.tooManyCrashes()) {
                if (prefs.earlyBoot()) {
                    // Step 1: back to the regular home screen, which starts after unlock.
                    Log.w(TAG, "early home keeps crashing, switching early boot off");
                    prefs.setEarlyBoot(false);
                    prefs.setEarlyBootAutoOff(true);
                } else if (!prefs.selfDisabled()) {
                    // Step 2: still crashing; stop being a home screen at all and let the
                    // firmware (or Projectivy) take over. Settings can turn it back on.
                    Log.w(TAG, "home keeps crashing, launcher steps aside");
                    prefs.setSelfDisabled(true);
                }
                prefs.clearCrashes();
            }
        } catch (Throwable e) {
            Log.w(TAG, "crash guard", e);
        }
    }
}
