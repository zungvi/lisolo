package eu.siacs.conversations.ai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The verdict rule matters more than the model: a warning accuses a real person of fraud, so
 * anything short of a clear SCAM has to pass silently.
 */
public class ScamTriageTest {

    @Test
    public void clearScamVerdictWarns() {
        assertTrue(ScamTriage.isScamVerdict(AiTask.Outcome.success("SCAM")));
        assertTrue(ScamTriage.isScamVerdict(AiTask.Outcome.success("scam")));
        assertTrue(ScamTriage.isScamVerdict(AiTask.Outcome.success("  SCAM\n")));
    }

    @Test
    public void okVerdictDoesNotWarn() {
        assertFalse(ScamTriage.isScamVerdict(AiTask.Outcome.success("OK")));
    }

    /** A network failure must never be read as an accusation. */
    @Test
    public void failedCallDoesNotWarn() {
        assertFalse(ScamTriage.isScamVerdict(AiTask.Outcome.failure("Could not reach Zungvi AI.")));
    }

    @Test
    public void emptyAnswerDoesNotWarn() {
        assertFalse(ScamTriage.isScamVerdict(AiTask.Outcome.success("")));
    }

    /** Models drift; unexpected wording is not a verdict. */
    @Test
    public void unexpectedWordingDoesNotWarn() {
        assertFalse(ScamTriage.isScamVerdict(AiTask.Outcome.success("I am not sure about this.")));
        assertFalse(ScamTriage.isScamVerdict(AiTask.Outcome.success("This might be a scam")));
    }

    @Test
    public void shortGreetingsAreNotWorthChecking() {
        assertFalse(ScamTriage.isWorthChecking("hi"));
        assertFalse(ScamTriage.isWorthChecking("Mambo"));
        assertFalse(ScamTriage.isWorthChecking("   "));
        assertFalse(ScamTriage.isWorthChecking(null));
    }

    @Test
    public void realOpeningMessagesAreChecked() {
        assertTrue(ScamTriage.isWorthChecking("Jambo, nimepewa namba yako na dada yako Grace."));
        assertTrue(ScamTriage.isWorthChecking("You won $5000! Send bank details."));
    }
}
