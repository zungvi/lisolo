package eu.siacs.conversations.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.AppBarLayout;
import java.util.List;
import com.google.android.material.elevation.SurfaceColors;

/**
 * Window chrome and window-inset plumbing.
 *
 * <p>The app targets SDK 35+, where edge-to-edge is mandatory and {@code
 * Window#setStatusBarColor}, {@code Window#setNavigationBarColor} and {@code
 * View#setSystemUiVisibility} are all no-ops. Bar colour therefore comes from whatever the app
 * draws behind the bars, and the only things we still set directly are the icon appearance (via
 * {@link WindowCompat}) and the insets we hand out to our own views.
 *
 * <p>On older releases we keep painting the bars the way the app always did, so the look does not
 * regress on API 23–34.
 */
public final class Activities {

    /** Edge-to-edge is enforced by the platform from this level up. */
    private static final int EDGE_TO_EDGE_ENFORCED = 35;

    private Activities() {}

    public static void setStatusAndNavigationBarColors(final Activity activity, final View view) {
        setStatusAndNavigationBarColors(activity, view, false);
    }

    public static void setStatusAndNavigationBarColors(
            final Activity activity, final View view, final boolean raisedStatusBar) {
        setStatusAndNavigationBarColors(activity, view, isLightMode(activity), raisedStatusBar);
    }

    /**
     * The default treatment: draw behind the system bars, push the app bar down below the status
     * bar, and keep the bottom of the content clear of the navigation bar.
     */
    public static void setStatusAndNavigationBarColors(
            final Activity activity,
            final View view,
            final boolean isLightMode,
            final boolean raisedStatusBar) {
        applyWindowChrome(activity, isLightMode, raisedStatusBar);
        applySystemBarInsets(view);
    }

    /**
     * Window chrome only — no inset dispatch. Use this when the screen needs to route insets
     * itself, for example because a fragment owns a view that has to follow the keyboard.
     */
    public static void applyWindowChrome(
            final Activity activity, final boolean isLightMode, final boolean raisedStatusBar) {
        final var window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        final var controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(isLightMode);
        controller.setAppearanceLightNavigationBars(isLightMode);
        if (Build.VERSION.SDK_INT < EDGE_TO_EDGE_ENFORCED) {
            applyLegacyBarColors(activity, raisedStatusBar);
        }
    }

    public static void applyWindowChrome(final Activity activity) {
        applyWindowChrome(activity, isLightMode(activity), false);
    }

