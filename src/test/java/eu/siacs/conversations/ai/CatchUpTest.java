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
@Config(manifest = Config.NONE, sdk = 24)
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

    // --- formatting the model's marker output into something readable -------------------------

    private static final String DONE = "Settled";
    private static final String OPEN = "Still open";

    @Test
    public void markersBecomeHeadingsAndBullets() {
        final String raw = "DONE\n- Serveur: logrotate configure\nOPEN\n- Backup automatique";

        assertEquals(
                "Settled\n\u2022  Serveur: logrotate configure\n\nStill open\n\u2022  Backup automatique",
                CatchUp.format(raw, DONE, OPEN));
    }

    /** The raw markers are what made it read like machine output. */
    @Test
    public void rawMarkersNeverSurvive() {
        final String formatted =
                CatchUp.format("DONE\n- a\nOPEN\n- b", DONE, OPEN);

        assertFalse(formatted.contains("DONE"));
        assertFalse(formatted.contains("OPEN"));
    }

    @Test
    public void emptySectionsAreDroppedRatherThanLeftAsNone() {
        final String formatted = CatchUp.format("DONE\n- fixed it\nOPEN\nNone", DONE, OPEN);

        assertEquals("Settled\n\u2022  fixed it", formatted);
        assertFalse(formatted.contains("None"));
    }

    @Test
    public void aSummaryWithNothingInEitherSectionIsEmpty() {
        assertEquals("", CatchUp.format("DONE\nNone\nOPEN\nNone", DONE, OPEN));
    }

    @Test
    public void markdownDecorationOnHeadingsIsTolerated() {
        final String formatted = CatchUp.format("**DONE**\n* a\n## OPEN:\n* b", DONE, OPEN);

        assertEquals("Settled\n\u2022  a\n\nStill open\n\u2022  b", formatted);
    }

    /** An answer that ignores the format is still worth showing. */
    @Test
    public void outputWithoutMarkersIsPassedThrough() {
        assertEquals("Just a paragraph.", CatchUp.format("Just a paragraph.", DONE, OPEN));
    }

    @Test
    public void blankInputProducesNothing() {
        assertEquals("", CatchUp.format("", DONE, OPEN));
        assertEquals("", CatchUp.format(null, DONE, OPEN));
        assertEquals("", CatchUp.format("   \n  ", DONE, OPEN));
    }

    @Test
    public void headingsAreTakenFromTheCallerSoTheyCanBeTranslated() {
        final String formatted = CatchUp.format("DONE\n- a", "Réglé", "Encore ouvert");

        assertTrue(formatted.startsWith("Réglé"));
    }

    // --- selecting only what was actually missed ---------------------------------------------

    @Test
    public void unreadReturnsOnlyTheTailThatWasNotRead() {
        final Conversation c = groupConversation();
        // Message.read defaults to true, so the unread ones are marked explicitly.
        final Message read1 = received(c, "bruno", "old one");
        final Message read2 = received(c, "marc", "old two");
        final Message new1 = received(c, "bruno", "new one");
        final Message new2 = received(c, "marc", "new two");
        new1.markUnread();
        new2.markUnread();

        final List<Message> unread = CatchUp.unread(List.of(read1, read2, new1, new2));

        assertEquals(2, unread.size());
        assertEquals("new one", unread.get(0).getBody());
        assertEquals("new two", unread.get(1).getBody());
    }

    @Test
    public void everythingReadMeansNothingWasMissed() {
        final Conversation c = groupConversation();
        final Message a = received(c, "bruno", "one");
        final Message b = received(c, "marc", "two");
        a.markRead();
        b.markRead();

        assertTrue(CatchUp.unread(List.of(a, b)).isEmpty());
    }

    @Test
    public void everythingUnreadReturnsEverything() {
        final Conversation c = groupConversation();
        final Message a = received(c, "bruno", "one");
        final Message b = received(c, "marc", "two");
        a.markUnread();
        b.markUnread();

        assertEquals(2, CatchUp.unread(List.of(a, b)).size());
    }

    /**
     * Only the unbroken run at the end counts. An older message left unread behind ones that were
     * read is not something the user "missed" on this visit.
     */
    @Test
    public void onlyTheTrailingRunOfUnreadCounts() {
        final Conversation c = groupConversation();
        final Message strayUnread = received(c, "bruno", "stray");
        strayUnread.markUnread();
        final Message read = received(c, "marc", "read later");
        final Message fresh = received(c, "bruno", "fresh");
        fresh.markUnread();

        final List<Message> unread = CatchUp.unread(List.of(strayUnread, read, fresh));

        assertEquals(1, unread.size());
        assertEquals("fresh", unread.get(0).getBody());
    }

    @Test
    public void emptyConversationHasNothingUnread() {
        assertTrue(CatchUp.unread(List.of()).isEmpty());
    }

    // --- "mark as unread", which sets a flag on the conversation, not on messages ------------

    @Test
    public void markedUnreadFallsBackToARecentWindow() {
        final Conversation c = groupConversation();
        final List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            messages.add(received(c, "bruno", "message " + i));
        }

        final List<Message> unread = CatchUp.unread(messages, true);

        assertEquals(CatchUp.MARKED_UNREAD_WINDOW, unread.size());
        assertEquals("message 39", unread.get(unread.size() - 1).getBody());
    }

    @Test
    public void markedUnreadOnAShortThreadTakesWhatThereIs() {
        final Conversation c = groupConversation();
        final List<Message> messages =
                List.of(received(c, "bruno", "one"), received(c, "marc", "two"));

        assertEquals(2, CatchUp.unread(messages, true).size());
    }

    /** A real boundary beats the flag: per-message unread is the precise answer. */
    @Test
    public void perMessageUnreadWinsOverTheMarker() {
        final Conversation c = groupConversation();
        final Message old1 = received(c, "bruno", "old");
        final Message fresh = received(c, "marc", "fresh");
        fresh.markUnread();

        final List<Message> unread = CatchUp.unread(List.of(old1, fresh), true);

        assertEquals(1, unread.size());
        assertEquals("fresh", unread.get(0).getBody());
    }

    @Test
    public void withoutTheMarkerAndWithoutUnreadThereIsNothing() {
        final Conversation c = groupConversation();
        final List<Message> messages =
                List.of(received(c, "bruno", "one"), received(c, "marc", "two"));

        assertTrue(CatchUp.unread(messages, false).isEmpty());
    }

    @Test
    public void markerOnAnEmptyConversationStillYieldsNothing() {
        assertTrue(CatchUp.unread(List.of(), true).isEmpty());
    }

    // --- must agree with the unread badge on the row ----------------------------------------

    /**
     * The reported bug: a group showed unread messages and offered Catch up, which then claimed
     * there was nothing to summarise. Conversation.unreadCount() steps over a read call entry
     * and keeps counting, so one call among the recent messages hid every unread message behind
     * it from a scan that stopped at the first read row.
     */
    @Test
    public void readCallEntriesDoNotHideTheUnreadRunBehindThem() {
        final Conversation c = groupConversation();
        final List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final Message m = received(c, "bruno", "unread " + i);
            m.markUnread();
            messages.add(m);
        }
        // A finished call sits among them and is already read.
        final Message call = received(c, "marc", "");
        call.setType(Message.TYPE_RTP_SESSION);
        messages.add(3, call);

        final List<Message> unread = CatchUp.unread(messages);

        assertEquals(6, unread.size());
        assertEquals("unread 0", unread.get(0).getBody());
        assertEquals("unread 5", unread.get(unread.size() - 1).getBody());
    }

    @Test
    public void countMatchesTheConversationsOwnUnreadCount() {
        final Conversation c = groupConversation();
        for (int i = 0; i < 4; i++) {
            final Message m = received(c, "bruno", "u" + i);
            m.markUnread();
            c.add(m);
        }
        final Message call = received(c, "marc", "");
        call.setType(Message.TYPE_RTP_SESSION);
        c.add(call);

        final List<Message> messages = new ArrayList<>();
        c.populateWithMessages(messages);

        assertEquals(c.unreadCount(), CatchUp.unread(messages).size());
    }

    /** A read ordinary message still ends the run - only calls are stepped over. */
    @Test
    public void aReadTextMessageStillEndsTheRun() {
        final Conversation c = groupConversation();
        final Message old = received(c, "bruno", "already read");
        final Message fresh = received(c, "marc", "new");
        fresh.markUnread();

        final List<Message> unread = CatchUp.unread(List.of(old, fresh));

        assertEquals(1, unread.size());
        assertEquals("new", unread.get(0).getBody());
    }

    // --- deciding whether a summary is worth asking for --------------------------------------

    /** The reported case: one long message is exactly what somebody wants condensed. */
    @Test
    public void oneLongMessageIsWorthSummarising() {
        final Conversation c = groupConversation();
        final Message m = received(c, "bruno", "x".repeat(CatchUp.MIN_SUMMARY_CHARS));
        m.markUnread();

        assertTrue(CatchUp.isWorthSummarising(List.of(m)));
    }

    @Test
    public void oneShortMessageIsFasterToJustRead() {
        final Conversation c = groupConversation();
        final Message m = received(c, "bruno", "see you at 3");
        m.markUnread();

        assertFalse(CatchUp.isWorthSummarising(List.of(m)));
    }

    /**
     * Counting rows accepted these and then produced an empty transcript, so the action was
     * offered and immediately reported that there was nothing to summarise.
     */
    @Test
    public void severalPhotosAreNotWorthSummarising() {
        final Conversation c = groupConversation();
        final List<Message> photos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final Message m = received(c, "bruno", "/path/image" + i + ".jpg");
            m.setType(Message.TYPE_IMAGE);
            m.markUnread();
            photos.add(m);
        }

        assertFalse(CatchUp.isWorthSummarising(photos));
    }

    @Test
    public void severalShortMessagesAddUp() {
        final Conversation c = groupConversation();
        final List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(received(c, "bruno", "a reasonably sized line of chat number " + i));
        }

        assertTrue(CatchUp.isWorthSummarising(messages));
    }

    @Test
    public void nothingAtAllIsNotWorthSummarising() {
        assertFalse(CatchUp.isWorthSummarising(List.of()));
    }

    // --- a running app, messages read, then more arrive --------------------------------------

    /**
     * The live cycle, without a restart: a batch arrives, the user reads it, a second batch
     * arrives. The second batch has to be picked up on its own, and must not drag the already
     * read first batch back in with it.
     */
    @Test
    public void newMessagesAfterReadingAreFoundWithoutARestart() {
        final Conversation c = groupConversation();
        final List<Message> thread = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final Message m = received(c, "bruno", "first batch message number " + i);
            m.markUnread();
            thread.add(m);
        }
        assertEquals(6, CatchUp.unread(thread).size());

        // The user opens the chat; everything becomes read.
        for (final Message m : thread) {
            m.markRead();
        }
        assertTrue(CatchUp.unread(thread).isEmpty());

        // More arrive while the app is still running. MessageParser marks each one unread as it
        // lands, which is what this stands in for.
        for (int i = 0; i < 3; i++) {
            final Message m = received(c, "marc", "second batch message number " + i);
            m.markUnread();
            thread.add(m);
        }

        final List<Message> unread = CatchUp.unread(thread);

        assertEquals(3, unread.size());
        assertEquals("second batch message number 0", unread.get(0).getBody());
        assertEquals("second batch message number 2", unread.get(2).getBody());
    }

    @Test
    public void readingTheSecondBatchClearsItAgain() {
        final Conversation c = groupConversation();
        final Message m = received(c, "bruno", "x".repeat(CatchUp.MIN_SUMMARY_CHARS));
        m.markUnread();
        final List<Message> thread = new ArrayList<>(List.of(m));
        assertTrue(CatchUp.isWorthSummarising(CatchUp.unread(thread)));

        m.markRead();

        assertTrue(CatchUp.unread(thread).isEmpty());
        assertFalse(CatchUp.isWorthSummarising(CatchUp.unread(thread)));
    }
}
