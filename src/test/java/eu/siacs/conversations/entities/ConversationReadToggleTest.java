package eu.siacs.conversations.entities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * The conversation-list long press shows "Mark as unread" or "Mark as read" purely from {@link
 * Conversation#isRead()}. A chat can be unread for two independent reasons, and the toggle is
 * only correct if that single call accounts for both.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 23)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ConversationReadToggleTest {

    @Test
    public void aQuietConversationIsRead() {
        final Conversation conversation = conversation();
        conversation.add(readMessage(conversation));

        assertTrue(conversation.isRead());
    }

    @Test
    public void unreadMessagesMakeItUnread() {
        final Conversation conversation = conversation();
        final Message message = readMessage(conversation);
        message.markUnread();
        conversation.add(message);

        assertFalse(conversation.isRead());
    }

    /** The manual flag alone, with every message already read. */
    @Test
    public void theManualMarkerAloneMakesItUnread() {
        final Conversation conversation = conversation();
        conversation.add(readMessage(conversation));

        assertTrue(conversation.markUnreadLocally());

        assertFalse(conversation.isRead());
    }

    @Test
    public void bothSourcesAtOnceStillReadsAsUnread() {
        final Conversation conversation = conversation();
        final Message message = readMessage(conversation);
        message.markUnread();
        conversation.add(message);
        conversation.markUnreadLocally();

        assertFalse(conversation.isRead());
    }

    /**
     * What "Mark as read" has to achieve. Clearing only the marker would leave the row unread
     * and make the action look like it failed.
     */
    @Test
    public void clearingBothSourcesIsWhatMakesItReadAgain() {
        final Conversation conversation = conversation();
        final Message message = readMessage(conversation);
        message.markUnread();
        conversation.add(message);
        conversation.markUnreadLocally();

        conversation.clearLocalUnreadMarker();
        assertFalse("messages are still unread", conversation.isRead());

        conversation.markRead(message.getUuid());
        assertTrue(conversation.isRead());
    }

    private static Conversation conversation() {
        final Account account = new Account(Jid.of("me@example.test"), "unused");
        return new Conversation(
                "Test", account, Jid.of("you@example.test"), Conversation.MODE_SINGLE);
    }

    private static Message readMessage(final Conversation conversation) {
        return new Message(conversation, "hello", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
    }
}
