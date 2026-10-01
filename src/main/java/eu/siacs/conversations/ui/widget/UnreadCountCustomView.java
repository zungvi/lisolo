package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import eu.siacs.conversations.R;

/**
 * The unread badge.
 *
 * <p>Measures to a circle for a single digit and stretches into a pill for wider counts, rather than
 * squeezing three digits into a fixed circle. Height drives everything: the corner radius is half
 * the height and the text is sized from it, so the badge scales with whatever the layout asks for.
 */
public class UnreadCountCustomView extends View {

    /** Counts above this are shown as an infinity glyph. */
    private static final int MAX_DISPLAYED_COUNT = 999;

    private static final float TEXT_SIZE_RATIO = 0.62f;
    private static final float HORIZONTAL_PADDING_RATIO = 0.3f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private int unreadCount;
    private int badgeColor = 0xff326130;
    private int textColor = Color.WHITE;

    public UnreadCountCustomView(final Context context) {
        super(context);
        init(context);
    }

    public UnreadCountCustomView(final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        initXMLAttrs(context, attrs);
        init(context);
    }

    public UnreadCountCustomView(
            final Context context, @Nullable final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initXMLAttrs(context, attrs);
        init(context);
    }

    private void initXMLAttrs(final Context context, @Nullable final AttributeSet attrs) {
        final var a = context.obtainStyledAttributes(attrs, R.styleable.UnreadCountCustomView);
        try {
            // Look attributes up by name. The previous implementation used getIndex(0)/getIndex(1),
            // which enumerate only the attributes actually present, so leaving either one out of a
            // layout silently read the wrong value.
            this.badgeColor =
                    a.getColor(
                            R.styleable.UnreadCountCustomView_backgroundColor,
                            ContextCompat.getColor(context, R.color.md_theme_light_tertiaryContainer));
            this.textColor =
                    a.getColor(
                            R.styleable.UnreadCountCustomView_textColor,
                            ContextCompat.getColor(
                                    context, R.color.md_theme_light_onTertiaryContainer));
        } finally {
            a.recycle();
        }
    }

    private void init(final Context context) {
        paint.setColor(this.badgeColor);
        textPaint.setColor(this.textColor);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(mediumTypeface(context));
    }

    private static Typeface mediumTypeface(final Context context) {
        try {
            final var inter = ResourcesCompat.getFont(context, R.font.inter_medium);
            if (inter != null) {
                return inter;
            }
        } catch (final RuntimeException e) {
            // Fall through to the platform face; a missing or unparsable font must not take the
            // conversation list down with it.
        }
        return Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    }

    private String label() {
        return this.unreadCount > MAX_DISPLAYED_COUNT ? "∞" : String.valueOf(this.unreadCount);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        textPaint.setTextSize(height * TEXT_SIZE_RATIO);
        final float textWidth = textPaint.measureText(label());
        final int desiredWidth =
                Math.round(Math.max(height, textWidth + height * HORIZONTAL_PADDING_RATIO * 2f));
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec), height);
    }

    @Override
    protected void onDraw(@NonNull final Canvas canvas) {
        super.onDraw(canvas);
        final float width = getWidth();
        final float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }
        bounds.set(0f, 0f, width, height);
        final float radius = height / 2f;
        canvas.drawRoundRect(bounds, radius, radius, paint);

        textPaint.setTextSize(height * TEXT_SIZE_RATIO);
        // Centre on the text's own vertical extents rather than the font's full line box, so the
        // digits sit optically centred in the pill.
        final var metrics = textPaint.getFontMetrics();
        final float baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(label(), width / 2f, baseline, textPaint);
    }

    public void setUnreadCount(final int unreadCount) {
        if (this.unreadCount == unreadCount) {
            return;
        }
        this.unreadCount = unreadCount;
        // The digit count changes the measured width, so a repaint alone is not enough.
        requestLayout();
        invalidate();
    }

    /**
     * Sets the pill colour. This deliberately shadows {@link View#setBackgroundColor(int)}: the
     * badge paints its own rounded background and never uses the view background.
     */
    @Override
    public void setBackgroundColor(final int backgroundColor) {
        this.badgeColor = backgroundColor;
        paint.setColor(backgroundColor);
        invalidate();
    }

    public void setTextColor(final int textColor) {
        this.textColor = textColor;
        textPaint.setColor(textColor);
        invalidate();
    }
}
