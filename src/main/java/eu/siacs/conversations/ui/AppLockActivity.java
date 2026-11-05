package eu.siacs.conversations.ui;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.util.AppLockManager;

public class AppLockActivity extends BaseActivity {

    private static final int REQUEST_CONFIRM_DEVICE_CREDENTIAL = 1;

    private Button unlockButton;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_lock);
        this.unlockButton = findViewById(R.id.unlock);
        this.unlockButton.setOnClickListener(view -> requestDeviceCredential());
        getOnBackPressedDispatcher()
                .addCallback(
                        this,
                        new OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                moveTaskToBack(true);
                            }
                        });
        if (savedInstanceState == null) {
            requestDeviceCredential();
        }
    }

    @SuppressWarnings("deprecation")
    private void requestDeviceCredential() {
        final KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
        if (keyguardManager == null || !keyguardManager.isDeviceSecure()) {
            AppLockManager.disableBecauseNoDeviceCredential(this);
            Toast.makeText(this, R.string.app_lock_disabled_no_device_credential, Toast.LENGTH_LONG)
                    .show();
            finish();
            return;
        }
        final Intent intent =
                keyguardManager.createConfirmDeviceCredentialIntent(
                        getString(R.string.app_lock_auth_title),
                        getString(R.string.app_lock_auth_description));
        if (intent == null) {
            AppLockManager.disableBecauseNoDeviceCredential(this);
            Toast.makeText(this, R.string.app_lock_disabled_no_device_credential, Toast.LENGTH_LONG)
                    .show();
            finish();
            return;
        }
        this.unlockButton.setEnabled(false);
        try {
            startActivityForResult(intent, REQUEST_CONFIRM_DEVICE_CREDENTIAL);
        } catch (final RuntimeException e) {
            AppLockManager.authenticationPromptFinished();
            this.unlockButton.setEnabled(true);
            Toast.makeText(this, R.string.app_lock_auth_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(
            final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CONFIRM_DEVICE_CREDENTIAL) {
            return;
        }
        if (resultCode == RESULT_OK) {
            AppLockManager.authenticated();
            finish();
        } else {
            AppLockManager.authenticationPromptFinished();
            this.unlockButton.setEnabled(true);
            Toast.makeText(this, R.string.app_lock_auth_cancelled, Toast.LENGTH_SHORT).show();
        }
    }
}
