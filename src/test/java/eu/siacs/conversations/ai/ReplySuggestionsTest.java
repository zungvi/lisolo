package eu.siacs.conversations.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xmpp.Jid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Suggestions are offered without being asked for, so the gate and the parser carry the weight: a
 * chip that misreads the chat is read as the app being wrong, and costs more than offering nothing.
 */
public class ReplySuggestionsTest {

    @Test
    public void unansweredIncomingMessageIsWorthSuggesting() {
        assertTrue(
                ReplySuggestions.isWorthSuggesting(
                        List.of(received("Are you coming to the meeting?"))));
    }

    /** Nothing is outstanding when the user spoke last. */
    @Test
    public void ownLastMessageIsNotWorthSuggesting() {
        assertFalse(
                ReplySuggestions.isWorthSuggesting(
                        List.of(received("Are you coming?"), sent("Yes, on my way"))));
    }

    @Test
    public void emptyAndNullHistoriesAreNotWorthSuggesting() {
        assertFalse(ReplySuggestions.isWorthSuggesting(Collections.emptyList()));
        assertFalse(ReplySuggestions.isWorthSuggesting(null));
    }

    /** A photo with no caption gives the model nothing to answer. */
    @Test
    public void nonTextLastMessageIsNotWorthSuggesting() {
        final Message image = received("");
        image.setType(Message.TYPE_IMAGE);

        assertFalse(ReplySuggestions.isWorthSuggesting(List.of(image)));
    }

    @Test
    public void blankIncomingMessageIsNotWorthSuggesting() {
        assertFalse(ReplySuggestions.isWorthSuggesting(List.of(received("   "))));
    }

    /**
     * An image after an unanswered question must not hide it: the question is still the thing a
     * reply would answer.
     */
    @Test
    public void imageAfterAQuestionStillSuggests() {
        final Message image = received("");
        image.setType(Message.TYPE_IMAGE);

        assertTrue(ReplySuggestions.isWorthSuggesting(List.of(received("Which one?"), image)));
    }

    @Test
    public void parseTakesOneSuggestionPerLine() {
        final List<String> parsed =
                ReplySuggestions.parse(AiTask.Outcome.success("Yes\nNot today\nLet me check"));

        assertEquals(List.of("Yes", "Not today", "Let me check"), parsed);
    }

    /** Models decorate lists however they feel like; the chip must show the reply, not the bullet. */
    @Test
    public void parseStripsNumberingBulletsAndQuotes() {
        assertEquals(
                List.of("Yes", "Not today", "Let me check"),
                ReplySuggestions.parse(
                        AiTask.Outcome.success("1. Yes\n- Not today\n\"Let me check\"")));
        assertEquals(
                List.of("Sounds good"),
                ReplySuggestions.parse(AiTask.Outcome.success("• Sounds good")));
    }

    @Test
    public void parseDropsBlankLinesAndCapsTheCount() {
        final List<String> parsed =
                ReplySuggestions.parse(AiTask.Outcome.success("Yes\n\n\nNo\n\nMaybe\nLater\nSure"));

        assertEquals(ReplySuggestions.MAX_SUGGESTIONS, parsed.size());
        assertEquals(List.of("Yes", "No", "Maybe"), parsed);
    }

    /** A suggestion the user has to read twice is not a one-tap reply. */
    @Test
    public void parseDropsSuggestionsTooLongToBeOneTap() {
        final String tooLong = "x".repeat(ReplySuggestions.MAX_SUGGESTION_CHARS + 1);
        final List<String> parsed =
                ReplySuggestions.parse(AiTask.Outcome.success("Yes\n" + tooLong + "\nNo"));

        assertEquals(List.of("Yes", "No"), parsed);
    }

    @Test
    public void parseDropsDuplicatesRegardlessOfCase() {
        final List<String> parsed =
                ReplySuggestions.parse(AiTask.Outcome.success("Yes\nyes\nYES\nNo"));

        assertEquals(List.of("Yes", "No"), parsed);
    }

    /** A failed call shows no row at all, so it must parse to nothing rather than to an error chip. */
    @Test
    public void parseReturnsNothingForFailureAndEmptyAnswers() {
        assertTrue(ReplySuggestions.parse(AiTask.Outcome.failure("offline")).isEmpty());
        assertTrue(ReplySuggestions.parse(AiTask.Outcome.success("")).isEmpty());
        assertTrue(ReplySuggestions.parse(AiTask.Outcome.success("\n\n  \n")).isEmpty());
    }

    @Test
    public void transcriptLabelsWhoSpokeOldestFirst() {
        final List<Message> messages =
                List.of(received("Are you free?"), sent("When?"), received("Tomorrow at 3"));

        assertEquals(
                "them: Are you free?\nme: When?\nthem: Tomorrow at 3",
                ReplySuggestions.transcript(messages));
    }

    /** Context is bounded because this runs on its own: the window is a privacy decision too. */
    @Test
    public void transcriptKeepsOnlyTheTailOfALongChat() {
        final List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(received("message " + i));
        }

        final String transcript = ReplySuggestions.transcript(messages);

        assertEquals(
                ReplySuggestions.CONTEXT_MESSAGES, transcript.split("\n").length);
        assertTrue(transcript.startsWith("them: message 22"));
        assertTrue(transcript.endsWith("them: message 29"));
    }

    @Test
    public void transcriptSkipsMessagesWithNothingToRead() {
        final Message image = received("");
        image.setType(Message.TYPE_IMAGE);
        final List<Message> messages = List.of(received("Look at this"), image, received("Nice?"));

        assertEquals("them: Look at this\nthem: Nice?", ReplySuggestions.transcript(messages));
    }

    private static final Conversation CONVERSATION =
            new Conversation(
                    "Pascal",
                    new Account(Jid.of("me@example.test"), "unused"),
                    Jid.of("pascal@example.test"),
                    Conversation.MODE_SINGLE);

    private static Message received(final String body) {
        return new Message(
                CONVERSATION, body, Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
    }

    private static Message sent(final String body) {
        return new Message(CONVERSATION, body, Message.ENCRYPTION_NONE, Message.STATUS_SEND);
    }
}
