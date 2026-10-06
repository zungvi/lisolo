package eu.siacs.conversations.ui.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class ReactionChipsTest {

    @Test
    public void reactionsWithinTheCapAreAllInline() {
        final var layout = ReactionChips.of(sorted("a", 3, "b", 2), Set.of(), 6);

        assertEquals(List.of("a", "b"), keys(layout.inline()));
        assertFalse(layout.hasOverflow());
    }

    @Test
    public void reactionsBeyondTheCapOverflowInCountOrder() {
        final var layout = ReactionChips.of(sorted("a", 9, "b", 8, "c", 7, "d", 6), Set.of(), 2);

        assertEquals(List.of("a", "b"), keys(layout.inline()));
        assertEquals(List.of("c", "d"), keys(layout.overflow()));
        assertTrue(layout.hasOverflow());
    }

    @Test
    public void ourOwnReactionStaysInlineDespiteALowCount() {
        // Tapping the chip is the only way to take a reaction back, so one of ours in the
        // collapsed remainder would be stranded there.
        final var layout =
                ReactionChips.of(
                        sorted("a", 9, "b", 8, "c", 7, "mine", 1), ImmutableSet.of("mine"), 2);

        assertEquals(List.of("a", "mine"), keys(layout.inline()));
        assertEquals(List.of("b", "c"), keys(layout.overflow()));
    }

    @Test
    public void everyOwnReactionStaysInlineEvenPastTheCap() {
        final var layout =
                ReactionChips.of(
                        sorted("a", 9, "mine", 2, "alsoMine", 1),
                        ImmutableSet.of("mine", "alsoMine"),
                        1);

        assertEquals(List.of("mine", "alsoMine"), keys(layout.inline()));
        assertEquals(List.of("a"), keys(layout.overflow()));
    }

    @Test
    public void inlineKeepsTheIncomingCountOrder() {
        final var layout =
                ReactionChips.of(
                        sorted("a", 9, "b", 8, "mine", 3, "d", 2), ImmutableSet.of("mine"), 3);

        assertEquals(List.of("a", "b", "mine"), keys(layout.inline()));
    }

    @Test
    public void noReactionsYieldsNothingToRender() {
        final var layout = ReactionChips.of(List.of(), Set.of(), 6);

        assertTrue(layout.inline().isEmpty());
        assertFalse(layout.hasOverflow());
    }

    @Test
    public void nullAggregationYieldsNothingToRender() {
        final var layout = ReactionChips.of(null);

        assertTrue(layout.inline().isEmpty());
        assertFalse(layout.hasOverflow());
    }

    private static List<Map.Entry<String, Integer>> sorted(final Object... emojiAndCounts) {
        final var builder = ImmutableList.<Map.Entry<String, Integer>>builder();
        for (int i = 0; i < emojiAndCounts.length; i += 2) {
            builder.add(
                    new AbstractMap.SimpleImmutableEntry<>(
                            (String) emojiAndCounts[i], (Integer) emojiAndCounts[i + 1]));
        }
        return builder.build();
    }

    private static List<String> keys(final List<Map.Entry<String, Integer>> entries) {
        return entries.stream().map(Map.Entry::getKey).toList();
    }
}
