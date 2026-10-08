package eu.siacs.conversations.ai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * With ML Kit gone, the translation prompt is the only thing standing between the reader and a
 * server model that is weak at Swahili. These tests pin the two wordings that measurably changed
 * its behaviour, so a later tidy-up cannot quietly drop them.
 *
 * <p>They assert what we ask for, not what the model answers - that needs the network and a human
 * reading Swahili. The measurements behind each assertion are recorded in {@link
 * MessageTranslator}.
 */
public class MessageTranslatorTest {

    @Test
    public void promptNamesTheTargetLanguage() {
        assertTrue(AiPrompts.translate("French").contains("into French"));
        assertTrue(AiPrompts.translate("Swahili").contains("into Swahili"));
    }

    /**
     * The load-bearing instruction. Asked only to fix the clock, the model replied "9:00 AM" and
     * discarded the sentence, so the reader lost the message entirely.
     */
    @Test
    public void promptDemandsTheWholeSentenceNotJustTheTime() {
        final String prompt = AiPrompts.translate("English");
        assertTrue(prompt.contains("whole sentence"));
        assertTrue(prompt.contains("not only the"));
    }

    /** Swahili reckons hours from six; a literal reading is three hours out and reads plausibly. */
    @Test
    public void promptExplainsSwahiliHourReckoning() {
        final String prompt = AiPrompts.translate("English");
        assertTrue(prompt.contains("Swahili"));
        assertTrue(prompt.contains("saa tatu"));
        assertTrue(prompt.contains("from six"));
        assertTrue(prompt.contains("nine in the morning"));
    }

    @Test
    public void promptAsksForNothingButTheTranslation() {
        final String prompt = AiPrompts.translate("English");
        assertTrue(prompt.contains("no preamble"));
        assertTrue(prompt.contains("no quotes"));
        assertTrue(prompt.contains("no explanation"));
    }

    /**
     * The sentinel was withdrawn after testing: the model answered it for Swahili input as
     * readily as for English, which would have told a Swahili reader their message was already in
     * their own language and shown them nothing. If it ever comes back it needs a real language
     * detector behind it, not the model's own say-so.
     */
    @Test
    public void promptDoesNotAskForAnAlreadyInTargetSentinel() {
        final String prompt = AiPrompts.translate("English");
        assertFalse(prompt.contains("ALREADY_IN_TARGET"));
        assertFalse(prompt.toLowerCase().contains("already in"));
    }
}
