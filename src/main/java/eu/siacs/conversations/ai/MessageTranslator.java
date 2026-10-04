package eu.siacs.conversations.ai;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.TranslatorOptions;
import eu.siacs.conversations.Config;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Translates a single message, choosing where to do it by language.
 *
 * <p>Swahili is handled on the device by ML Kit. Zungvi AI gets it wrong in ways the reader
 * cannot catch - in testing it rendered "saa tatu asubuhi" as "3 AM" when Swahili counts hours
 * from six, which is the sort of mistake that makes somebody miss a meeting. French and English
 * it handles well, so those go to the server where quality is better than ML Kit's.
 *
 * <p>On-device translation is also the only route that keeps the message off the network, which
 * is why it is preferred wherever it is good enough.
 */
public final class MessageTranslator {

    /** ML Kit downloads a model per language pair; allow for a slow first run on mobile data. */
    private static final long MODEL_TIMEOUT_SECONDS = 120;

    private static final long TRANSLATE_TIMEOUT_SECONDS = 30;

    private MessageTranslator() {}

    /** Reports the translation together with whether it stayed on the device. */
    public interface Callback {
        void onTranslated(@NonNull AiTask.Outcome outcome, boolean onDevice);
    }

    /** Languages ML Kit handles well enough to keep the text on the device. */
    private static boolean preferOnDevice(final String languageTag) {
        return "sw".equals(languageTag);
    }

    public static void translate(
            @NonNull final String body,
            @NonNull final Locale target,
            @NonNull final Callback callback) {
        // Whether the work stayed on the device is decided inside the blocking call, so it is
        // recorded there and read once the outcome comes back.
        final boolean[] onDevice = new boolean[1];
        AiTask.run(
                () -> translateBlocking(body, target, onDevice),
                outcome -> callback.onTranslated(outcome, onDevice[0]));
    }

    private static AiTask.Outcome translateBlocking(
            final String body, final Locale target, final boolean[] onDeviceOut) {
        final String source = identifyLanguage(body);
        final String targetTag = target.getLanguage();
        if (source != null && source.equals(targetTag)) {
            return AiTask.Outcome.failure("already_in_target");
        }
        if (preferOnDevice(source)) {
            final AiTask.Outcome onDevice = onDevice(body, source, targetTag);
            if (onDevice != null) {
                onDeviceOut[0] = true;
                return onDevice;
            }
            // Falling through to the server is better than failing outright, but it is a quality
            // downgrade for exactly the language that needed on-device translation, so say so.
            Log.d(Config.LOGTAG, "ZUNGVIAI on-device translation unavailable, using server");
        }
        return AiTask.Outcome.of(
                ZungviAi.chat(AiPrompts.translate(target.getDisplayLanguage(Locale.ENGLISH)), body));
    }

    /** Returns null when ML Kit cannot do this pair, so the caller can fall back. */
    private static AiTask.Outcome onDevice(
            final String body, final String sourceTag, final String targetTag) {
        final String source = TranslateLanguage.fromLanguageTag(sourceTag);
        final String target = TranslateLanguage.fromLanguageTag(targetTag);
        if (source == null || target == null) {
            return null;
        }
        final TranslatorOptions options =
                new TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build();
        try (final com.google.mlkit.nl.translate.Translator translator =
                Translation.getClient(options)) {
            Tasks.await(
                    translator.downloadModelIfNeeded(), MODEL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            final String translated =
                    Tasks.await(translator.translate(body), TRANSLATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (translated == null || translated.isBlank()) {
                return null;
            }
            return AiTask.Outcome.success(translated.trim());
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "ZUNGVIAI on-device translation failed", e);
            return null;
        }
    }

    /** BCP-47 tag, or null when ML Kit cannot tell. */
    private static String identifyLanguage(final String body) {
        try {
            final String tag =
                    Tasks.await(
                            LanguageIdentification.getClient().identifyLanguage(body),
                            15,
                            TimeUnit.SECONDS);
            return "und".equals(tag) ? null : tag;
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "ZUNGVIAI language identification failed", e);
            return null;
        }
    }
}
