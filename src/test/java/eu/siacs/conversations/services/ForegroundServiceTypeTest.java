package eu.siacs.conversations.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.pm.ServiceInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * Play requires every foreground service type the app announces to match what it actually does.
 * The camera is the one that used to be claimed without being used — it was picked purely because
 * the CAMERA permission happened to be granted, for a notification that only keeps an XMPP
 * connection alive.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 24)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ForegroundServiceTypeTest {

    @Test
    public void anOngoingCallClaimsTheMicrophone() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                ForegroundServiceType.select(true, true, false));
    }

    /** The doze allow list is the expected default for the plain connection notification. */
    @Test
    public void theDozeAllowListWinsOverTheFallbacks() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                ForegroundServiceType.select(false, true, true));
    }

    /** A call still claims the microphone even when the app is also doze exempt. */
    @Test
    public void aCallOutranksTheDozeAllowList() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                ForegroundServiceType.select(true, true, true));
    }

    @Test
    public void withoutAnythingElseItIsSpecialUse() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                ForegroundServiceType.select(false, false, false));
    }

    /** A call with the microphone permission refused must not claim a type it cannot back up. */
    @Test
    public void aCallWithoutTheMicrophonePermissionDoesNotClaimIt() {
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                ForegroundServiceType.select(true, false, false));
    }

    /** The regression this class exists for: the camera is never announced. */
    @Test
    public void theCameraIsNeverClaimed() {
        for (final boolean call : new boolean[] {false, true}) {
            for (final boolean microphone : new boolean[] {false, true}) {
                for (final boolean doze : new boolean[] {false, true}) {
                    assertNotEquals(
                            "camera claimed for call="
                                    + call
                                    + " microphone="
                                    + microphone
                                    + " doze="
                                    + doze,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                            ForegroundServiceType.select(call, microphone, doze));
                }
            }
        }
    }
}
