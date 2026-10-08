package eu.siacs.conversations.ui.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

/** Uses real RecyclerView layout to exercise the thumb while the chat is idle at its bottom. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 24)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class ConversationScrollbarTest {

    private static final int WIDTH = 360;
    private static final int HEIGHT = 480;
    private ActivityController<Activity> activityController;

    @After
    public void tearDown() {
        if (activityController != null) {
            activityController.pause().stop().destroy();
        }
    }

    @Test
    public void idleReceiptRefreshKeepsThumbAtBottom() {
        final ChatFixture chat = createChat();
        final ScrollbarGeometry initial = geometry(chat.view);
        assertBottom(chat);

        for (int i = 0; i < 10; ++i) {
            chat.adapter.notifyItemChanged(chat.adapter.getItemCount() - 1);
            layout(chat.view);
            assertBottom(chat);
            assertEquals(initial.offset, geometry(chat.view).offset);
            assertEquals(initial.extent, geometry(chat.view).extent);
            assertEquals(initial.range, geometry(chat.view).range);
        }
    }

    @Test
    public void resizedNewestBubbleDoesNotMakeBottomThumbJump() {
        final ChatFixture chat = createChat();
        final ScrollbarGeometry initial = geometry(chat.view);
        assertBottom(chat);

        // A transfer preview, a reaction, or a receipt label can change a visible bubble's height.
        // The user still has not moved, and the latest message remains aligned with the composer.
        for (final int height : new int[] {40, 300, HEIGHT * 2, 80}) {
            chat.adapter.newestHeight = height;
            chat.adapter.notifyItemChanged(chat.adapter.getItemCount() - 1);
            layout(chat.view);
            assertBottom(chat);
            final ScrollbarGeometry resized = geometry(chat.view);
            assertEquals(initial.offset, resized.offset);
            assertEquals(initial.extent, resized.extent);
            assertEquals(initial.range, resized.range);
        }
    }

    @Test
    public void newestBubbleTallerThanViewportStillReportsExactBottom() {
        final ChatFixture chat = createChat();
        chat.adapter.newestHeight = HEIGHT * 2;
        chat.adapter.notifyItemChanged(chat.adapter.getItemCount() - 1);
        layout(chat.view);
        assertBottom(chat);
    }

    @Test
    public void scrollInsideTallBubbleUpdatesCursorContinuously() {
        final ChatFixture chat = createChat();
        chat.adapter.tallPosition = 15;
        chat.adapter.notifyItemChanged(15);
        chat.layoutManager.scrollToPositionWithOffset(15, -40);
        layout(chat.view);
        assertTrue(chat.view.canScrollVertically(-1));
        assertTrue(chat.view.canScrollVertically(1));
        final ScrollbarGeometry initial = geometry(chat.view);

        chat.view.scrollBy(0, 60);
        layout(chat.view);
        assertEquals(15, chat.layoutManager.findFirstVisibleItemPosition());
        final ScrollbarGeometry scrolled = geometry(chat.view);
        assertTrue(scrolled.offset > initial.offset);
        assertEquals(initial.extent, scrolled.extent);
        assertEquals(initial.range, scrolled.range);
    }

    @Test
    public void appendingMessageAtBottomUpdatesRangeAndPreservesEnd() {
        final ChatFixture chat = createChat();
        final int previousCount = chat.adapter.getItemCount();
        ++chat.adapter.count;
        chat.adapter.notifyItemInserted(previousCount);
        chat.layoutManager.scrollToPosition(chat.adapter.getItemCount() - 1);
        layout(chat.view);
        assertBottom(chat);
        assertEquals(chat.adapter.getItemCount() * 1000, geometry(chat.view).range);
        assertEquals(1000, geometry(chat.view).extent);
    }

    @Test
    public void shortConversationHidesScrollbar() {
        final ChatFixture chat = createChat(2);
        assertFalse(chat.view.canScrollVertically(-1));
        assertFalse(chat.view.canScrollVertically(1));
        assertEquals(new ScrollbarGeometry(0, 0, 0), geometry(chat.view));
    }

    @Test
    public void reachingOldestMessageReportsExactTop() {
        final ChatFixture chat = createChat();
        chat.layoutManager.scrollToPositionWithOffset(0, 0);
        layout(chat.view);
        assertFalse(chat.view.canScrollVertically(-1));
        assertTrue(chat.view.canScrollVertically(1));
        assertEquals(0, geometry(chat.view).offset);
        assertEquals(1000, geometry(chat.view).extent);
    }

    @Test
    public void prependedHistoryRestoresTopOffsetDespiteStackFromEnd() {
        final ChatFixture chat = createChat();
        chat.layoutManager.scrollToPositionWithOffset(15, -20);
        layout(chat.view);
        assertEquals(
                -20,
                chat.layoutManager.getDecoratedTop(chat.layoutManager.findViewByPosition(15)));

        // Preserve the same row after adding an older page. Explicit offsets are measured from
        // the start edge when reverseLayout is false, even though stackFromEnd is true.
        chat.adapter.count += 10;
        chat.adapter.notifyItemRangeInserted(0, 10);
        chat.layoutManager.scrollToPositionWithOffset(25, -20);
        layout(chat.view);
        assertEquals(
                -20,
                chat.layoutManager.getDecoratedTop(chat.layoutManager.findViewByPosition(25)));
    }

    private ChatFixture createChat() {
        return createChat(40);
    }

    private ChatFixture createChat(final int count) {
        activityController = Robolectric.buildActivity(Activity.class).setup();
        final Activity activity = activityController.get();
        final ContextMenuRecyclerView view = new ContextMenuRecyclerView(activity);
        final ConversationLayoutManager layoutManager = new ConversationLayoutManager(activity);
        layoutManager.setStackFromEnd(true);
        view.setLayoutManager(layoutManager);
        view.setItemAnimator(null);
        final HeightAdapter adapter = new HeightAdapter();
        adapter.count = count;
        view.setAdapter(adapter);
        activity.setContentView(view);
        assertTrue(view.isAttachedToWindow());
        layoutManager.scrollToPosition(adapter.getItemCount() - 1);
        layout(view);
        return new ChatFixture(view, layoutManager, adapter);
    }

    private static void layout(final RecyclerView view) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, WIDTH, HEIGHT);
    }

    private static void assertBottom(final ChatFixture chat) {
        assertFalse(chat.view.canScrollVertically(1));
        final View newest =
                chat.layoutManager.findViewByPosition(chat.adapter.getItemCount() - 1);
        assertTrue(newest != null);
        assertEquals(HEIGHT, chat.layoutManager.getDecoratedBottom(newest));
        final ScrollbarGeometry geometry = geometry(chat.view);
        assertTrue(geometry.extent > 0);
        assertTrue(geometry.range >= geometry.extent);
        assertEquals(geometry.range, geometry.offset + geometry.extent);
    }

    private static ScrollbarGeometry geometry(final RecyclerView view) {
        return new ScrollbarGeometry(
                view.computeVerticalScrollOffset(),
                view.computeVerticalScrollExtent(),
                view.computeVerticalScrollRange());
    }

    private record ChatFixture(
            ContextMenuRecyclerView view,
            ConversationLayoutManager layoutManager,
            HeightAdapter adapter) {}

    private record ScrollbarGeometry(int offset, int extent, int range) {}

    private static final class HeightAdapter extends RecyclerView.Adapter<HeightViewHolder> {
        private int newestHeight = 80;
        private int tallPosition = -1;
        private int count = 40;

        @NonNull
        @Override
        public HeightViewHolder onCreateViewHolder(
                @NonNull final ViewGroup parent, final int viewType) {
            return new HeightViewHolder(new TextView(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(
                @NonNull final HeightViewHolder holder, final int position) {
            final int height;
            if (position == tallPosition) {
                height = HEIGHT * 2;
            } else if (position == getItemCount() - 1) {
                height = newestHeight;
            } else {
                height = position % 2 == 0 ? 72 : 128;
            }
            holder.itemView.setLayoutParams(
                    new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            ((TextView) holder.itemView).setText("Message " + position);
        }

        @Override
        public int getItemCount() {
            return count;
        }
    }

    private static final class HeightViewHolder extends RecyclerView.ViewHolder {
        private HeightViewHolder(@NonNull final View itemView) {
            super(itemView);
        }
    }
}
