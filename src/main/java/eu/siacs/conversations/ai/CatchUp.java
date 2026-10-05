package eu.siacs.conversations.ai;

import androidx.annotation.NonNull;
import eu.siacs.conversations.entities.Message;
import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * How much unread text makes a summary worth asking for.
     *
     * <p>Measured in characters of actual message text, not in number of messages. A count of
     * rows answers the wrong question twice over: it refuses a single long message that is
     * exactly what somebody wants condensed, and it accepts six photos, whose transcript is
     * empty and which therefore produce nothing to summarise.
     *
     * <p>Roughly forty words. Below that, reading the messages is quicker than a round trip to
     * the server and a paraphrase of them.
     */
    public static final int MIN_SUMMARY_CHARS = 200;

    private static final int MAX_BODY_CHARS = 600;

    private CatchUp() {}

    /**
     * How far back to go when the user marked a chat unread by hand.
     *
     * <p>That marker is a single flag on the conversation with no position in the thread, so
     * there is no range to recover - only an intention to come back to it. A bounded window of
     * the most recent messages is the honest answer.
     */
    static final int MARKED_UNREAD_WINDOW = 25;

    /**
     * The messages the reader has not seen yet, oldest first.
     *
     * <p>Taken from the conversation list, where the unread flags are still intact. Opening a
     * chat marks everything read within moments, so asking this question from inside the chat
     * means asking it too late.
     *
     * @param markedUnread whether the user flagged the conversation unread by hand. Per-message
     *     flags win when there are any, because they give a real boundary.
     */
    public static List<Message> unread(final List<Message> messages, final boolean markedUnread) {
        final List<Message> unread = unread(messages);
        if (!unread.isEmpty() || !markedUnread || messages.isEmpty()) {
            return unread;
        }
        final int from = Math.max(0, messages.size() - MARKED_UNREAD_WINDOW);
        return new ArrayList<>(messages.subList(from, messages.size()));
    }

    public static List<Message> unread(final List<Message> messages) {
        // Mirrors Conversation.unreadCount(), which is what paints the badge on the row: a read
        // call entry is stepped over rather than treated as the end of the unread run. Stopping
        // at one made catch-up report nothing to summarise on a conversation whose badge said
        // otherwise, because a single call among the recent messages hid everything behind it.
        final List<Message> unread = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            final Message message = messages.get(i);
            if (message.isRead()) {
                if (message.getType() == Message.TYPE_RTP_SESSION) {
                    continue;
                }
                break;
            }
            unread.add(message);
        }
        Collections.reverse(unread);
        return unread;
    }

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

    /**
     * Whether these messages contain enough text to be worth summarising. Callers use this to
     * decide whether to offer the action at all, so that it is never offered and then refused.
     */
    public static boolean isWorthSummarising(final List<Message> messages) {
        return transcript(messages).length() >= MIN_SUMMARY_CHARS;
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
