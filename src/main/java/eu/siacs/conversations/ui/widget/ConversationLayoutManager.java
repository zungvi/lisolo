package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.view.View;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** Scrollbar metrics for a timeline whose bubbles have unrelated, changing heights. */
public final class ConversationLayoutManager extends LinearLayoutManager {

    private static final int SCROLL_UNIT = 1000;

    public ConversationLayoutManager(final Context context) {
        super(context);
        setStackFromEnd(true);
    }

    @Override
    public int computeVerticalScrollExtent(final RecyclerView.State state) {
        return hasScrollableContent(state) ? SCROLL_UNIT : 0;
    }

    @Override
    public int computeVerticalScrollRange(final RecyclerView.State state) {
        return hasScrollableContent(state) ? Math.max(2, state.getItemCount()) * SCROLL_UNIT : 0;
    }

    @Override
    public int computeVerticalScrollOffset(final RecyclerView.State state) {
        if (!hasScrollableContent(state)) {
            return 0;
        }
        final View first = findViewByPosition(findFirstVisibleItemPosition());
        final View last = findViewByPosition(findLastVisibleItemPosition());
        if (first == null || last == null) {
            return 0;
        }
        // Integer item metrics jump whenever a partially visible bubble becomes fully visible.
        // Pixel metrics instead re-estimate the entire archive from the visible bubbles. Use
        // fractional item positions with a fixed thumb extent: receipts may resize a bubble, but
        // cannot resize the thumb or move it away from the bottom of an idle conversation.
        final double start =
                getPosition(first) + visibleFraction(first, getPaddingTop());
        final double end =
                getPosition(last)
                        + visibleFraction(last, getHeight() - getPaddingBottom());
        final double remaining = state.getItemCount() - (end - start);
        final double progress = remaining <= 0 ? 0 : Math.max(0, Math.min(1, start / remaining));
        return (int) Math.round(progress * (computeVerticalScrollRange(state) - SCROLL_UNIT));
    }

    private double visibleFraction(final View child, final int edge) {
        final int top = decoratedStart(child);
        final int height = decoratedEnd(child) - top;
        return height <= 0 ? 0 : Math.max(0, Math.min(1, (double) (edge - top) / height));
    }

    private boolean hasScrollableContent(final RecyclerView.State state) {
        if (getChildCount() == 0 || state.getItemCount() == 0) {
            return false;
        }
        final int firstPosition = findFirstVisibleItemPosition();
        final int lastPosition = findLastVisibleItemPosition();
        final View first = findViewByPosition(firstPosition);
        final View last = findViewByPosition(lastPosition);
        return first != null
                && last != null
                && (firstPosition > 0
                        || lastPosition < state.getItemCount() - 1
                        || decoratedStart(first) < getPaddingTop()
                        || decoratedEnd(last) > getHeight() - getPaddingBottom());
    }

    private int decoratedStart(final View child) {
        return getDecoratedTop(child)
                - ((RecyclerView.LayoutParams) child.getLayoutParams()).topMargin;
    }

    private int decoratedEnd(final View child) {
        return getDecoratedBottom(child)
                + ((RecyclerView.LayoutParams) child.getLayoutParams()).bottomMargin;
    }
}
