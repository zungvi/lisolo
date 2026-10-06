package eu.siacs.conversations.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.model.Bookmark;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MessageReactionsTest {

    private static final String HEART = "❤️";
    private static final String THUMBS_UP = "👍";

    @Test
    public void aggregationIsComputedOncePerReactionSet() {
        // Every bound row asks for this; recomputing the index and sort on each bind was pure
        // waste on every scroll frame.
        final var message = message();
        message.setReactions(List.of(received(HEART), sent(HEART)));

        assertSame(message.getAggregatedReactions(), message.getAggregatedReactions());
    }

    @Test
    public void newReactionsReplaceTheAggregation() {
        final var message = message();
        message.setReactions(List.of(received(HEART)));
        final var before = message.getAggregatedReactions();

        message.setReactions(List.of(received(HEART), received(THUMBS_UP)));

        assertNotSame(before, message.getAggregatedReactions());
        assertEquals(2, message.getAggregatedReactions().reactions.size());
    }

    @Test
    public void aggregationCountsPerEmojiAndRemembersOurs() {
        final var message = message();
        message.setReactions(
                List.of(received(HEART), received(HEART), received(THUMBS_UP), sent(THUMBS_UP)));

        final var aggregated = message.getAggregatedReactions();

        assertEquals(List.of(HEART, THUMBS_UP), aggregated.reactions.stream()
                .map(java.util.Map.Entry::getKey)
                .toList());
        assertEquals(2, (int) aggregated.reactions.get(0).getValue());
        assertEquals(2, (int) aggregated.reactions.get(1).getValue());
        assertEquals(java.util.Set.of(THUMBS_UP), aggregated.ourReactions);
    }

    private static Message message() {
        final var conversation = new TestConversation();
        return new Message(
                conversation, "body", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
    }

    private static Reaction received(final String emoji) {
        return new Reaction(emoji, true, Jid.of("juliet@example.com"), null, null);
    }

    private static Reaction sent(final String emoji) {
        return new Reaction(emoji, false, Jid.of("romeo@example.net"), null, null);
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
            super("Juliet", new TestAccount(), Jid.of("juliet@example.com"), MODE_SINGLE);
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
