package eu.siacs.conversations.ai;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Runs an AI call off the main thread and delivers the answer back on it.
 *
 * <p>Deliberately small: the AI features are user-initiated one-shots, so there is nothing here
 * to cancel, queue or retry. A single thread keeps them from piling up if somebody taps twice.
 */
public final class AiTask {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AiTask() {}

    /** What a finished AI call produced: some text, or a reason there is none. */
    public static final class Outcome {
        @Nullable public final String text;
        @Nullable public final String errorMessage;

        Outcome(@Nullable final String text, @Nullable final String errorMessage) {
            this.text = text;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return this.text != null && !this.text.isEmpty();
        }

        static Outcome of(final ZungviAi.Result result) {
            return new Outcome(result.text, result.errorMessage);
        }

        static Outcome success(final String text) {
            return new Outcome(text, null);
        }

        static Outcome failure(final String message) {
            return new Outcome(null, message);
        }
    }

    public interface Callback {
        void onFinished(@NonNull Outcome outcome);
    }

    static void run(final Supplier<Outcome> work, final Callback callback) {
        EXECUTOR.execute(
                () -> {
                    final Outcome outcome;
                    try {
                        outcome = work.get();
                    } catch (final RuntimeException e) {
                        // A crash in a convenience feature must not take the chat down with it.
                        MAIN.post(() -> callback.onFinished(Outcome.failure("Something went wrong.")));
                        return;
                    }
                    MAIN.post(() -> callback.onFinished(outcome));
                });
    }
}
