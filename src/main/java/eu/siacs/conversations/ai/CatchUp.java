package eu.siacs.conversations.ai;

import androidx.annotation.NonNull;
import eu.siacs.conversations.entities.Message;
import java.util.List;
import java.util.Locale;

/** Summarises the messages somebody missed in a busy group. */
public final class CatchUp {

    /**
     * Enough backlog to be worth summarising without blowing the context window. Older messages
     * are dropped rather than the newer ones, because the end of the thread is where topics get
     * resolved and that is what the summary has to get right.
     */
    static final int MAX_MESSAGES = 120;

    /** Below this a reader is faster off just scrolling. */
    public static final int MIN_MESSAGES = 5;

    private static final int MAX_BODY_CHARS = 600;

    private CatchUp() {}

    /**
     * Turns the model's marker format into something a person would read.
     *
     * <p>The prompt asks for literal DONE and OPEN markers because making the model sort each
     * topic into settled or outstanding is what stopped it reporting solved problems as still
     * open. That structure is worth keeping and worth hiding: shown raw it reads like machine
     * output. Headings arrive translated from the caller.
     *
     * <p>Anything that does not follow the format is passed through untouched rather than
     * mangled, since a summary in an unexpected shape is still more use than a parse error.
     */
    public static String format(final String raw, final String doneHeading, final String openHeading) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        final StringBuilder done = new StringBuilder();
        final StringBuilder open = new StringBuilder();
        StringBuilder current = null;
        boolean sawMarker = false;
        for (final String line : raw.split("\n")) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final String marker = trimmed.replace("*", "").replace("#", "").replace(":", "").trim();
            if ("DONE".equalsIgnoreCase(marker)) {
                current = done;
                sawMarker = true;
                continue;
            }
            if ("OPEN".equalsIgnoreCase(marker)) {
                current = open;
                sawMarker = true;
                continue;
            }
            if (current == null) {
                continue;
            }
            final String item = stripBullet(trimmed);
            // The model writes "None" for an empty section; an absent heading says it better.
            if (item.isEmpty() || "none".equalsIgnoreCase(item)) {
                continue;
            }
            current.append("•  ").append(item).append('\n');
        }
        if (!sawMarker) {
            return raw.trim();
        }
        final StringBuilder out = new StringBuilder();
        if (done.length() > 0) {
            out.append(doneHeading).append('\n').append(done);
        }
        if (open.length() > 0) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(openHeading).append('\n').append(open);
        }
        return out.toString().trim();
    }

    private static String stripBullet(final String line) {
        String item = line;
        while (!item.isEmpty() && (item.startsWith("-") || item.startsWith("*") || item.startsWith("•"))) {
            item = item.substring(1).trim();
        }
        return item.trim();
    }

    public static void summarise(
            @NonNull final List<Message> messages,
            @NonNull final Locale target,
            @NonNull final AiTask.Callback callback) {
        final String transcript = transcript(messages);
        if (transcript.isEmpty()) {
            callback.onFinished(AiTask.Outcome.failure("Nothing to summarise."));
            return;
        }
        AiTask.run(
                () ->
                        AiTask.Outcome.of(
                                ZungviAi.chat(
                                        AiPrompts.catchUp(target.getDisplayLanguage(Locale.ENGLISH)),
                                        transcript)),
                callback);
    }

    /**
     * Flattens messages to "sender: body" lines. Who said what matters: the summary has to be
     * able to say that somebody took an action, not just that it happened.
     */
    static String transcript(final List<Message> messages) {
        final List<Message> recent =
                messages.size() > MAX_MESSAGES
                        ? messages.subList(messages.size() - MAX_MESSAGES, messages.size())
                        : messages;
        final StringBuilder builder = new StringBuilder();
        for (final Message message : recent) {
            if (!isSummarisable(message)) {
                continue;
            }
            final String body = message.getBody().trim();
            if (body.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(senderOf(message))
                    .append(": ")
                    .append(body.length() > MAX_BODY_CHARS ? body.substring(0, MAX_BODY_CHARS) : body);
        }
        return builder.toString();
    }

    private static boolean isSummarisable(final Message message) {
        return message.getType() == Message.TYPE_TEXT
                && !message.isDeleted()
                && !message.isGeoUri()
                && !message.isFileOrImage()
                && !message.treatAsDownloadable();
    }

    private static String senderOf(final Message message) {
        if (message.getStatus() != Message.STATUS_RECEIVED) {
            return "me";
        }
        final var counterpart = message.getCounterpart();
        if (counterpart == null) {
            return "them";
        }
        // In a group the resource is the nickname; in a 1:1 chat it is the local part that
        // identifies the person.
        final String resource = counterpart.getResource();
        if (resource != null && !resource.isEmpty()) {
            return resource;
        }
        final String local = counterpart.getLocal();
        return local == null || local.isEmpty() ? "them" : local;
    }
}
