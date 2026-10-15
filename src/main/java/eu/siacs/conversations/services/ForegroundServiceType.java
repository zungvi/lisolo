package eu.siacs.conversations.services;

import android.content.pm.ServiceInfo;
import android.os.Build;

/**
 * Picks the foreground service type {@link XmppConnectionService} announces on Android 14 and
 * later.
 *
 * <p>Only the microphone is ever claimed for what it actually is: an ongoing call. The connection
 * notification has no matching type of its own, so it falls back to the doze exemption when there
 * is one and to {@code specialUse} (subtype {@code xmpp-im}, declared in the manifest) otherwise.
 *
 * <p>It deliberately never claims {@code camera}. The service does not run the camera in the
 * background, and announcing a type the app does not use is a Play policy violation on top of
 * being untrue.
 */
public final class ForegroundServiceType {

    private ForegroundServiceType() {}

    /**
     * @param requireMicrophone whether a call is in progress, so the microphone is genuinely in use
     * @param microphonePermission whether {@code RECORD_AUDIO} has been granted
     * @param ignoringBatteryOptimizations whether the app sits on the doze allow list
     */
    public static int select(
            final boolean requireMicrophone,
            final boolean microphonePermission,
            final boolean ignoringBatteryOptimizations) {
        if (requireMicrophone && microphonePermission) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (ignoringBatteryOptimizations) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;
        }
        if (microphonePermission) {
            return ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }
}
