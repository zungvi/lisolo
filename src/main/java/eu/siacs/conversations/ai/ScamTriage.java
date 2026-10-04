package eu.siacs.conversations.ai;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import java.util.Locale;

/**
 * Checks the first message from somebody who is not a contact.
 *
 * <p>Only the message being judged is sent, never the rest of the conversation, and only for
 * senders outside the roster - there is no reason to ship a stranger's whole history anywhere to
 * answer one question about one message.
 */
public final class ScamTriage {

    /**
     * Long enough to carry a fraud pitch. Below this there is nothing to classify and the model
     * guesses, which is how "hi" ends up flagged.
     */
    @VisibleForTesting static final int MIN_BODY_LENGTH = 15;

    /** A pitch does not need a wall of text, and sending more than this leaks more than needed. */
    private static final int MAX_BODY_CHARS = 1000;

    private ScamTriage() {}

    public interface Callback {
        void onChecked(boolean likelyScam);
    }

    public static void check(@NonNull final String body, @NonNull final Callback callback) {
        if (!isWorthChecking(body)) {
            callback.onChecked(false);
            return;
        }
        final String trimmed =
                body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body;
        AiTask.run(
                () -> AiTask.Outcome.of(ZungviAi.chat(AiPrompts.SCAM_TRIAGE, trimmed)),
                outcome -> callback.onChecked(isScamVerdict(outcome)));
    }

    @VisibleForTesting
    static boolean isWorthChecking(final String body) {
        return body != null && body.trim().length() >= MIN_BODY_LENGTH;
    }

    /**
     * Anything other than a clear SCAM is treated as fine. A failed call, an empty answer or
     * unexpected wording must never raise a warning: accusing a real person of fraud because the
     * network was down is worse than missing a scam.
     */
    @VisibleForTesting
    static boolean isScamVerdict(final AiTask.Outcome outcome) {
        if (!outcome.isSuccess()) {
            return false;
        }
        final String answer = outcome.text.trim().toUpperCase(Locale.ROOT);
        return answer.startsWith("SCAM");
    }
}
