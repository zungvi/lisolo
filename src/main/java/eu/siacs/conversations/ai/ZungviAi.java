package eu.siacs.conversations.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.common.base.Strings;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.Conversations;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Calls the Zungvi AI completion API, which speaks the OpenAI chat-completions dialect.
 *
 * <p>Everything sent here leaves the device and is readable by the server, so callers must check
 * the matching opt-in in {@link eu.siacs.conversations.AppSettings} first. Nothing in this class
 * enforces that; it is deliberately a dumb transport.
 *
 * <p>Calls block, like {@link eu.siacs.conversations.zungvi.ZungviAuth}, so callers run them off
 * the main thread.
 */
public final class ZungviAi {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * Size tiers, not model names. The server resolves these to whatever weights it currently
     * runs, so re-pointing a tier is a server change and not an app release. Sending a raw tag
     * like "qwen2.5:7b-instruct" still works, but it pins this app to one deployment.
     *
     * <p>A tier a caller asks for beats the model the API project pins, which is the point: this
     * app wants a cheap model for short, frequent tasks even when the project pins a large one.
     */
    public static final String MODEL_SMALL = "small";

    public static final String MODEL_MEDIUM = "medium";

    public static final String MODEL_LARGE = "large";

    /**
     * Medium is the smallest tier that stays coherent on the summarisation and classification
     * prompts; small produced confidently wrong output in testing.
     */
    public static final String DEFAULT_MODEL = MODEL_MEDIUM;

    private static final OkHttpClient CLIENT =
            new OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    // Generation on the larger models is slow enough that the usual 30s read
                    // timeout cuts off summaries midway.
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build();

    private ZungviAi() {}

    /** The generated text, or why there is none. */
    public static final class Result {
        @Nullable public final String text;
        @Nullable public final String errorMessage;

        private Result(@Nullable final String text, @Nullable final String errorMessage) {
            this.text = text;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return !Strings.isNullOrEmpty(this.text);
        }

        static Result success(final String text) {
            return new Result(text, null);
        }

        static Result failure(final String message) {
            return new Result(null, message);
        }
    }

    /** True when this build has the public AI gateway configured. */
    public static boolean isConfigured() {
        return !Strings.isNullOrEmpty(BuildConfig.ZUNGVI_AI_BASE_URL);
    }

    public static Result chat(final String systemPrompt, final String userMessage) {
        return chat(DEFAULT_MODEL, systemPrompt, userMessage, 512);
    }

    public static Result chat(
            final String model,
            final String systemPrompt,
            final String userMessage,
            final int maxTokens) {
        if (!isConfigured()) {
            return Result.failure("AI features are not available in this build.");
        }
        final String payload;
        try {
            payload = buildPayload(model, systemPrompt, userMessage, maxTokens);
        } catch (final JSONException e) {
            return Result.failure("Could not prepare the request.");
        }
        final String accessToken;
        try {
            accessToken = accessToken();
        } catch (final IOException e) {
            return Result.failure(e.getMessage());
        }
        final Request request =
                new Request.Builder()
                        .url(baseUrl() + "/api/v1/mobile/chat/completions")
                        .header("Authorization", "Bearer " + accessToken)
                        .post(RequestBody.create(payload, JSON))
                        .build();
        try (final Response response = CLIENT.newCall(request).execute()) {
            final ResponseBody body = response.body();
            final String raw = body == null ? null : body.string();
            if (Strings.isNullOrEmpty(raw)) {
                return Result.failure("Zungvi AI did not answer. Please try again.");
            }
            return parseCompletion(raw);
        } catch (final IOException e) {
            Log.d(Config.LOGTAG, "ZUNGVIAI request to " + request.url() + " failed", e);
            return Result.failure("Could not reach Zungvi AI. Check your connection.");
        }
    }

