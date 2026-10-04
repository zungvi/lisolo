package eu.siacs.conversations.zungvi;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import android.content.pm.PackageManager;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import eu.siacs.conversations.Config;
import java.util.concurrent.Executors;

/**
 * Thin wrapper over Credential Manager for obtaining a Google ID token.
 *
 * <p>Only the token is produced here. It is the Zungvi API that decides what it means: the token
 * is posted to {@code /auth-google}, which verifies its signature and audience server side and
 * answers with the account. Nothing in the app treats a Google response as proof of identity on
 * its own, because a client has no way to check one.
 */
public final class GoogleSignIn {

    private static final String PLAY_SERVICES_PACKAGE = "com.google.android.gms";

    public interface Callback {
        void onIdToken(@NonNull String idToken);

        void onCancelled();

        void onFailure(@Nullable String message);
    }

    private GoogleSignIn() {}

    /**
     * Whether a Google sign-in could succeed on this device.
     *
     * <p>Credential Manager delegates to Play Services for Google accounts, so on a build or
     * device without it the button should not be offered at all rather than failing when tapped.
     */
    public static boolean isAvailable(final Context context) {
        // Checked through the package manager rather than GoogleApiAvailability so the app does
        // not have to depend on play-services-base just to decide whether to show a button.
        try {
            context.getPackageManager().getPackageInfo(PLAY_SERVICES_PACKAGE, 0);
            return true;
        } catch (final PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static void requestIdToken(
            @NonNull final Activity activity,
            @NonNull final String serverClientId,
            @NonNull final Callback callback) {
        // filterByAuthorizedAccounts=false so a first-time user is offered their Google accounts
        // rather than an empty sheet; autoSelect stays off so switching account is always possible.
        final GetGoogleIdOption option =
                new GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(serverClientId)
                        .setAutoSelectEnabled(false)
                        .build();
        final GetCredentialRequest request =
                new GetCredentialRequest.Builder().addCredentialOption(option).build();

        CredentialManager.create(activity)
                .getCredentialAsync(
                        activity,
                        request,
                        new CancellationSignal(),
                        Executors.newSingleThreadExecutor(),
                        new CredentialManagerCallback<
                                GetCredentialResponse, GetCredentialException>() {
                            @Override
                            public void onResult(final GetCredentialResponse response) {
                                final var credential = response.getCredential();
                                final var data = credential.getData();
                                if (!GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(
                                        credential.getType())) {
                                    callback.onFailure(null);
                                    return;
                                }
                                try {
                                    final GoogleIdTokenCredential googleCredential =
                                            GoogleIdTokenCredential.createFrom(data);
                                    callback.onIdToken(googleCredential.getIdToken());
                                } catch (final Exception e) {
                                    Log.d(Config.LOGTAG, "could not read Google id token", e);
                                    callback.onFailure(null);
                                }
                            }

                            @Override
                            public void onError(@NonNull final GetCredentialException e) {
                                if (e instanceof GetCredentialCancellationException) {
                                    callback.onCancelled();
                                    return;
                                }
                                Log.d(Config.LOGTAG, "Google sign-in failed", e);
                                callback.onFailure(e.getMessage());
                            }
                        });
    }
}
