package eu.siacs.conversations.ui;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.databinding.DataBindingUtil;
import com.google.common.base.Strings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityZungviSignInBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.zungvi.ZungviAuth;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Signs in with a Zungvi account, which the API exchanges for XMPP credentials.
 *
 * <p>Deliberately a screen of its own rather than part of {@link WelcomeActivity}: the app is an
 * XMPP client first, so the welcome screen offers the provider-independent paths and only links
 * here. Everything on this screen is Zungvi specific and none of it is needed to use the app.
 */
public class ZungviSignInActivity extends XmppActivity {

    /** Sign-in is network bound and must not run on the main thread. */
    private static final ExecutorService AUTH_EXECUTOR = Executors.newSingleThreadExecutor();

    private ActivityZungviSignInBinding binding;

    /**
     * A sign-in that finished before the backend did.
     *
     * <p>{@link XmppActivity} binds the service asynchronously, and a sign-in round trip can
     * easily beat it on a warm connection. Holding the result and creating the account from
     * {@link #onBackendConnected()} means the user is not told to try again for a sign-in that
     * actually succeeded.
     */
    private ZungviAuth.Result pendingResult;

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {
        final ZungviAuth.Result pending = this.pendingResult;
        if (pending != null) {
            this.pendingResult = null;
            createAccountAndContinue(pending);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        if (getResources().getBoolean(R.bool.portrait_only)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_zungvi_sign_in);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar(), true);
        binding.signInZungvi.setOnClickListener(v -> signIn());
    }

    private void signIn() {
        final String email = Strings.nullToEmpty(binding.email.getText().toString()).trim();
        final String password = Strings.nullToEmpty(binding.password.getText().toString());
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.enter_email_and_password, Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        AUTH_EXECUTOR.execute(
                () -> {
                    final ZungviAuth.Result result = ZungviAuth.loginWithPassword(email, password);
                    runOnUiThread(() -> onAuthResult(result));
                });
    }

    private void onAuthResult(final ZungviAuth.Result result) {
        if (!result.isSuccess()) {
            setBusy(false);
            Toast.makeText(
                            this,
                            Strings.isNullOrEmpty(result.errorMessage)
                                    ? getString(R.string.account_status_unauthorized)
                                    : result.errorMessage,
                            Toast.LENGTH_LONG)
                    .show();
            return;
        }
        if (xmppConnectionService == null) {
            // Picked up by onBackendConnected rather than dropped; the screen stays busy until
            // then, because the sign-in itself did succeed.
            this.pendingResult = result;
            return;
        }
        createAccountAndContinue(result);
    }

    private void createAccountAndContinue(final ZungviAuth.Result result) {
        setBusy(false);
        // Reuse the account when this JID is already set up, so a second sign-in refreshes the
        // credential instead of creating a duplicate that fights the first one for the resource.
        Account account = xmppConnectionService.findAccountByJid(result.jid);
        if (account == null) {
            account = new Account(result.jid, result.password);
            account.setOption(Account.OPTION_DISABLED, false);
            account.setOption(Account.OPTION_MAGIC_CREATE, false);
            xmppConnectionService.createAccount(account);
        } else {
            account.setPassword(result.password);
            account.setOption(Account.OPTION_DISABLED, false);
            xmppConnectionService.updateAccount(account);
        }
        final Intent intent = new Intent(this, ConversationsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        addInviteUri(intent);
        startActivity(intent);
        finish();
    }

    private void setBusy(final boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.signInZungvi.setEnabled(!busy);
        binding.signInZungvi.setText(busy ? R.string.signing_in : R.string.continue_with_zungvi);
    }

    /** Carries an invite the user arrived with through to the conversation list. */
    private void addInviteUri(final Intent to) {
        final Intent from = getIntent();
        if (from != null && from.hasExtra(StartConversationActivity.EXTRA_INVITE_URI)) {
            to.putExtra(
                    StartConversationActivity.EXTRA_INVITE_URI,
                    from.getStringExtra(StartConversationActivity.EXTRA_INVITE_URI));
        }
    }
}
