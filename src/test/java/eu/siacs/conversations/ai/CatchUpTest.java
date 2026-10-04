package eu.siacs.conversations.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.Jid;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * The transcript decides what the model sees, so what it drops and how it attributes lines is the
 * part worth pinning down.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 23)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class CatchUpTest {

    @Test
    public void attributesReceivedMessagesToTheirSender() {
        final Conversation conversation = groupConversation();
        final List<Message> messages = new ArrayList<>();
        messages.add(received(conversation, "bruno", "le serveur est down"));
        messages.add(received(conversation, "marc", "I restarted it"));

        assertEquals("bruno: le serveur est down\nmarc: I restarted it", CatchUp.transcript(messages));
    }

    @Test
    public void ownMessagesAreLabelledAsMe() {
        final Conversation conversation = groupConversation();
        final List<Message> messages = List.of(sent(conversation, "on arrive"));

        assertEquals("me: on arrive", CatchUp.transcript(messages));
    }

    /** Images and files carry no text, so they would only waste context. */
    @Test
    public void nonTextMessagesAreLeftOut() {
        final Conversation conversation = groupConversation();
        final Message status = received(conversation, "bruno", "joined");
        status.setType(Message.TYPE_STATUS);
        final List<Message> messages = List.of(status, received(conversation, "marc", "hello"));

        assertEquals("marc: hello", CatchUp.transcript(messages));
    }

    @Test
    public void deletedMessagesAreLeftOut() {
        final Conversation conversation = groupConversation();
        final Message deleted = received(conversation, "bruno", "oops");
        deleted.setDeleted(true);
        final List<Message> messages = List.of(deleted, received(conversation, "marc", "hello"));

        assertEquals("marc: hello", CatchUp.transcript(messages));
    }

    @Test
    public void blankMessagesAreLeftOut() {
        final Conversation conversation = groupConversation();
        final List<Message> messages =
                List.of(received(conversation, "bruno", "   "), received(conversation, "marc", "hi"));

        assertEquals("marc: hi", CatchUp.transcript(messages));
    }

    /**
     * When the backlog is trimmed it has to keep the END of the conversation: that is where
     * topics get resolved, and reporting a solved problem as open is the failure that matters.
     */
    @Test
    public void trimmingKeepsTheNewestMessages() {
        final Conversation conversation = groupConversation();
        final List<Message> messages = new ArrayList<>();
        for (int i = 0; i < CatchUp.MAX_MESSAGES + 20; i++) {
            messages.add(received(conversation, "bruno", "message " + i));
        }

        final String transcript = CatchUp.transcript(messages);

        assertTrue(transcript.endsWith("message " + (CatchUp.MAX_MESSAGES + 19)));
        assertFalse(transcript.contains("message 0\n"));
        assertEquals(CatchUp.MAX_MESSAGES, transcript.split("\n").length);
    }

    @Test
    public void emptyBacklogProducesNothingToSend() {
        assertEquals("", CatchUp.transcript(List.of()));
    }

    private static Conversation groupConversation() {
        final Account account = new Account(Jid.of("me@example.test"), "unused");
        return new Conversation(
                "Group", account, Jid.of("room@conference.example.test"), Conversation.MODE_MULTI);
    }

    private static Message received(
            final Conversation conversation, final String nick, final String body) {
        final Message message =
                new Message(conversation, body, Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setCounterpart(Jid.of("room@conference.example.test/" + nick));
        return message;
    }

    private static Message sent(final Conversation conversation, final String body) {
        return new Message(conversation, body, Message.ENCRYPTION_NONE, Message.STATUS_SEND);
    }
}