    /** Gets a revocable, per-install credential. The project API token stays on the server. */
    private static String accessToken() throws IOException {
        synchronized (ZungviAi.class) {
            final SharedPreferences preferences =
                    Conversations.getContext()
                            .getSharedPreferences("zungvi_ai", Context.MODE_PRIVATE);
            final String storedToken = preferences.getString("access_token", null);
            if (!Strings.isNullOrEmpty(storedToken)) {
                return storedToken;
            }

            String clientId = preferences.getString("client_id", null);
            if (Strings.isNullOrEmpty(clientId)) {
                clientId = UUID.randomUUID().toString();
                preferences.edit().putString("client_id", clientId).apply();
            }

            final String requestBody;
            try {
                requestBody = new JSONObject().put("client_id", clientId).toString();
            } catch (final JSONException e) {
                throw new IOException("Could not prepare AI access.", e);
            }
            final Request request =
                    new Request.Builder()
                            .url(baseUrl() + "/api/v1/mobile/token")
                            .post(RequestBody.create(requestBody, JSON))
                            .build();
            try (final Response response = CLIENT.newCall(request).execute()) {
                final ResponseBody body = response.body();
                final String raw = body == null ? null : body.string();
                if (!response.isSuccessful() || Strings.isNullOrEmpty(raw)) {
                    throw new IOException(
                            response.isSuccessful()
                                    ? "Zungvi AI could not issue access."
                                    : errorMessage(raw, "Zungvi AI access was rejected."));
                }
                final String token;
                try {
                    token = new JSONObject(raw).optString("token", null);
                } catch (final JSONException e) {
                    throw new IOException("Zungvi AI returned invalid access details.", e);
                }
                if (Strings.isNullOrEmpty(token)) {
                    throw new IOException("Zungvi AI returned invalid access details.");
                }
                preferences.edit().putString("access_token", token).apply();
                return token;
            }
        }
    }

    private static String errorMessage(final String raw, final String fallback) {
        if (Strings.isNullOrEmpty(raw)) {
            return fallback;
        }
        try {
            return detailOr(new JSONObject(raw), fallback);
        } catch (final JSONException e) {
            return fallback;
        }
    }

    @VisibleForTesting
    static String buildPayload(
            final String model,
            final String systemPrompt,
            final String userMessage,
            final int maxTokens)
            throws JSONException {
        final JSONArray messages = new JSONArray();
        if (!Strings.isNullOrEmpty(systemPrompt)) {
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        }
        messages.put(new JSONObject().put("role", "user").put("content", userMessage));
        return new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", maxTokens)
                // These tasks are transformations of the user's own text, not creative writing,
                // so the least inventive setting available is the right one.
                .put("temperature", 0)
                .toString();
    }

    /**
     * Pulls the assistant text out of a chat-completions body. The API reports its own failures
     * with a {@code detail} field and an error status, which is more useful to surface than the
     * status code alone.
     */
    @VisibleForTesting
    static Result parseCompletion(@NonNull final String raw) {
        final JSONObject json;
        try {
            json = new JSONObject(raw);
        } catch (final JSONException e) {
            return Result.failure("Zungvi AI returned something unreadable.");
        }
        final JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return Result.failure(detailOr(json, "Zungvi AI could not answer that."));
        }
        final JSONObject first = choices.optJSONObject(0);
        final JSONObject message = first == null ? null : first.optJSONObject("message");
        final String content = message == null ? null : message.optString("content", null);
        if (Strings.isNullOrEmpty(content)) {
            return Result.failure("Zungvi AI returned an empty answer.");
        }
        return Result.success(content.trim());
    }

    private static String detailOr(final JSONObject json, final String fallback) {
        final Object detail = json.opt("detail");
        if (detail instanceof String s && !Strings.isNullOrEmpty(s)) {
            return s;
        }
        final String message = json.optString("message", null);
        return Strings.isNullOrEmpty(message) ? fallback : message;
    }

    private static String baseUrl() {
        final String configured = BuildConfig.ZUNGVI_AI_BASE_URL;
        return configured.endsWith("/")
                ? configured.substring(0, configured.length() - 1)
                : configured;
    }
}
