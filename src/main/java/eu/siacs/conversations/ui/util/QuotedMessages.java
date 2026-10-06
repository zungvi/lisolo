package eu.siacs.conversations.ui.util;

import com.google.common.base.Strings;
import eu.siacs.conversations.entities.Message;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves, for a reply, the message it quotes.
 *
 * <p>Built once per adapter snapshot instead of scanning the loaded range on every bind: the scan
 * was O(n) per row, so a conversation of n rows cost O(n²) per full pass.
 *
 * <p>A reply id is matched against {@link Message#getReplyIdWireFormat()} first - that is the id
 * this client would itself have put on the wire, so it is the only form guaranteed to be correct
 * for the conversation's mode. Remote clients are less consistent: a reply arriving from another
 * implementation may carry the origin-id (our uuid) or the archive's stanza-id where we expect the
 * other, so the remaining id forms are consulted as a fallback. A fallback id claimed by more than
 * one loaded message is dropped rather than guessed at, because showing the wrong quote is worse
 * than showing the sender's own fallback text.
 */
public final class QuotedMessages {

    private static final QuotedMessages EMPTY =
            new QuotedMessages(Map.of(), Map.of());

    private final Map<String, Message> byWireFormat;
    private final Map<String, Message> byAlternateId;

    private QuotedMessages(
            final Map<String, Message> byWireFormat, final Map<String, Message> byAlternateId) {
        this.byWireFormat = byWireFormat;
        this.byAlternateId = byAlternateId;
    }

    public static QuotedMessages of(final List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return EMPTY;
        }
        final Map<String, Message> byWireFormat = new HashMap<>();
        final Map<String, Message> byAlternateId = new HashMap<>();
        final Set<String> ambiguousAlternateIds = new HashSet<>();
        for (final Message message : messages) {
            // Date separators, the load-more notice and room status lines are never reply targets.
            if (message.getType() == Message.TYPE_STATUS) {
                continue;
            }
            final var wireFormat = message.getReplyIdWireFormat();
            if (!Strings.isNullOrEmpty(wireFormat)) {
                byWireFormat.putIfAbsent(wireFormat, message);
            }
            for (final String alternate :
                    new String[] {
                        message.getServerMsgId(), message.getRemoteMsgId(), message.getUuid()
                    }) {
                if (Strings.isNullOrEmpty(alternate) || alternate.equals(wireFormat)) {
                    continue;
                }
                final var previous = byAlternateId.put(alternate, message);
                if (previous != null && previous != message) {
                    ambiguousAlternateIds.add(alternate);
                }
            }
        }
        byAlternateId.keySet().removeAll(ambiguousAlternateIds);
        return new QuotedMessages(byWireFormat, byAlternateId);
    }

    /** The message {@code reply} quotes, or null when it is not a reply or not in the snapshot. */
    public Message resolve(final Message reply) {
        if (reply == null || !reply.isReply()) {
            return null;
        }
        final var replyId = reply.getReplyId();
        if (Strings.isNullOrEmpty(replyId)) {
            return null;
        }
        final var exact = this.byWireFormat.get(replyId);
        final var resolved = exact != null ? exact : this.byAlternateId.get(replyId);
        // A message cannot quote itself; an id collision that says otherwise is not a quote.
        return resolved == reply ? null : resolved;
    }
}
