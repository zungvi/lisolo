package eu.siacs.conversations.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import eu.siacs.conversations.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;

/**
 * The about text used to spell the product name out by hand, so it kept the old name after the app
 * was renamed. It now takes the name from app_name, which the build generates per flavour.
 */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AboutMessageTest {

    @Test
    public void aboutMessageNamesTheAppAfterAppName() {
        final Context context = RuntimeEnvironment.getApplication();
        final String appName = context.getString(R.string.app_name);

        final String about = context.getString(R.string.pref_about_message, appName);

        assertTrue(
                "about text should open with the app name, but was: "
                        + about.trim().substring(0, Math.min(80, about.trim().length())),
                about.trim().startsWith(appName));
    }

    @Test
    public void aboutMessageHasNoHardCodedProductName() {
        final Context context = RuntimeEnvironment.getApplication();
        final String appName = context.getString(R.string.app_name);

        // The raw resource, with the placeholder left unfilled: whatever product names survive here
        // are hard coded and will go stale the next time the app is renamed.
        final String raw = context.getResources().getString(R.string.pref_about_message);

        for (final String staleName : new String[] {"Zungvi Messenger", "Lisolo", "Quicksy"}) {
            if (staleName.equals(appName)) {
                continue;
            }
            assertFalse(
                    "about text hard codes the product name '" + staleName + "'",
                    raw.contains(staleName));
        }
    }
}
