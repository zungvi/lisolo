package eu.siacs.conversations.ui;

import android.view.View;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Reaction;
import eu.siacs.conversations.ui.util.ReactionChips;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class BindingAdapters {

    public static void setReactionsOnReceived(
            final ChipGroup chipGroup,
            final Reaction.Aggregated reactions,
            final Consumer<Collection<String>> onModifiedReactions,
            final Function<String, Boolean> onDetailsClicked,
            final Runnable onOverflowClicked,
            final Runnable addReaction) {
        setReactions(
                chipGroup,
                reactions,
                true,
                onModifiedReactions,
                onDetailsClicked,
                onOverflowClicked,
                addReaction);
    }

    public static void setReactionsOnSent(
            final ChipGroup chipGroup,
            final Reaction.Aggregated reactions,
            final Consumer<Collection<String>> onModifiedReactions,
            final Function<String, Boolean> onDetailsClicked,
            final Runnable onOverflowClicked) {
        setReactions(
                chipGroup,
                reactions,
                false,
                onModifiedReactions,
                onDetailsClicked,
                onOverflowClicked,
                null);
    }

    private static void setReactions(
            final ChipGroup chipGroup,
            final Reaction.Aggregated aggregated,
            final boolean onReceived,
            final Consumer<Collection<String>> onModifiedReactions,
            final Function<String, Boolean> onDetailsClicked,
            final Runnable onOverflowClicked,
            final Runnable addReaction) {
        final var layout = ReactionChips.of(aggregated);
        if (layout.inline().isEmpty()) {
            chipGroup.setVisibility(View.GONE);
            chipGroup.setTag(R.id.TAG_REACTION_CHIPS, null);
            chipGroup.removeAllViews();
            return;
        }
        chipGroup.setVisibility(View.VISIBLE);
        // Rebuilding every Chip on every bind allocated a view per emoji on each scroll frame. The
        // chips are a pure function of this signature, so an unchanged one only needs its
        // listeners refreshed - those close over the message the row now holds.
        final var signature = signature(layout, aggregated, onReceived);
        if (!signature.equals(chipGroup.getTag(R.id.TAG_REACTION_CHIPS))) {
            chipGroup.setTag(R.id.TAG_REACTION_CHIPS, signature);
            chipGroup.removeAllViews();
            for (final Map.Entry<String, Integer> reaction : layout.inline()) {
                chipGroup.addView(
                        reactionChip(
                                chipGroup,
                                reaction.getKey(),
                                reaction.getValue(),
                                aggregated.ourReactions.contains(reaction.getKey())));
            }
            if (layout.hasOverflow()) {
                chipGroup.addView(overflowChip(chipGroup, layout.overflow().size()));
            }
            if (onReceived) {
                chipGroup.addView(addReactionChip(chipGroup));
            }
        }
        bindListeners(
                chipGroup,
                layout,
                aggregated,
                onReceived,
                onModifiedReactions,
                onDetailsClicked,
                onOverflowClicked,
                addReaction);
    }

    private static void bindListeners(
            final ChipGroup chipGroup,
            final ReactionChips.Layout layout,
            final Reaction.Aggregated aggregated,
            final boolean onReceived,
            final Consumer<Collection<String>> onModifiedReactions,
            final Function<String, Boolean> onDetailsClicked,
            final Runnable onOverflowClicked,
            final Runnable addReaction) {
        int index = 0;
        for (final Map.Entry<String, Integer> reaction : layout.inline()) {
            final var chip = (Chip) chipGroup.getChildAt(index++);
            final var emoji = reaction.getKey();
            final boolean oneOfOurs = aggregated.ourReactions.contains(emoji);
            chip.setOnClickListener(
                    v -> onModifiedReactions.accept(toggled(aggregated, emoji, oneOfOurs)));
            chip.setOnLongClickListener(v -> onDetailsClicked.apply(emoji));
        }
        if (layout.hasOverflow()) {
            final var chip = (Chip) chipGroup.getChildAt(index++);
            chip.setOnClickListener(v -> onOverflowClicked.run());
        }
        if (onReceived) {
            final var chip = (Chip) chipGroup.getChildAt(index);
            chip.setOnClickListener(v -> addReaction.run());
        }
    }

    private static Collection<String> toggled(
            final Reaction.Aggregated aggregated, final String emoji, final boolean oneOfOurs) {
        if (oneOfOurs) {
            return ImmutableSet.copyOf(
                    Collections2.filter(aggregated.ourReactions, r -> !r.equals(emoji)));
        }
        return new ImmutableSet.Builder<String>()
                .addAll(aggregated.ourReactions)
                .add(emoji)
                .build();
    }

    /**
     * Everything the rendered chips depend on. Our own reactions are part of it because they decide
     * both the highlight and which emoji are held inline.
     */
    private static List<Object> signature(
            final ReactionChips.Layout layout,
            final Reaction.Aggregated aggregated,
            final boolean onReceived) {
        final var signature = new ArrayList<Object>(layout.inline().size() + 3);
        signature.add(onReceived);
        signature.add(layout.overflow().size());
        signature.add(ImmutableSet.copyOf(aggregated.ourReactions));
        for (final Map.Entry<String, Integer> reaction : layout.inline()) {
            signature.add(reaction.getKey());
            signature.add(reaction.getValue());
        }
        return ImmutableList.copyOf(signature);
    }

    private static Chip reactionChip(
            final ChipGroup chipGroup,
            final String emoji,
            final int count,
            final boolean oneOfOurs) {
        final var context = chipGroup.getContext();
        final Chip chip = new Chip(context);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipStartPadding(0.0f);
        chip.setChipEndPadding(0.0f);
        if (count == 1) {
            chip.setText(emoji);
        } else {
            chip.setText(String.format(Locale.ENGLISH, "%s %d", emoji, count));
        }
        // One of ours gets the primary container plus a matching outline. The two surface shades
        // used before differ by a step a reader cannot reliably see, which left no way to tell
        // which reactions were yours - the one thing the row has to answer before you tap it.
        if (oneOfOurs) {
            chip.setChipBackgroundColor(
                    MaterialColors.getColorStateListOrNull(
                            context, com.google.android.material.R.attr.colorPrimaryContainer));
            chip.setTextColor(
                    MaterialColors.getColor(
                            chip, com.google.android.material.R.attr.colorOnPrimaryContainer));
            chip.setChipStrokeWidth(
                    context.getResources().getDimension(R.dimen.reaction_chip_stroke_width));
            chip.setChipStrokeColor(
                    MaterialColors.getColorStateListOrNull(
                            context, androidx.appcompat.R.attr.colorPrimary));
        } else {
            chip.setChipBackgroundColor(
                    MaterialColors.getColorStateListOrNull(
                            context, com.google.android.material.R.attr.colorSurfaceContainerLow));
        }
        return chip;
    }

    /** Collapses the reactions held back by {@link ReactionChips}; opens the full list on click. */
    private static Chip overflowChip(final ChipGroup chipGroup, final int hidden) {
        final var context = chipGroup.getContext();
        final Chip chip = new Chip(context);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipStartPadding(0.0f);
        chip.setChipEndPadding(0.0f);
        chip.setText(context.getString(R.string.reaction_overflow, hidden));
        chip.setContentDescription(context.getString(R.string.reactions));
        chip.setChipBackgroundColor(
                MaterialColors.getColorStateListOrNull(
                        context, com.google.android.material.R.attr.colorSurfaceContainerLow));
        return chip;
    }

    private static Chip addReactionChip(final ChipGroup chipGroup) {
        final var context = chipGroup.getContext();
        final Chip chip = new Chip(context);
        chip.setChipIconResource(R.drawable.ic_add_reaction_24dp);
        chip.setChipStrokeColor(
                MaterialColors.getColorStateListOrNull(
                        context, com.google.android.material.R.attr.colorTertiary));
        chip.setChipBackgroundColor(
                MaterialColors.getColorStateListOrNull(
                        context, com.google.android.material.R.attr.colorTertiaryContainer));
        chip.setChipIconTint(
                MaterialColors.getColorStateListOrNull(
                        context, com.google.android.material.R.attr.colorOnTertiaryContainer));
        chip.setEnsureMinTouchTargetSize(false);
        chip.setTextEndPadding(0.0f);
        chip.setTextStartPadding(0.0f);
        chip.setContentDescription(context.getString(R.string.add_reaction));
        return chip;
    }
}
