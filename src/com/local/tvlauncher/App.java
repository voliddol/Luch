package com.local.tvlauncher;

import android.app.Application;
import android.util.Log;

/**
 * Record Java crashes in device-protected storage and reconcile recovery on process start.
 */
public class App extends Application {
    private static final String TAG = "TvLauncher";
    volatile HomeActivity startingHome;

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler system = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    Prefs prefs = new Prefs(App.this);
                    prefs.recordCrash();
                    if (startingHome != null || prefs.tooManyCrashes()) {
                        prefs.setSelfDisabled(true);
                    }
                } catch (Throwable ignored) {
                }
                if (system != null) system.uncaughtException(t, e);
            }
        });

        try {
            Prefs prefs = new Prefs(this);
            if (prefs.selfDisabled() || prefs.tooManyCrashes()) {
                prefs.setSelfDisabled(true);
                prefs.clearCrashes();
            }
        } catch (Throwable e) {
            Log.w(TAG, "crash guard", e);
        }
    }
}
