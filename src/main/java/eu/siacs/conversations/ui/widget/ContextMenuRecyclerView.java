package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.ContextMenu;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A {@link RecyclerView} that reports which row a context menu was opened on.
 *
 * <p>{@code registerForContextMenu} works on any view, but only {@code AdapterView} fills in a
 * {@link ContextMenu.ContextMenuInfo} describing the row. RecyclerView leaves it null, so an
 * {@code onCreateContextMenu} that reads {@code AdapterContextMenuInfo.position} — as the message
 * list and search results both do — would fail outright. This supplies the equivalent.
 *
 * <p>The position is captured on the way up through {@link #showContextMenuForChild}, which is
 * reached from whichever descendant was long-pressed, so it works even though the bubble layouts
 * mark their inner views long-clickable.
 */
public class ContextMenuRecyclerView extends RecyclerView {

    @Nullable private RecyclerViewContextMenuInfo contextMenuInfo;

    public ContextMenuRecyclerView(@NonNull final Context context) {
        super(context);
    }

    public ContextMenuRecyclerView(
            @NonNull final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
    }

    public ContextMenuRecyclerView(
            @NonNull final Context context,
            @Nullable final AttributeSet attrs,
            final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Nullable
    @Override
    protected ContextMenu.ContextMenuInfo getContextMenuInfo() {
        return this.contextMenuInfo;
    }

    @Override
    public boolean showContextMenuForChild(final View originalView) {
        if (!captureContextMenuInfo(originalView)) {
            return false;
        }
        return super.showContextMenuForChild(originalView);
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @Override
    public boolean showContextMenuForChild(
            final View originalView, final float x, final float y) {
        if (!captureContextMenuInfo(originalView)) {
            return false;
        }
        return super.showContextMenuForChild(originalView, x, y);
    }

    /**
     * Resolves {@code originalView} — which may be any descendant of a row — back to its adapter
     * position. Returns false when the row is gone or mid-rebind, in which case no menu is shown
     * rather than one pointing at the wrong message.
     */
    private boolean captureContextMenuInfo(final View originalView) {
        final var viewHolder = findContainingViewHolder(originalView);
        if (viewHolder == null) {
            this.contextMenuInfo = null;
            return false;
        }
        final int position = viewHolder.getBindingAdapterPosition();
        if (position == NO_POSITION) {
            this.contextMenuInfo = null;
            return false;
        }
        this.contextMenuInfo = new RecyclerViewContextMenuInfo(position, viewHolder.getItemId());
        return true;
    }

    /** The RecyclerView analogue of {@code AdapterView.AdapterContextMenuInfo}. */
    public static class RecyclerViewContextMenuInfo implements ContextMenu.ContextMenuInfo {

        public final int position;
        public final long id;

        public RecyclerViewContextMenuInfo(final int position, final long id) {
            this.position = position;
            this.id = id;
        }
    }
}
