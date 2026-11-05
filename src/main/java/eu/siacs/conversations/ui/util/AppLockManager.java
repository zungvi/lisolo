package eu.siacs.conversations.ui.util;

import android.content.Context;
import eu.siacs.conversations.AppSettings;

public final class AppLockManager {

    private static boolean authenticated;
    private static boolean authenticationActivityStarted;

    private AppLockManager() {}

    public static synchronized boolean isEnabled(final Context context) {
        return new AppSettings(context).isAppLockEnabled();
    }

    public static synchronized boolean needsAuthentication(final Context context) {
        return isEnabled(context) && !authenticated;
    }

    public static synchronized boolean beginAuthentication(final Context context) {
        if (!needsAuthentication(context) || authenticationActivityStarted) {
            return false;
        }
        authenticationActivityStarted = true;
        return true;
    }

    public static synchronized void authenticationPromptFinished() {
        authenticationActivityStarted = false;
    }

    public static synchronized void authenticated() {
        authenticationActivityStarted = false;
        authenticated = true;
    }

    public static synchronized void onAppBackground(final Context context) {
        if (isEnabled(context)) {
            authenticated = false;
            authenticationActivityStarted = false;
        }
    }

    public static synchronized void onSettingChanged() {
        authenticated = true;
        authenticationActivityStarted = false;
    }

    public static synchronized void disableBecauseNoDeviceCredential(final Context context) {
        new AppSettings(context).setAppLockEnabled(false);
        authenticated = true;
        authenticationActivityStarted = false;
    }
}
