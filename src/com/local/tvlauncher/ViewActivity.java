package com.local.tvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Opens the home screen from the app list, whichever of the two home activities is enabled. */
public class ViewActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Prefs prefs = new Prefs(this);
        // After the crash guard switched the home screen off, only the settings can bring it back.
        Intent i = prefs.selfDisabled() ? new Intent(this, SettingsActivity.class)
                : new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(prefs.home());
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}