package eu.siacs.conversations.xmpp.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.xmpp.mam.MamReference;
import im.conversations.android.xmpp.IqErrorException;
import im.conversations.android.xmpp.model.error.Condition;
import im.conversations.android.xmpp.model.error.Error;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.List;
import org.junit.Test;

public class MessageArchiveManagerTest {

    @Test
    public void completedRecentSyncDoesNotExhaustOlderHistory() {
        final var query =
                new MessageArchiveManager.Query(null, new MamReference(1_000), 2_000, false);

        assertFalse(query.isHistoryBackfill());
        assertFalse(MessageArchiveManager.isHistoryComplete(query, true));
    }

    @Test
    public void completedCatchupDoesNotExhaustOlderHistory() {
        final var query =
                new MessageArchiveManager.Query(null, new MamReference(0), 2_000, true);

        assertFalse(query.isHistoryBackfill());
        assertFalse(MessageArchiveManager.isHistoryComplete(query, true));
    }

    @Test
    public void duplicateOnlyPageStillAllowsOlderHistoryUntilServerCompletes() {
        final var query =
                new MessageArchiveManager.Query(null, new MamReference(0), 2_000, false);
        for (int i = 0; i < 50; ++i) {
            query.incrementMessageCount();
        }

        assertEquals(0, query.getActualMessageCount());
        assertFalse(MessageArchiveManager.isHistoryComplete(query, false));
        assertTrue(MessageArchiveManager.isHistoryComplete(query, true));
    }

    @Test
    public void retentionBoundStillCountsAsFullAvailableHistory() {
        // The user's original start is zero, but the manager applies local retention before
        // constructing the wire query. That bound must not turn history into a recent sync.
        final var query =
                new MessageArchiveManager.Query(null, new MamReference(1_000), 2_000, false, true);

        assertTrue(query.isHistoryBackfill());
        assertTrue(MessageArchiveManager.isHistoryComplete(query, true));
    }

    @Test
    public void historyPagesPreserveScopeAndCounts() {
        final var query =
                new MessageArchiveManager.Query(null, new MamReference(0), 2_000, false);
        query.incrementMessageCount();
        query.incrementActualMessageCount();

        final var olderPage = query.prev("oldest-id");

        assertTrue(olderPage.isHistoryBackfill());
        assertEquals(MessageArchiveManager.PagingOrder.REVERSE, olderPage.getPagingOrder());
        assertEquals("oldest-id", olderPage.getReference());
        assertEquals(1, olderPage.getTotalCount());
        assertEquals(1, olderPage.getActualMessageCount());
        assertEquals(0, olderPage.getActualInThisQuery());
        assertFalse(MessageArchiveManager.isHistoryComplete(olderPage, false));
        assertTrue(MessageArchiveManager.isHistoryComplete(olderPage, true));
    }

    @Test
    public void recentSyncPagesNeverBecomeFullHistory() {
        final var query =
                new MessageArchiveManager.Query(null, new MamReference(1_000), 2_000, false);

        assertFalse(query.prev("recent-id").isHistoryBackfill());
        assertFalse(MessageArchiveManager.isHistoryComplete(query.prev("recent-id"), true));
    }

    @Test
    public void receiptAndDuplicatePagesDoNotUseTheVisibleHistoryBudget() {
        final var query =
                new MessageArchiveManager.Query(null, new MamReference(0), 2_000, false);
        for (int i = 0; i < 100; ++i) {
            query.incrementMessageCount();
        }

        assertFalse(MessageArchiveManager.hasReachedPageBudget(query));
        for (int i = 0; i < 49; ++i) {
            query.incrementActualMessageCount();
        }
        assertFalse(MessageArchiveManager.hasReachedPageBudget(query));
        query.incrementActualMessageCount();
        assertTrue(MessageArchiveManager.hasReachedPageBudget(query));
    }

    @Test
    public void duplicateOnlyHistoryStillHasABoundedRawStanzaBudget() {
        final var query =
                new MessageArchiveManager.Query(null, new MamReference(0), 2_000, false);
        for (int i = 0; i < 749; ++i) {
            query.incrementMessageCount();
        }

        assertFalse(MessageArchiveManager.hasReachedPageBudget(query));
        query.incrementMessageCount();
        assertTrue(MessageArchiveManager.hasReachedPageBudget(query));
    }

