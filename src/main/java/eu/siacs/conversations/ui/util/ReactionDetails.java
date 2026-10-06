package eu.siacs.conversations.ui.util;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import eu.siacs.conversations.entities.Reaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Who reacted with a given emoji, for the details dialog. */
public final class ReactionDetails {

    private ReactionDetails() {}

    /**
     * Names of the people behind {@code reactions} in a direct conversation.
     *
     * <p>A direct conversation has exactly two possible reactors, so there is nothing to look up:
     * reactions we sent are ours, every other one is the contact's. Duplicates are collapsed
     * because a reactor may appear once per emoji they sent.
     *
     * @param selfLabel what to call the local user, e.g. "Me"
     * @param contactLabel the contact's display name
     */
    public static List<String> directNames(
            final Collection<Reaction> reactions,
            final String selfLabel,
            final String contactLabel) {
        final var names = new LinkedHashSet<String>();
        for (final Reaction reaction : reactions) {
            final var name = reaction.received ? contactLabel : selfLabel;
            if (!Strings.isNullOrEmpty(name)) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    /** Joins names for display, or null when there is nobody to show. */
    public static String format(final Collection<String> names) {
        return names == null || names.isEmpty() ? null : Joiner.on(", ").join(names);
    }
}