    /**
     * The default inset routing: the status bar inset goes to the first {@link AppBarLayout} in the
     * tree so its surface extends behind the bar, and the remaining edges go to the root. The
     * insets are then reported consumed, so a child still declaring {@code fitsSystemWindows}
     * cannot pad a second time.
     *
     * <p>Original paddings are captured up front and added to, rather than overwritten, because
     * this listener runs again on every configuration change and rotation.
     */
    public static void applySystemBarInsets(final View root) {
        final var appBar = findAppBarLayout(root);
        final int appBarPaddingTop = appBar == null ? 0 : appBar.getPaddingTop();
        final int rootPaddingLeft = root.getPaddingLeft();
        final int rootPaddingTop = root.getPaddingTop();
        final int rootPaddingRight = root.getPaddingRight();
        final int rootPaddingBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (v, windowInsets) -> {
                    // displayCutout matters in landscape, where a notch sits on a vertical edge
                    // that the system bars alone do not cover.
                    final var bars =
                            windowInsets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.displayCutout());
                    if (appBar == null) {
                        v.setPadding(
                                rootPaddingLeft + bars.left,
                                rootPaddingTop + bars.top,
                                rootPaddingRight + bars.right,
                                rootPaddingBottom + bars.bottom);
                    } else {
                        appBar.setPadding(
                                appBar.getPaddingLeft(),
                                appBarPaddingTop + bars.top,
                                appBar.getPaddingRight(),
                                appBar.getPaddingBottom());
                        v.setPadding(
                                rootPaddingLeft + bars.left,
                                rootPaddingTop,
                                rootPaddingRight + bars.right,
                                rootPaddingBottom + bars.bottom);
                    }
                    return WindowInsetsCompat.CONSUMED;
                });
        requestInsets(root);
    }

    /** Pads {@code view} by the status bar height, leaving the rest of the tree alone. */
    public static void applyTopInset(final View view) {
        final int paddingTop = view.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(
                view,
                (v, windowInsets) -> {
                    final var bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(
                            v.getPaddingLeft(),
                            paddingTop + bars.top,
                            v.getPaddingRight(),
                            v.getPaddingBottom());
                    return windowInsets;
                });
        requestInsets(view);
    }

    /**
     * Lifts {@code view} clear of the navigation bar, and of the keyboard while it is open, by
     * growing its bottom margin.
     *
     * <p>Margin rather than padding. Padding grows the view's own box while its bottom edge stays
     * pinned to the parent, which squashes any child laid out with {@code layout_centerVertical}
     * into the remaining sliver of content box — for the composer that pushed the input field and
     * the send button off screen entirely. A margin moves the whole view and leaves its internal
     * layout untouched.
     *
     * <p>The margin is also recomputed on every frame of the keyboard animation, because the
     * platform otherwise reports IME insets only once the animation has finished and the view
     * would snap into position after the keyboard had already opened.
     */
    public static void applyBottomMarginInsetWithIme(final View view) {
        final var layoutParams = view.getLayoutParams();
        if (!(layoutParams instanceof ViewGroup.MarginLayoutParams marginLayoutParams)) {
            return;
        }
        final int marginBottom = marginLayoutParams.bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(
                view,
                (v, windowInsets) -> {
                    applyImeMargin(v, windowInsets, marginBottom);
                    return windowInsets;
                });
        ViewCompat.setWindowInsetsAnimationCallback(
                view,
                new WindowInsetsAnimationCompat.Callback(
                        WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    @NonNull
                    @Override
                    public WindowInsetsCompat onProgress(
                            @NonNull final WindowInsetsCompat windowInsets,
                            @NonNull final List<WindowInsetsAnimationCompat> running) {
                        applyImeMargin(view, windowInsets, marginBottom);
                        return windowInsets;
                    }
                });
        requestInsets(view);
    }

    private static void applyImeMargin(
            final View view, final WindowInsetsCompat windowInsets, final int marginBottom) {
        final var params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams marginLayoutParams)) {
            return;
        }
        final var bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
        final var ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
        // Whichever is taller, never the sum. An open keyboard already covers the navigation bar,
        // whether that bar is the tall three-button one or a slim gesture handle, so adding both
        // would leave a gap the height of the bar above the keyboard.
        final int bottom = marginBottom + Math.max(bars.bottom, ime.bottom);
        if (marginLayoutParams.bottomMargin == bottom) {
            return;
        }
        marginLayoutParams.bottomMargin = bottom;
        view.setLayoutParams(marginLayoutParams);
    }

    /** Pads {@code view} by the navigation bar height only. */
    public static void applyBottomInset(final View view) {
        final int paddingBottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(
                view,
                (v, windowInsets) -> {
                    final var bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(
                            v.getPaddingLeft(),
                            v.getPaddingTop(),
                            v.getPaddingRight(),
                            paddingBottom + bars.bottom);
                    return windowInsets;
                });
        requestInsets(view);
    }

    /**
     * Grows {@code view}'s bottom margin by the navigation bar height. Floating views such as a FAB
     * need this rather than padding, which would only inflate their touch target.
     */
    public static void applyBottomMarginInset(final View view) {
        final var layoutParams = view.getLayoutParams();
        if (!(layoutParams instanceof ViewGroup.MarginLayoutParams marginLayoutParams)) {
            return;
        }
        final int marginBottom = marginLayoutParams.bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(
                view,
                (v, windowInsets) -> {
                    final var bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    final var params = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                    params.bottomMargin = marginBottom + bars.bottom;
                    v.setLayoutParams(params);
                    return windowInsets;
                });
        requestInsets(view);
    }

    /**
     * Asks for an insets pass, deferring until the view is attached when it is not yet.
     *
     * <p>Views configured from {@code onCreateView} are never attached at that point, and the
     * window has usually already dispatched its insets by then. Simply skipping the request left
     * any fragment added after that first dispatch — the chat screen, which is created when a
     * conversation is tapped — with a listener that never ran, so its composer sat under the
     * navigation bar and behind the keyboard.
     */
    private static void requestInsets(final View view) {
        if (view.isAttachedToWindow()) {
            ViewCompat.requestApplyInsets(view);
            return;
        }
        view.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(@NonNull final View v) {
                        v.removeOnAttachStateChangeListener(this);
                        ViewCompat.requestApplyInsets(v);
                    }

                    @Override
                    public void onViewDetachedFromWindow(@NonNull final View v) {}
                });
    }

    private static AppBarLayout findAppBarLayout(final View view) {
        if (view instanceof AppBarLayout appBarLayout) {
            return appBarLayout;
        }
        if (view instanceof ViewGroup viewGroup) {
            for (int i = 0; i < viewGroup.getChildCount(); ++i) {
                final var match = findAppBarLayout(viewGroup.getChildAt(i));
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation") // no-ops on 35+; guarded by the caller
    private static void applyLegacyBarColors(
            @NonNull final Activity activity, final boolean raisedStatusBar) {
        final var window = activity.getWindow();
        // an elevation of 4 matches the MaterialToolbar elevation
        if (raisedStatusBar) {
            window.setStatusBarColor(SurfaceColors.SURFACE_5.getColor(activity));
        } else {
            window.setStatusBarColor(SurfaceColors.SURFACE_0.getColor(activity));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.setNavigationBarColor(SurfaceColors.SURFACE_1.getColor(activity));
        }
    }

    private static boolean isLightMode(final Context context) {
        return !isNightMode(context);
    }

    public static boolean isNightMode(final Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
