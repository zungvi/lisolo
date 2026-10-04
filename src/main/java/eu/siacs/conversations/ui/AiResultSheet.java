package eu.siacs.conversations.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.databinding.DataBindingUtil;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ai.AiTask;
import eu.siacs.conversations.databinding.SheetAiResultBinding;

/**
 * Shows the answer from an AI feature: a translation, or a catch-up summary.
 *
 * <p>The sheet is opened before the answer exists, so the wait is visible in the place the answer
 * will appear rather than as a toast or a frozen menu. The caller hands the finished outcome back
 * through {@link #deliver}.
 */
public class AiResultSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ai_result";

    private static final String ARG_TITLE = "title";

    private SheetAiResultBinding binding;

    /** Held for the case where the answer lands before the view does. */
    private AiTask.Outcome pending;

    private String pendingSource;

    private String original;

    public static AiResultSheet create(@StringRes final int title) {
        final AiResultSheet sheet = new AiResultSheet();
        final Bundle arguments = new Bundle();
        arguments.putInt(ARG_TITLE, title);
        sheet.setArguments(arguments);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull final LayoutInflater inflater,
            @Nullable final ViewGroup container,
            @Nullable final Bundle savedInstanceState) {
        this.binding = DataBindingUtil.inflate(inflater, R.layout.sheet_ai_result, container, false);
        return this.binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final Bundle arguments = getArguments();
        this.binding.title.setText(arguments == null ? 0 : arguments.getInt(ARG_TITLE));
        this.binding.actionDone.setOnClickListener(v -> dismiss());
        this.binding.actionCopy.setOnClickListener(v -> copyResult());
        if (this.original != null) {
            this.binding.original.setText(this.original);
            this.binding.original.setVisibility(View.VISIBLE);
        }
        if (this.pending != null) {
            render(this.pending, this.pendingSource);
        }
    }

    /**
     * Hands the finished answer to the sheet.
     *
     * @param source a short note on where it came from, such as on-device translation, or null.
     */
    /** Shows the text the answer was produced from, above the answer itself. */
    public void showOriginal(@NonNull final String text) {
        this.original = text;
        if (this.binding != null) {
            this.binding.original.setText(text);
            this.binding.original.setVisibility(View.VISIBLE);
        }
    }

    public void deliver(@NonNull final AiTask.Outcome outcome, @Nullable final String source) {
        this.pending = outcome;
        this.pendingSource = source;
        if (this.binding != null) {
            render(outcome, source);
        }
    }

    private void render(final AiTask.Outcome outcome, @Nullable final String source) {
        this.binding.progress.setVisibility(View.GONE);
        if (outcome.isSuccess()) {
            this.binding.resultScroll.setVisibility(View.VISIBLE);
            this.binding.error.setVisibility(View.GONE);
            this.binding.actions.setVisibility(View.VISIBLE);
            this.binding.result.setText(outcome.text);
            if (source != null) {
                this.binding.source.setText(source);
                this.binding.source.setVisibility(View.VISIBLE);
            }
        } else {
            this.binding.resultScroll.setVisibility(View.GONE);
            this.binding.error.setVisibility(View.VISIBLE);
            // Copy would have nothing to copy, so only the dismiss action stays.
            this.binding.actions.setVisibility(View.VISIBLE);
            this.binding.actionCopy.setVisibility(View.GONE);
            this.binding.error.setText(
                    outcome.errorMessage == null
                            ? getString(R.string.ai_generic_error)
                            : outcome.errorMessage);
        }
    }

    private void copyResult() {
        final CharSequence text = this.binding.result.getText();
        if (text == null || text.length() == 0) {
            return;
        }
        final Context context = requireContext();
        final var clipboard =
                (android.content.ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.app_name), text));
        dismiss();
    }
}
