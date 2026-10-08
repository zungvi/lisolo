package eu.siacs.conversations.ai;

import androidx.annotation.NonNull;
import java.util.Locale;

/**
 * Translates a single message through Zungvi AI.
 *
 * <p>Every translation leaves the device. Swahili used to be translated on the phone by ML Kit,
 * but that is proprietary Google code shipped as native libraries inside the APK, and this app is
 * GPLv3 - we cannot hand a user the corresponding source for a blob we do not have. So it is
 * gone, and with it the only route that kept a message off the network.
 *
 * <p><b>Swahili quality is poor and this class cannot fix it.</b> Measured against the live
 * endpoint at temperature 0: "Nitakuja kesho" ("I will come tomorrow") came back as "I came
 * yesterday", and "Niko huru saa kumi na mbili jioni" ("I am free at 6 PM") as "Niko has 13
 * stones". Clock times were wrong in four cases of six. The large tier was no better - it read
 * "saa tatu asubuhi" as three in the afternoon. The prompt in {@link AiPrompts#translate} stops
 * the model answering with a bare clock time and nothing else, which is as far as a prompt gets;
 * the rest has to be fixed at the server, where the model is chosen.
 *
 * <p>There is deliberately no "already in the reader's language" short-circuit. That used to come
 * from ML Kit's language identification. Asking the model instead was tried and withdrawn: it
 * answered the sentinel for Swahili input as readily as for English, which would have suppressed
 * translation of exactly the language that needs it most.
 */
public final class MessageTranslator {

    /**
     * A translation is about as long as its input, and chat messages are short. This is generous
     * for one message and still cuts off a model that starts rambling instead of translating.
     */
    private static final int TRANSLATION_MAX_TOKENS = 512;

    private MessageTranslator() {}

    public static void translate(
            @NonNull final String body,
            @NonNull final Locale target,
            @NonNull final AiTask.Callback callback) {
        AiTask.run(() -> translateBlocking(body, target), callback);
    }

    private static AiTask.Outcome translateBlocking(final String body, final Locale target) {
        // Medium, named explicitly rather than left to the server: translating one chat message
        // is short work that the medium tier does well, and asking for it keeps the request off
        // whatever larger model the API project pins. The large tier was measured and is no
        // better at the language that actually struggles here.
        return AiTask.Outcome.of(
                ZungviAi.chat(
                        ZungviAi.MODEL_MEDIUM,
                        AiPrompts.translate(target.getDisplayLanguage(Locale.ENGLISH)),
                        body,
                        TRANSLATION_MAX_TOKENS));
    }
}
