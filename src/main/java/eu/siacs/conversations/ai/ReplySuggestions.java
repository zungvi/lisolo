package eu.siacs.conversations.ai;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import eu.siacs.conversations.entities.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offers a few ready replies above the composer when a chat opens.
 *
 * <p>Suggestions are worth asking for only when there is an unanswered message from somebody
 * else: suggesting replies to your own last message is nonsense, and a chat whose last word is
 * already yours has nothing outstanding. {@link #isWorthSuggesting(List)} is the gate, and it is
 * deliberately strict - an empty list costs nothing, whereas a suggestion that misreads the chat
 * is read by the user as the app being wrong.
 *
 * <p>Nothing reaches the UI until the whole set has arrived and parsed. There is no partial or
 * placeholder state by design: a row of chips that appears empty and fills in later draws the
 * eye to itself at the exact moment the user is trying to read the last message.
 */
public final class ReplySuggestions {

    /** How many to offer. Three fits one row on a phone without scrolling or eliding. */
    @VisibleForTesting static final int MAX_SUGGESTIONS = 3;

    /**
     * A suggestion is something the user can send with one tap. Past roughly this length it is no
     * longer a shortcut - they have to read it carefully first, and reading it takes longer than
     * typing their own answer.
     */
    @VisibleForTesting static final int MAX_SUGGESTION_CHARS = 60;

    /**
     * Enough of the thread to answer the last message sensibly. Short on purpose: suggestions run
     * automatically on every qualifying chat open, so this is the one AI feature whose context
     * size is also a privacy and cost decision.
     */
    @VisibleForTesting static final int CONTEXT_MESSAGES = 8;

    private static final int MAX_BODY_CHARS = 300;

    private ReplySuggestions() {}

    /** Delivers a set of suggestions, or nothing at all. */
    public interface Callback {
        void onSuggestions(@NonNull List<String> suggestions);
    }

    /**
     * Whether this conversation has an unanswered message from somebody else.
     *
     * <p>Only the tail is examined: what matters is who spoke last, not whether the user ever
     * replied earlier in the thread.
     */
    @VisibleForTesting
    public static boolean isWorthSuggesting(final List<Message> messages) {
        final Message last = lastSuggestable(messages);
        return last != null
                && last.getStatus() == Message.STATUS_RECEIVED
                && !last.getBody().trim().isEmpty();
    }

    /**
     * Asks for suggestions, calling back only when there are some.
     *
     * <p>The callback is skipped entirely on failure rather than being handed an empty list and an
     * error, because there is nothing for the caller to do with either: the row simply stays
     * hidden, exactly as it does before the answer arrives.
     */
    public static void suggest(
            @NonNull final List<Message> messages,
            @NonNull final Locale target,
            @NonNull final Callback callback) {
        if (!isWorthSuggesting(messages)) {
            return;
        }
        final String transcript = transcript(messages);
        if (transcript.isEmpty()) {
            return;
        }
        final String language = target.getDisplayLanguage(Locale.ENGLISH);
        AiTask.run(
                () ->
                        AiTask.Outcome.of(
                                // Medium: three short replies is light work, and the request must
                                // not land on whatever larger model the API project pins, because
                                // this one runs on its own rather than on a tap.
                                ZungviAi.chat(
                                        ZungviAi.MODEL_MEDIUM,
                                        AiPrompts.replySuggestions(language),
                                        transcript,
                                        MAX_TOKENS)),
                outcome -> {
                    final List<String> suggestions = parse(outcome);
                    if (!suggestions.isEmpty()) {
                        callback.onSuggestions(suggestions);
                    }
                });
    }

    /** Three short lines, plus room for the model to waste a little on formatting. */
    private static final int MAX_TOKENS = 120;

    /**
     * Pulls usable suggestions out of the answer.
     *
     * <p>One per line, and everything questionable is dropped rather than repaired: a list
     * numbered by the model, a trailing explanation, a repeated line, something too long to be a
     * one-tap reply. Showing three good chips and silently discarding a fourth bad one is always
     * better than showing the bad one.
     */
    @VisibleForTesting
    static List<String> parse(final AiTask.Outcome outcome) {
        final List<String> suggestions = new ArrayList<>();
        if (!outcome.isSuccess()) {
            return suggestions;
        }
        for (final String line : outcome.text.split("\n")) {
            final String cleaned = clean(line);
            if (cleaned.isEmpty() || cleaned.length() > MAX_SUGGESTION_CHARS) {
                continue;
            }
            if (!containsIgnoreCase(suggestions, cleaned)) {
                suggestions.add(cleaned);
            }
            if (suggestions.size() == MAX_SUGGESTIONS) {
                break;
            }
        }
        return suggestions;
    }

    /**
     * Strips the decoration models add to a list - "1.", "-", "*", surrounding quotes - so the
     * chip shows the reply and not the formatting.
     */
    private static String clean(final String line) {
        String cleaned = line.trim();
        cleaned = cleaned.replaceFirst("^(?:[-*•]|\\d+[.)])\\s*", "").trim();
        if (cleaned.length() >= 2
                && cleaned.startsWith("\"")
                && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private static boolean containsIgnoreCase(final List<String> existing, final String candidate) {
        for (final String each : existing) {
            if (each.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The tail of the conversation as "sender: body" lines, oldest first.
     *
     * <p>Same shape as {@link CatchUp#transcript(List)} and for the same reason: the model has to
     * know which turns are the user's own, or it suggests replies to the wrong person.
     */
    @VisibleForTesting
    static String transcript(final List<Message> messages) {
        final List<Message> usable = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && usable.size() < CONTEXT_MESSAGES; i--) {
            final Message message = messages.get(i);
            if (isSuggestable(message) && !message.getBody().trim().isEmpty()) {
                usable.add(message);
            }
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = usable.size() - 1; i >= 0; i--) {
            final Message message = usable.get(i);
            final String body = message.getBody().trim();
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(message.getStatus() == Message.STATUS_RECEIVED ? "them" : "me")
                    .append(": ")
                    .append(
                            body.length() > MAX_BODY_CHARS
                                    ? body.substring(0, MAX_BODY_CHARS)
                                    : body);
        }
        return builder.toString();
    }

    /** The newest message that carries text at all, or null when there is none. */
    private static Message lastSuggestable(final List<Message> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            final Message message = messages.get(i);
            if (isSuggestable(message)) {
                return message;
            }
        }
        return null;
    }

    private static boolean isSuggestable(final Message message) {
        return message != null
                && message.getType() == Message.TYPE_TEXT
                && message.getBody() != null
                && !message.isDeleted()
                && !message.isGeoUri()
                && !message.isFileOrImage()
                && !message.treatAsDownloadable();
    }
}
