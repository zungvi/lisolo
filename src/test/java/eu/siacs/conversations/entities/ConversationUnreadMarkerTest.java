package eu.siacs.conversations.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * Conversation stores its flags through setAttribute, which is backed by org.json. The stub
 * android.jar throws on those methods, so this runs under Robolectric for a real implementation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 23)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ConversationUnreadMarkerTest {

    @Test
    public void localUnreadMarkerDoesNotChangeMessageReadState() {
        final Conversation conversation = conversation();
        final Message message =
                new Message(
                        conversation,
                        "Already read",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        message.markRead();
        conversation.add(message);

        assertTrue(conversation.isRead());
        assertEquals(0, conversation.unreadCount());

        assertTrue(conversation.markUnreadLocally());
        assertFalse(conversation.isRead());
        assertEquals(1, conversation.unreadCount());
        assertTrue(message.isRead());

        assertTrue(conversation.clearLocalUnreadMarker());
        assertTrue(conversation.isRead());
        assertEquals(0, conversation.unreadCount());
    }

    @Test
    public void localMarkerDoesNotInflateActualUnreadCount() {
        final Conversation conversation = conversation();
        final Message message =
                new Message(
                        conversation,
                        "New message",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        message.markUnread();
        conversation.add(message);

        conversation.markUnreadLocally();

        assertEquals(1, conversation.unreadCount());
    }

    private static Conversation conversation() {
        final Account account = new Account(Jid.of("me@example.test"), "unused");
        return new Conversation(
                "Test conversation", account, Jid.of("you@example.test"), Conversation.MODE_SINGLE);
    }
}
