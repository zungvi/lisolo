package eu.siacs.conversations.ui.util;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;
import androidx.annotation.FontRes;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import eu.siacs.conversations.R;

/**
 * Resolves the app's Inter faces and applies emphasis to a {@link TextView} without losing them.
 *
 * <p>{@code TextView.setTypeface(null, style)} cannot be used for this. With a null family the
 * framework either falls back to {@code Typeface.defaultFromStyle(style)} or, for {@link
 * Typeface#NORMAL}, to the platform default outright — in both cases replacing Inter with Roboto.
 * Adapters that toggled emphasis that way silently undid the app's typography on every bind.
 *
 * <p>Weight is therefore chosen by picking a real face, and only slant is left to the platform to
 * synthesize, since no italic faces are bundled.
 */
public final class Typefaces {

    private Typefaces() {}

    /** Inter 400, as a family so nested bold spans resolve to the real bold face. */
    public static Typeface regular(final Context context) {
        return get(context, R.font.inter, Typeface.NORMAL);
    }

    /** Inter 500, for row headings and other UI labels. */
    public static Typeface medium(final Context context) {
        return get(context, R.font.inter_medium, Typeface.NORMAL);
    }

    /** Inter 700, for emphasis such as an unread conversation name. */
    public static Typeface bold(final Context context) {
        return get(context, R.font.inter_bold, Typeface.BOLD);
    }

    /**
     * Applies {@code face} to {@code view}, adding synthetic slant when {@code italic} is set.
     *
     * <p>Safe to call repeatedly on a recycled view: the face is always re-derived from {@code
     * face} rather than from whatever the view happened to be showing, so emphasis does not
     * accumulate.
     */
    public static void apply(
            final TextView view, @Nullable final Typeface face, final boolean italic) {
        view.setTypeface(face, italic ? Typeface.ITALIC : Typeface.NORMAL);
    }

    private static Typeface get(
            final Context context, @FontRes final int font, final int fallbackStyle) {
        try {
            final var typeface = ResourcesCompat.getFont(context, font);
            if (typeface != null) {
                return typeface;
            }
        } catch (final RuntimeException e) {
            // A missing or unparsable font resource must not take a list binding down with it.
        }
        return Typeface.defaultFromStyle(fallbackStyle);
    }
}
