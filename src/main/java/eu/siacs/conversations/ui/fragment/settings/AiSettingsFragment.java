package eu.siacs.conversations.ui.fragment.settings;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.preference.SwitchPreferenceCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;

public class AiSettingsFragment extends XmppPreferenceFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_ai, rootKey);
        final SwitchPreferenceCompat enabled = findPreference(AppSettings.AI_ENABLED);
        if (enabled == null) {
            throw new IllegalStateException(
                    "The preference resource file is missing some preferences");
        }
        enabled.setOnPreferenceChangeListener(
                (preference, newValue) -> {
                    if (Boolean.TRUE.equals(newValue)) {
                        // Switching this on starts sending decrypted message text off the device,
                        // so it asks first rather than taking a stray tap as consent. Turning it
                        // back off needs no confirmation.
                        askToEnable(enabled);
                        return false;
                    }
                    return true;
                });
    }

    private void askToEnable(final SwitchPreferenceCompat enabled) {
        final var builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setTitle(R.string.ai_consent_title);
        builder.setMessage(R.string.ai_consent_message);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(
                R.string.ai_consent_confirm, (dialog, which) -> enabled.setChecked(true));
        builder.create().show();
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_title_ai);
    }
}
