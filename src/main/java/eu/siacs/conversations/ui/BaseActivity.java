package eu.siacs.conversations.ui;

import android.content.Intent;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.ui.util.AppLockManager;
import eu.siacs.conversations.ui.util.SettingsUtils;

public abstract class BaseActivity extends AppCompatActivity {
    private Boolean isDynamicColors;

    @Override
    public void onStart() {
        super.onStart();
        final var appSettings = new AppSettings(this);
        final int desiredNightMode = appSettings.getDesiredNightMode();
        if (setDesiredNightMode(desiredNightMode)) {
            return;
        }
        final boolean isDynamicColors = appSettings.isDynamicColorsDesired();
        setDynamicColors(isDynamicColors);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SettingsUtils.applyScreenshotSetting(this);
        if (AppLockManager.isEnabled(this)) {
            if (!(this instanceof AppLockActivity)
                    && AppLockManager.beginAuthentication(this)) {
                try {
                    startActivity(
                            new Intent(this, AppLockActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
                } catch (final RuntimeException e) {
                    AppLockManager.authenticationPromptFinished();
                    Log.e(Config.LOGTAG, "could not start app lock activity", e);
                    moveTaskToBack(true);
                }
            }
        }
    }

    public void setDynamicColors(final boolean isDynamicColors) {
        if (this.isDynamicColors == null) {
            this.isDynamicColors = isDynamicColors;
        } else {
            if (this.isDynamicColors != isDynamicColors) {
                Log.i(
                        "Recreating {} because dynamic color setting has changed",
                        getClass().getSimpleName());
                recreate();
            }
        }
    }

    public boolean setDesiredNightMode(final int desiredNightMode) {
        if (desiredNightMode == AppCompatDelegate.getDefaultNightMode()) {
            return false;
        }
        AppCompatDelegate.setDefaultNightMode(desiredNightMode);
        Log.i("Recreating {} because desired night mode has changed", getClass().getSimpleName());
        recreate();
        return true;
    }
}
