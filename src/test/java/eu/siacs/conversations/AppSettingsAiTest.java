package eu.siacs.conversations;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.preference.PreferenceManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;

/**
 * AI has to stay off until asked for, and the master switch has to actually gate the individual
 * features rather than only hiding their rows.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AppSettingsAiTest {

    @Test
    public void everythingIsOffUntilTheUserOptsIn() {
        final var settings = new AppSettings(context());

        assertFalse(settings.isAiEnabled());
        assertFalse(settings.isAiTranslation());
        assertFalse(settings.isAiCatchUp());
        assertFalse(settings.isAiScamTriage());
    }

    @Test
    public void turningTheMasterSwitchOnEnablesTheDefaultSet() {
        set(AppSettings.AI_ENABLED, true);
        final var settings = new AppSettings(context());

        assertTrue(settings.isAiEnabled());
        assertTrue(settings.isAiTranslation());
        assertTrue(settings.isAiCatchUp());
        assertTrue(settings.isAiScamTriage());
    }

    /** The regression that matters: a stale per-feature true must not survive the master switch. */
    @Test
    public void masterSwitchOffOverridesFeaturesLeftOn() {
        set(AppSettings.AI_ENABLED, false);
        set(AppSettings.AI_TRANSLATION, true);
        set(AppSettings.AI_CATCH_UP, true);
        set(AppSettings.AI_SCAM_TRIAGE, true);
        final var settings = new AppSettings(context());

        assertFalse(settings.isAiTranslation());
        assertFalse(settings.isAiCatchUp());
        assertFalse(settings.isAiScamTriage());
    }

    @Test
    public void featuresCanBeTurnedOffIndividuallyWhileAiStaysOn() {
        set(AppSettings.AI_ENABLED, true);
        set(AppSettings.AI_CATCH_UP, false);
        final var settings = new AppSettings(context());

        assertTrue(settings.isAiEnabled());
        assertTrue(settings.isAiTranslation());
        assertFalse(settings.isAiCatchUp());
    }

    private static void set(final String key, final boolean value) {
        PreferenceManager.getDefaultSharedPreferences(context())
                .edit()
                .putBoolean(key, value)
                .commit();
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }
}
