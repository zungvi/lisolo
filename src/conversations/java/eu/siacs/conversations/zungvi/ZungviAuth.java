package eu.siacs.conversations.zungvi;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.common.base.Strings;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.xmpp.Jid;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Talks to the Zungvi accounts API to turn a Zungvi or Google login into XMPP credentials.
 *
 * <p>Zungvi is the source of truth for identity: Prosody authenticates through mod_auth_zungvi,
 * so the JID and the credential both have to come from here rather than being invented locally.
 *
 * <p>The two endpoints answer in different shapes, which this class flattens into {@link Result}:
 *
 * <ul>
 *   <li>{@code /auth-login} puts the identity at the top level and returns no password, because a
 *       password login authenticates against the Zungvi account password - the same secret the
 *       user just typed, which is what gets handed back for XMPP.
 *   <li>{@code /auth-google} nests it under {@code user} and does return a password, because a
 *       Google account has no usable account password; the API mints one for XMPP specifically.
 * </ul>
 */
public final class ZungviAuth {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT =
            new OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();

    private ZungviAuth() {}

    /** What the caller needs to create an account, or the reason it cannot. */
    public static final class Result {
        @Nullable public final Jid jid;
        @Nullable public final String password;
        @Nullable public final String errorMessage;
        public final boolean requiresTwoFactor;

        private Result(
                @Nullable final Jid jid,
                @Nullable final String password,
                @Nullable final String errorMessage,
                final boolean requiresTwoFactor) {
            this.jid = jid;
            this.password = password;
            this.errorMessage = errorMessage;
            this.requiresTwoFactor = requiresTwoFactor;
        }

        public boolean isSuccess() {
            return this.jid != null && !Strings.isNullOrEmpty(this.password);
        }

        static Result success(final Jid jid, final String password) {
            return new Result(jid, password, null, false);
        }

        static Result failure(final String message) {
            return new Result(null, null, message, false);
        }

        static Result twoFactorRequired(final String message) {
            return new Result(null, null, message, true);
        }
    }

    /**
     * Signs in with a Zungvi email and password.
     *
     * <p>The password is deliberately reused as the XMPP credential rather than minting a new
     * one: mod_auth_zungvi verifies against the Zungvi account password, so this is what Prosody
     * will accept, and it keeps the existing login behaviour untouched.
     */
    public static Result loginWithPassword(final String email, final String password) {
        final RequestBody body =
                new FormBody.Builder().add("email", email).add("password", password).build();
        final Request request =
                new Request.Builder().url(baseUrl() + "/auth-login").post(body).build();
        final JSONObject json = execute(request);
        if (json == null) {
            return Result.failure("Could not reach Zungvi. Check your connection and try again.");
        }
        if (json.optBoolean("requires_2fa", false)) {
            return Result.twoFactorRequired(
                    "This account uses two-factor authentication, which this app cannot complete"
                            + " yet. Sign in with Google, or use another XMPP account.");
        }
        if (!json.optBoolean("resp", false)) {
            return Result.failure(messageOr(json, "Wrong email or password."));
        }
        // /auth-login reports the identity at the top level.
        final Jid jid = parseJid(json.optJSONObject("xmpp"));
        if (jid == null) {
            return Result.failure("This Zungvi account has no chat address yet.");
        }
        return Result.success(jid, password);
    }

    /** Exchanges a Google ID token for XMPP credentials via Zungvi. */
    public static Result loginWithGoogle(final String idToken) {
        final JSONObject payload = new JSONObject();
        try {
            payload.put("credential", idToken);
        } catch (final JSONException e) {
            return Result.failure("Could not prepare the Google sign-in request.");
        }
        final Request request =
                new Request.Builder()
                        .url(baseUrl() + "/auth-google")
                        .post(RequestBody.create(payload.toString(), JSON))
                        .build();
        final JSONObject json = execute(request);
        if (json == null) {
            return Result.failure("Could not reach Zungvi. Check your connection and try again.");
        }
        if (!json.optBoolean("resp", false)) {
            return Result.failure(messageOr(json, "Google sign-in was refused."));
        }
        // /auth-google nests the identity under "user".
        final JSONObject user = json.optJSONObject("user");
        final JSONObject xmpp = user == null ? null : user.optJSONObject("xmpp");
        final Jid jid = parseJid(xmpp);
        if (jid == null) {
            return Result.failure("This Zungvi account has no chat address yet.");
        }
        final String password = xmpp.optString("password", null);
        if (Strings.isNullOrEmpty(password)) {
            // The server provisioned a JID but no credential, so there is nothing to log in
            // with. Saying so plainly beats creating an account that can never connect.
            return Result.failure(
                    "Zungvi did not return chat credentials for this account. Please try again.");
        }
        return Result.success(jid, password);
    }

    @Nullable
    private static Jid parseJid(@Nullable final JSONObject xmpp) {
        if (xmpp == null) {
            return null;
        }
        final String bare = xmpp.optString("bare_jid", xmpp.optString("jid", null));
        if (Strings.isNullOrEmpty(bare)) {
            return null;
        }
        try {
            return Jid.of(bare).asBareJid();
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    private static JSONObject execute(@NonNull final Request request) {
        try (final Response response = CLIENT.newCall(request).execute()) {
            final ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            // Parsed even on a non-2xx status: the API reports its own failures in the body with
            // resp=false, and that message is more useful to show than the status code.
            return new JSONObject(body.string());
        } catch (final IOException | JSONException e) {
            android.util.Log.d(
                    eu.siacs.conversations.Config.LOGTAG,
                    "ZUNGVIAUTH request to " + request.url() + " failed",
                    e);
            return null;
        }
    }

    private static String messageOr(final JSONObject json, final String fallback) {
        final String message = json.optString("message", null);
        return Strings.isNullOrEmpty(message) ? fallback : message;
    }

    private static String baseUrl() {
        final String configured = BuildConfig.ZUNGVI_API_BASE_URL;
        return configured.endsWith("/")
                ? configured.substring(0, configured.length() - 1)
                : configured;
    }
}
