package eu.siacs.conversations.ai;

/**
 * System prompts for the AI features.
 *
 * <p>These are not throwaway wording. Each one was arrived at by measuring the alternatives
 * against real Lisolo content, and the comments record what the earlier versions got wrong, so
 * that a future edit does not quietly reintroduce a failure that was already paid for.
 */
final class AiPrompts {

    private AiPrompts() {}

    /**
     * Flagging strangers.
     *
     * <p>A plain "is this a scam?" instruction produced a 57% false positive rate on legitimate
     * first messages and, worse, was biased by language: "Jambo, nimepewa namba yako na dada yako
     * Grace" was flagged while its English translation passed. Naming the fraud signals, saying
     * outright that an unfamiliar language is not one, and giving examples in the languages
     * Lisolo users actually write fixed it.
     */
    static final String SCAM_TRIAGE =
            """
            Classify the first message from an unknown sender. Answer one word: SCAM or OK.

            SCAM only if it shows a clear fraud signal: asking for a PIN, password, bank or \
            Mobile Money details; claiming a prize, lottery or inheritance; demanding urgent \
            payment; promising easy money; or a link to claim money.

            OK for everything else. Greetings, introductions, saying who referred them, asking \
            to meet, asking a question or discussing work are all OK. Messages in Swahili, \
            Lingala or French are just as likely to be OK as English ones - an unfamiliar \
            language is NOT a fraud signal.

            Examples:
            "Jambo, nimepewa namba yako na dada yako Grace." -> OK
            "Mambo, naomba tuonane kesho ofisini." -> OK
            "Mbote, nazali Pascal, toli ya Jean." -> OK
            "Bonjour, je suis agent MTN, donnez votre code PIN." -> SCAM
            "You won $5000! Send bank details to claim." -> SCAM\
            """;

    /**
     * Summarising a group backlog.
     *
     * <p>The instruction to scan to the end before reporting is the important part. Without it
     * the model summarised the opening of the thread and announced problems as unresolved that
     * the group had already fixed further down - the one mistake a catch-up feature cannot make,
     * because the reader has no way to notice it.
     */
    static String catchUp(final String languageName) {
        return """
            Summarise a group chat for someone who was away. Write in %s.

            Cover EVERY distinct topic - do not drop any.
            For each one, scan to the END of the conversation and report ONLY the final state. \
            Never present a solved problem as still open.

            Format exactly:
            DONE
            - <topic>: <final outcome>
            OPEN
            - <item>

            Leave a section empty if it has nothing. No preamble.\
            """
                .formatted(languageName);
    }

    /** Translation, used only for the languages Zungvi AI handles well. */
    static String translate(final String languageName) {
        return "Translate the message into %s. Output only the translation, with no preamble, "
                .formatted(languageName)
                + "no quotes and no explanation.";
    }
}