    @Test
    public void catchupCanContinuePastOneVisiblePage() {
        final var query =
                new MessageArchiveManager.Query(null, new MamReference(1_000), 2_000, true);
        for (int i = 0; i < 50; ++i) {
            query.incrementMessageCount();
            query.incrementActualMessageCount();
        }

        assertFalse(MessageArchiveManager.hasReachedPageBudget(query));
    }

    @Test
    public void recentSyncCannotSwallowAnOlderHistoryRequest() {
        final var recent =
                new MessageArchiveManager.Query(null, new MamReference(1_000), 2_000, false);
        final var catchup =
                new MessageArchiveManager.Query(null, new MamReference(1_000), 2_000, true);
        final var older =
                new MessageArchiveManager.Query(null, new MamReference(0), 1_000, false);

        assertEquals(null, MessageArchiveManager.findHistoryQuery(List.of(recent, catchup), null));
        assertEquals(
                older, MessageArchiveManager.findHistoryQuery(List.of(recent, older, catchup), null));
    }

    @Test
    public void cancellingAQueryPreservesAnyNewerHistoryLoadGuard() {
        final var cancelled =
                new MessageArchiveManager.Query(null, new MamReference(0), 2_000, false);
        final var newer = cancelled.prev("older-id");
        final var recent =
                new MessageArchiveManager.Query(null, new MamReference(1_000), 2_000, false);

        assertFalse(
                MessageArchiveManager.shouldReleaseCancelledLoadGuard(
                        cancelled, true, List.of(newer)));
        assertTrue(
                MessageArchiveManager.shouldReleaseCancelledLoadGuard(
                        cancelled, true, List.of(recent)));
        assertFalse(
                MessageArchiveManager.shouldReleaseCancelledLoadGuard(
                        cancelled, false, List.of()));
    }

    @Test
    public void historyPagesAndCursorFallbackKeepTheRetentionLowerBound() {
        final var query =
                new MessageArchiveManager.Query(null, new MamReference(1_000), 2_000, false, true);
        final var olderPage = query.prev("older-id");

        assertEquals(1_000, olderPage.getStart());
        assertEquals(2_000, olderPage.getEnd());
        assertEquals(1_000, olderPage.prev("oldest-id").getStart());
        assertEquals(1_000, olderPage.retryWithoutReference().getStart());
    }

    @Test
    public void expiredHistoryCursorIsRetriedOnlyOnceAndPreservesTimeBounds() {
        final var query =
                new MessageArchiveManager.Query(null, new MamReference(0), 2_000, false)
                        .prev("expired-id");
        final var error = serverError(new Condition.ItemNotFound());
        assertTrue(MessageArchiveManager.shouldRetryWithoutReference(query, error));

        final var retry = query.retryWithoutReference();
        assertEquals(null, retry.getReference());
        assertEquals(2_000, retry.getEnd());
        assertTrue(retry.isHistoryBackfill());
        assertFalse(MessageArchiveManager.shouldRetryWithoutReference(retry, error));
        assertFalse(
                MessageArchiveManager.shouldRetryWithoutReference(retry.prev("another-id"), error));
    }

    @Test
    public void arbitraryServerErrorsDoNotRestartHistoryOrRecentSync() {
        final var history =
                new MessageArchiveManager.Query(null, new MamReference(0), 2_000, false)
                        .prev("older-id");
        assertFalse(
                MessageArchiveManager.shouldRetryWithoutReference(
                        history, serverError(new Condition.ServiceUnavailable())));
        assertFalse(
                MessageArchiveManager.shouldRetryWithoutReference(
                        history, new IllegalStateException("not a MAM response")));
        final var sync =
                new MessageArchiveManager.Query(null, new MamReference(1_000), 2_000, false)
                        .prev("recent-id");
        assertFalse(
                MessageArchiveManager.shouldRetryWithoutReference(
                        sync, serverError(new Condition.ItemNotFound())));
    }

    private static IqErrorException serverError(final Condition condition) {
        final var iq = new Iq(Iq.Type.ERROR);
        final var error = iq.addExtension(new Error());
        error.setCondition(condition);
        return new IqErrorException(iq);
    }
}
