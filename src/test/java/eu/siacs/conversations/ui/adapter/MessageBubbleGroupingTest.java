package eu.siacs.conversations.ui.adapter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.model.Bookmark;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

/** Which adjacent messages render as one bubble group. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MessageBubbleGroupingTest {

    private static final Jid ROOM = Jid.of("room@conference.example.net");

    @Test
    public void consecutiveMessagesFromTheSameParticipantMerge() {
        final var conversation = group();

        assertTrue(
                MessageAdapter.merge(
                        received(conversation, "Alice", 0),
                        received(conversation, "Alice", 1_000)));
    }

    @Test
    public void messagesFromDifferentParticipantsDoNotMerge() {
        final var conversation = group();

        assertFalse(
                MessageAdapter.merge(
                        received(conversation, "Alice", 0),
                        received(conversation, "Bob", 1_000)));
    }

    @Test
    public void aPrivateMessageDoesNotJoinThePublicGroupAboveIt() {
        // A whisper has a different audience than the room, so sharing one surface with a public
        // message would read as a single thread.
        final var conversation = group();
        final var publicMessage = received(conversation, "Alice", 0);
        final var whisper = received(conversation, "Alice", 1_000);
        whisper.setType(Message.TYPE_PRIVATE);

        assertFalse(MessageAdapter.merge(publicMessage, whisper));
        assertFalse(MessageAdapter.merge(whisper, publicMessage));
    }

    @Test
    public void ourWhispersToDifferentParticipantsDoNotMerge() {
        // The occupant check only runs on received messages, so without the private-message rule
        // two whispers we sent to different people merged into one group.
        final var conversation = group();

        assertFalse(
                MessageAdapter.merge(
                        sentWhisper(conversation, "Alice", 0),
                        sentWhisper(conversation, "Bob", 1_000)));
    }

    @Test
    public void ourWhispersToTheSameParticipantMerge() {
        final var conversation = group();

        assertTrue(
                MessageAdapter.merge(
                        sentWhisper(conversation, "Alice", 0),
                        sentWhisper(conversation, "Alice", 1_000)));
    }

    @Test
    public void messagesFurtherApartThanTheMergeWindowDoNotMerge() {
        final var conversation = group();

        assertFalse(
                MessageAdapter.merge(
                        received(conversation, "Alice", 0),
                        received(conversation, "Alice", 10 * 60_000)));
    }

    private static Message received(
            final Conversation conversation, final String nick, final long offset) {
        final var message =
                new Message(
                        conversation, "body", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setCounterpart(ROOM.withResource(nick));
        message.setTime(offset);
        return message;
    }

    private static Message sentWhisper(
            final Conversation conversation, final String nick, final long offset) {
        final var message =
                new Message(conversation, "body", Message.ENCRYPTION_NONE, Message.STATUS_SEND);
        message.setType(Message.TYPE_PRIVATE);
        message.setCounterpart(ROOM.withResource(nick));
        message.setTime(offset);
        return message;
    }

    private static Conversation group() {
        return new TestConversation();
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
        private TestConversation() {
            super("Test group", new TestAccount(), ROOM, MODE_MULTI);
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
