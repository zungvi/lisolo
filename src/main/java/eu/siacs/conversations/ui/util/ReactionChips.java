package eu.siacs.conversations.ui.util;

import com.google.common.collect.ImmutableList;
import eu.siacs.conversations.entities.Reaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits a message's aggregated reactions into the chips rendered inside the bubble and the
 * remainder, which is collapsed behind a single "+n" chip.
 *
 * <p>Without a cap the chip row grows with the number of *distinct* emoji on a message, which in a
 * busy channel wraps into several lines, pushes the message body out of view and costs a Chip view
 * per emoji on every bind.
 *
 * <p>Emoji are offered in count-descending order (as {@link Reaction#aggregated(Collection)}
 * returns them), with one exception: a reaction the local user sent is always kept inline. Tapping
 * a chip is the only way to take a reaction back, so letting one fall into the collapsed remainder
 * would strand it.
 */
public final class ReactionChips {

    /** Chosen so the common case stays on one line on a narrow phone. */
    public static final int MAX_INLINE = 6;

    private ReactionChips() {}

    public record Layout(
            List<Map.Entry<String, Integer>> inline, List<Map.Entry<String, Integer>> overflow) {

        public boolean hasOverflow() {
            return !this.overflow.isEmpty();
        }
    }

    public static Layout of(final Reaction.Aggregated aggregated) {
        return of(aggregated, MAX_INLINE);
    }

    public static Layout of(final Reaction.Aggregated aggregated, final int maxInline) {
        if (aggregated == null || aggregated.reactions == null) {
            return new Layout(ImmutableList.of(), ImmutableList.of());
        }
        return of(aggregated.reactions, aggregated.ourReactions, maxInline);
    }

    public static Layout of(
            final List<Map.Entry<String, Integer>> sorted,
            final Set<String> ourReactions,
            final int maxInline) {
        if (sorted.size() <= maxInline || maxInline <= 0) {
            return new Layout(ImmutableList.copyOf(sorted), ImmutableList.of());
        }
        // Reserve a slot for every one of our own reactions, then fill what is left from the top of
        // the count-descending order. Both lists keep the incoming order so the row does not
        // reshuffle as counts change.
        final Set<String> ours = ourReactions == null ? Set.of() : ourReactions;
        final var ourCount = (int) sorted.stream().filter(e -> ours.contains(e.getKey())).count();
        int remaining = Math.max(0, maxInline - ourCount);
        final List<Map.Entry<String, Integer>> inline = new ArrayList<>(maxInline);
        final List<Map.Entry<String, Integer>> overflow = new ArrayList<>();
        for (final Map.Entry<String, Integer> entry : sorted) {
            if (ours.contains(entry.getKey())) {
                inline.add(entry);
            } else if (remaining > 0) {
                inline.add(entry);
                remaining--;
            } else {
                overflow.add(entry);
            }
        }
        return new Layout(ImmutableList.copyOf(inline), ImmutableList.copyOf(overflow));
    }
}
