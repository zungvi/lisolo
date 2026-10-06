package eu.siacs.conversations.ui.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import eu.siacs.conversations.entities.Reaction;
import eu.siacs.conversations.xmpp.Jid;
import java.util.List;
import org.junit.Test;

public class ReactionDetailsTest {

    @Test
    public void receivedReactionIsAttributedToTheContact() {
        final var names = ReactionDetails.directNames(List.of(received("❤")), "Me", "Juliet");

        assertEquals(List.of("Juliet"), names);
    }

    @Test
    public void sentReactionIsAttributedToUs() {
        final var names = ReactionDetails.directNames(List.of(sent("❤")), "Me", "Juliet");

        assertEquals(List.of("Me"), names);
    }

    @Test
    public void bothSidesAreNamedInSendOrder() {
        final var names =
                ReactionDetails.directNames(
                        List.of(sent("❤"), received("❤")), "Me", "Juliet");

        assertEquals(List.of("Me", "Juliet"), names);
    }

    @Test
    public void aReactorIsNamedOnlyOnce() {
        final var names =
                ReactionDetails.directNames(
                        List.of(received("❤"), received("👍")), "Me", "Juliet");

        assertEquals(List.of("Juliet"), names);
    }

    @Test
    public void anUnknownContactNameIsLeftOut() {
        final var names = ReactionDetails.directNames(List.of(received("❤")), "Me", null);

        assertEquals(List.of(), names);
    }

    @Test
    public void formatJoinsNamesAndReportsEmptyAsNothingToShow() {
        assertEquals("Me, Juliet", ReactionDetails.format(List.of("Me", "Juliet")));
        assertNull(ReactionDetails.format(List.of()));
        assertNull(ReactionDetails.format(null));
    }

    private static Reaction received(final String emoji) {
        return new Reaction(emoji, true, Jid.of("juliet@example.com"), null, null);
    }

    private static Reaction sent(final String emoji) {
        return new Reaction(emoji, false, Jid.of("romeo@example.net"), null, null);
    }
}
