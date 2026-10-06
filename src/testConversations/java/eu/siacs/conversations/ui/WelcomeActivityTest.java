package eu.siacs.conversations.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.persistance.DatabaseBackend.AccountWithOptions;
import eu.siacs.conversations.ui.WelcomeActivity.ExistingAccountTarget;
import eu.siacs.conversations.xmpp.Jid;
import java.util.List;
import org.junit.Test;

/**
 * The welcome screen leads with the provider-independent XMPP paths, so "I already have an account"
 * is the button most people press first. These cover where it goes.
 *
 * <p>Scoped to the conversations flavour: the Quicksy flavour has no welcome screen of this shape.
 */
public class WelcomeActivityTest {

    private static AccountWithOptions account(final String jid) {
        return new AccountWithOptions(Jid.ofUserInput(jid), 0);
    }

    @Test
    public void noAccountsYetGoesToAFreshAccountSetup() {
        final ExistingAccountTarget target = WelcomeActivity.existingAccountTarget(List.of());

        assertFalse(
                "a first-time user should not land on the account list",
                target.manageAccounts());
        assertNull("there is no account to resume", target.resume());
    }

    @Test
    public void singleAccountIsResumedRatherThanAddedAgain() {
        final ExistingAccountTarget target =
                WelcomeActivity.existingAccountTarget(List.of(account("test@example.com")));

        assertFalse(target.manageAccounts());
        assertEquals(Jid.ofUserInput("test@example.com"), target.resume());
    }

    @Test
    public void resumedAccountIsStrippedToItsBareJid() {
        final ExistingAccountTarget target =
                WelcomeActivity.existingAccountTarget(
                        List.of(account("test@example.com/phone")));

        // EditAccountActivity looks the account up by bare JID, so a stored resource must not
        // leak into the extra or the prefill silently misses.
        assertEquals(Jid.ofUserInput("test@example.com"), target.resume());
    }

    @Test
    public void severalAccountsGoToTheAccountList() {
        final ExistingAccountTarget target =
                WelcomeActivity.existingAccountTarget(
                        List.of(account("a@example.com"), account("b@example.com")));

        assertTrue(target.manageAccounts());
        assertNull("the account list picks the account itself", target.resume());
    }
}
