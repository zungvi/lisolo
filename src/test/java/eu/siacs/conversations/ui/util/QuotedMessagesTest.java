package eu.siacs.conversations.ui.util;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.IndividualMessage;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.model.Bookmark;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/** Resolution of a reply's quote target across the id forms remote clients put on the wire. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class QuotedMessagesTest {

    @Test
    public void quoteInAChannelResolvesByStanzaId() {
        final var conversation = group();
        final var quoted = received(conversation);
        quoted.setServerMsgId("stanza-1");
        final var reply = reply(conversation, "stanza-1");

        assertSame(quoted, QuotedMessages.of(List.of(quoted, reply)).resolve(reply));
    }

    @Test
    public void quoteInADirectChatResolvesByRemoteId() {
        final var conversation = direct();
        final var quoted = received(conversation);
        quoted.setRemoteMsgId("remote-1");
        final var reply = reply(conversation, "remote-1");

        assertSame(quoted, QuotedMessages.of(List.of(quoted, reply)).resolve(reply));
    }

    @Test
    public void quoteOfOurOwnDirectMessageResolvesByOriginId() {
        final var conversation = direct();
        final var quoted = new Message(conversation, "ours", Message.ENCRYPTION_NONE);
        final var reply = reply(conversation, quoted.getUuid());

        assertSame(quoted, QuotedMessages.of(List.of(quoted, reply)).resolve(reply));
    }

    @Test
    public void channelQuoteFallsBackToTheOriginIdASenderMayHaveUsedInstead() {
        // In a channel the stanza-id is the id to reply to, but senders are not all consistent
        // about it; a reply naming the origin-id still points at exactly one loaded message.
        final var conversation = group();
        final var quoted = received(conversation);
        quoted.setServerMsgId("stanza-1");
        quoted.setRemoteMsgId("origin-1");
        final var reply = reply(conversation, "origin-1");

        assertSame(quoted, QuotedMessages.of(List.of(quoted, reply)).resolve(reply));
    }

    @Test
    public void exactIdWinsOverAnotherMessagesFallbackId() {
        final var conversation = group();
        final var target = received(conversation);
        target.setServerMsgId("shared");
        final var decoy = received(conversation);
        decoy.setServerMsgId("stanza-2");
        decoy.setRemoteMsgId("shared");
        final var reply = reply(conversation, "shared");

        assertSame(target, QuotedMessages.of(List.of(target, decoy, reply)).resolve(reply));
    }

    @Test
    public void anAmbiguousFallbackIdResolvesToNothingRatherThanAGuess() {
        final var conversation = group();
        final var first = received(conversation);
        first.setServerMsgId("stanza-1");
        first.setRemoteMsgId("shared");
        final var second = received(conversation);
        second.setServerMsgId("stanza-2");
        second.setRemoteMsgId("shared");
        final var reply = reply(conversation, "shared");

        assertNull(QuotedMessages.of(List.of(first, second, reply)).resolve(reply));
    }

    @Test
    public void quoteOutsideTheLoadedRangeResolvesToNothing() {
        final var conversation = group();
        final var reply = reply(conversation, "stanza-not-loaded");

        assertNull(QuotedMessages.of(List.of(reply)).resolve(reply));
    }

    @Test
    public void aMessageThatIsNotAReplyHasNoQuote() {
        final var conversation = group();
        final var message = received(conversation);
        message.setServerMsgId("stanza-1");

        assertNull(QuotedMessages.of(List.of(message)).resolve(message));
    }

    @Test
    public void aReplyIsNeverItsOwnQuote() {
        final var conversation = group();
        final var reply = received(conversation);
        reply.setServerMsgId("stanza-1");
        reply.setReply("room@conference.example.net/Alice", "stanza-1", "quoted");

        assertNull(QuotedMessages.of(List.of(reply)).resolve(reply));
    }

    @Test
    public void separatorsAreNotReplyTargets() {
        final var conversation = group();
        final var message = received(conversation);
        message.setServerMsgId("stanza-1");
        final var separator = IndividualMessage.createDateSeparator(message);
        separator.setServerMsgId("stanza-1");
        final var reply = reply(conversation, "stanza-1");

        assertSame(
                message, QuotedMessages.of(List.of(separator, message, reply)).resolve(reply));
    }

    @Test
    public void anEmptySnapshotResolvesToNothing() {
        final var reply = reply(group(), "stanza-1");

        assertNull(QuotedMessages.of(List.of()).resolve(reply));
        assertNull(QuotedMessages.of(null).resolve(reply));
    }

    private static Message received(final Conversation conversation) {
        final var message =
                new Message(
                        conversation, "body", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setCounterpart(conversation.getAddress().withResource("Alice"));
        return message;
    }

    private static Message reply(final Conversation conversation, final String replyId) {
        final var message = new Message(conversation, "a reply", Message.ENCRYPTION_NONE);
        message.setReply(conversation.getAddress().toString(), replyId, "quoted");
        return message;
    }

    private static Conversation group() {
        return new TestConversation(
                "Test group", Jid.of("room@conference.example.net"), Conversation.MODE_MULTI);
    }

    private static Conversation direct() {
        return new TestConversation(
                "Juliet", Jid.of("juliet@example.com"), Conversation.MODE_SINGLE);
    }

    private static final class TestAccount extends Account {
        private TestAccount() {
            super(Jid.of("romeo@example.net"), "unused");
        }

        @Override
        public AxolotlService getAxolotlService() {
            return null;
        }
    }

    private static final class TestConversation extends Conversation {
        private TestConversation(final String name, final Jid address, final int mode) {
            super(name, new TestAccount(), address, mode);
        }

        @Override
        public Bookmark getBookmark() {
            return null;
        }

        @Override
        public int getNextEncryption() {
            return Message.ENCRYPTION_NONE;
        }
    }
}
