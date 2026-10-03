package eu.siacs.conversations.xmpp.manager;

import static eu.siacs.conversations.utils.Random.SECURE_RANDOM;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.ReceiptRequest;
import eu.siacs.conversations.generator.AbstractGenerator;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.mam.MamReference;
import im.conversations.android.xmpp.IqErrorException;
import im.conversations.android.xmpp.Range;
import im.conversations.android.xmpp.model.error.Condition;
import im.conversations.android.xmpp.model.mam.Fin;
import im.conversations.android.xmpp.model.mam.Preferences;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class MessageArchiveManager extends AbstractManager {

    private final XmppConnectionService mXmppConnectionService;

    private Preferences.Default currentArchivingPreferences;

    private final HashSet<Query> queries = new HashSet<>();

    public MessageArchiveManager(
            final XmppConnectionService service, final XmppConnection connection) {
        super(service.getApplicationContext(), connection);
        this.mXmppConnectionService = service;
    }

    public void catchup() {
        synchronized (this.queries) {
            final var cancelled = new ArrayList<>(this.queries);
            this.queries.clear();
            for (final Query query : cancelled) {
                releaseCancelledLoadGuard(query, query.hasCallback());
            }
        }
        MamReference mamReference =
                MamReference.max(
                        mXmppConnectionService.databaseBackend.getLastMessageReceived(getAccount()),
                        mXmppConnectionService.databaseBackend.getLastClearDate(getAccount()));
        mamReference =
                MamReference.max(
                        mamReference, mXmppConnectionService.getAutomaticMessageDeletionDate());
        long endCatchup = connection.getLastSessionEstablished();
        final Query query;
        if (mamReference.getTimestamp() == 0) {
            return;
        } else if (endCatchup - mamReference.getTimestamp() >= Config.MAM_MAX_CATCHUP) {
            long startCatchup = endCatchup - Config.MAM_MAX_CATCHUP;
            List<Conversation> conversations = mXmppConnectionService.getConversations();
            for (Conversation conversation : conversations) {
                if (conversation.getMode() == Conversation.MODE_SINGLE
                        && conversation.getAccount() == getAccount()
                        && startCatchup > conversation.getLastMessageTransmitted().getTimestamp()) {
                    this.query(conversation, startCatchup, true);
                }
            }
            query = new Query(new MamReference(startCatchup), 0);
        } else {
            query = new Query(mamReference, 0);
        }
        synchronized (this.queries) {
            this.queries.add(query);
        }
        this.execute(query);
    }

    public void catchupMUC(final Conversation conversation) {
        if (conversation.getLastMessageTransmitted().getTimestamp() < 0
                && conversation.countMessages() == 0) {
            query(conversation, new MamReference(0), 0, true);
        } else {
            query(conversation, conversation.getLastMessageTransmitted(), 0, true);
        }
    }

    public Query query(final Conversation conversation) {
        if (conversation.getLastMessageTransmitted().getTimestamp() < 0
                && conversation.countMessages() == 0) {
            return query(conversation, new MamReference(0), System.currentTimeMillis(), false);
        } else {
            return query(
                    conversation,
                    conversation.getLastMessageTransmitted(),
                    conversation.getAccount().getXmppConnection().getLastSessionEstablished(),
                    false);
        }
    }

    public boolean isCatchingUp(Conversation conversation) {
        final Account account = conversation.getAccount();
        if (account.getXmppConnection().isWaitingForSmCatchup()) {
            return true;
        } else {
            synchronized (this.queries) {
                for (Query query : this.queries) {
                    if (query.isCatchup()
                            && ((conversation.getMode() == Conversation.MODE_SINGLE
                                            && query.getWith() == null)
                                    || query.getConversation() == conversation)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public Query query(final Conversation conversation, long end, boolean allowCatchup) {
        return this.query(
                conversation, conversation.getLastMessageTransmitted(), end, allowCatchup);
    }

    public Query query(
            Conversation conversation, MamReference start, long end, boolean allowCatchup) {
        return query(conversation, start, end, allowCatchup, null);
    }

    public Query query(
            Conversation conversation,
            MamReference start,
            long end,
            boolean allowCatchup,
            XmppConnectionService.OnMoreMessagesLoaded callback) {
        synchronized (this.queries) {
            final Query query;
            final MamReference startActual =
                    MamReference.max(
                            start, mXmppConnectionService.getAutomaticMessageDeletionDate());
            if (end != 0 && startActual.greaterThan(end)) {
                return null;
            }
            if (start.getTimestamp() == 0) {
                query = new Query(conversation, startActual, end, false, true);
                query.reference = conversation.getFirstMamReference();
            } else {
                if (allowCatchup) {
                    MamReference maxCatchup =
                            MamReference.max(
                                    startActual,
                                    System.currentTimeMillis() - Config.MAM_MAX_CATCHUP);
                    if (maxCatchup.greaterThan(startActual)) {
                        Query reverseCatchup =
                                new Query(
                                        conversation,
                                        startActual,
                                        maxCatchup.getTimestamp(),
                                        false);
                        this.queries.add(reverseCatchup);
                        this.execute(reverseCatchup);
                    }
                    query = new Query(conversation, maxCatchup, end, true);
                } else {
                    query = new Query(conversation, startActual, end, false);
                }
            }
            query.setCallback(callback);
            this.queries.add(query);
            this.execute(query);
            return query;
        }
    }

    private ListenableFuture<Fin> execute(
            final Jid service, final im.conversations.android.xmpp.model.mam.Query query) {
        final var iq = new Iq(Iq.Type.SET, service, query);
        return Futures.transform(
                this.connection.sendIqPacket(iq),
                response -> {
                    final var fin = response.getOnlyExtension(Fin.class);
                    if (fin == null) {
                        Log.d(Config.LOGTAG, "response: " + response);
                        throw new IllegalStateException(
                                "Iq response to MAM query did not contain fin");
                    }
                    return fin;
                },
                MoreExecutors.directExecutor());
    }

    private void execute(final Query query) {
        final Conversation conversation = query.getConversation();
        if (conversation != null && conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
            throw new IllegalStateException("Attempted to run MAM query for archived conversation");
        }

        Log.d(
                Config.LOGTAG,
                getAccount().getJid().asBareJid().toString() + ": running mam query " + query);

        final Jid service;

        if (query.muc()) {
            service = query.getWith();
        } else {
            service = getAccount().getJid().asBareJid();
        }

        final var future = execute(service, toQuery(query));

        Futures.addCallback(
                future,
                new FutureCallback<Fin>() {
                    @Override
                    public void onSuccess(Fin result) {
                        final boolean running;
                        synchronized (queries) {
                            running = queries.contains(query);
                        }
                        if (running) {
                            processFin(query, result);
                        } else {
                            Log.d(
                                    Config.LOGTAG,
                                    getAccount().getJid().asBareJid()
                                            + ": ignoring MAM iq result because query had been"
                                            + " killed");
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Throwable t) {
                        synchronized (queries) {
                            if (!queries.contains(query)) {
                                return;
                            }
                        }
                        if (shouldRetryWithoutReference(query, t)) {
                            // Retention or a changed archive can invalidate an RSM cursor. Retry
                            // once using the existing time bound, without discarding local history.
                            Log.d(Config.LOGTAG, "MAM history cursor expired; retrying by timestamp");
                            final Query retry = query.retryWithoutReference();
                            query.getConversation().setFirstMamReference(null);
                            synchronized (queries) {
                                queries.add(retry);
                            }
                            finalizeQuery(query, false, false);
                            execute(retry);
                            return;
                        }
                        Log.d(
                                Config.LOGTAG,
                                getAccount().getJid().asBareJid() + ": error executing mam " + query,
                                t);
                        try {
                            // Errors and timeouts leave archive pagination retryable.
                            finalizeQuery(query, false);
                            if (query.hasCallback()) {
                                query.callback.informUser(R.string.history_load_failed);
                            }
                        } catch (final IllegalStateException e) {
                            // The connection may have cancelled this query concurrently.
                        }
                    }
                },
                MoreExecutors.directExecutor());
    }

    private void finalizeQuery(final Query query, boolean done) {
        finalizeQuery(query, done, true);
    }

    private void finalizeQuery(final Query query, boolean done, final boolean notify) {
        synchronized (this.queries) {
            if (!this.queries.remove(query)) {
                throw new IllegalStateException("Unable to remove query from queries");
            }
        }
        final Conversation conversation = query.getConversation();
        if (conversation != null) {
            conversation.sort();
            if (query.isHistoryBackfill()) {
                conversation.setHasMessagesLeftOnServer(!done);
            }
            final var displayState = conversation.getDisplayState();
            if (displayState != null) {
                mXmppConnectionService.markReadUpToStanzaId(conversation, displayState);
            }
        } else {
            for (final Conversation tmp : this.mXmppConnectionService.getConversations()) {
                if (tmp.getAccount() == getAccount()) {
                    tmp.sort();
                    final var displayState = tmp.getDisplayState();
                    if (displayState != null) {
                        mXmppConnectionService.markReadUpToStanzaId(tmp, displayState);
                    }
                }
            }
        }
        if (notify) {
            if (query.hasCallback()) {
                query.callback(done);
            } else {
                this.mXmppConnectionService.updateConversationUi();
            }
        }
    }

    public boolean inCatchup() {
        synchronized (this.queries) {
            for (final var query : queries) {
                if (query.isCatchup() && query.getWith() == null) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isCatchupInProgress(final Conversation conversation) {
        synchronized (this.queries) {
            for (final var query : queries) {
                if (query.isCatchup()) {
                    final Jid with = query.getWith() == null ? null : query.getWith().asBareJid();
                    if ((conversation.getMode() == Conversational.MODE_SINGLE && with == null)
                            || (conversation.getAddress().asBareJid().equals(with))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean queryInProgress(
            Conversation conversation, XmppConnectionService.OnMoreMessagesLoaded callback) {
        synchronized (this.queries) {
            for (Query query : queries) {
                if (query.conversation == conversation) {
                    // The visible fragment may have been recreated while this query was active.
                    // Its current callback must receive completion instead of an abandoned view.
                    if (callback != null) {
                        query.setCallback(callback);
                    }
                    return true;
                }
            }
            return false;
        }
    }

    public boolean queryInProgress(Conversation conversation) {
        return queryInProgress(conversation, null);
    }

    public boolean historyQueryInProgress(
            final Conversation conversation,
            final XmppConnectionService.OnMoreMessagesLoaded callback) {
        synchronized (this.queries) {
            final Query query = findHistoryQuery(this.queries, conversation);
            if (query == null) {
                return false;
            }
            if (callback != null) {
                query.setCallback(callback);
            }
            return true;
        }
    }

    static Query findHistoryQuery(
            final Iterable<Query> activeQueries, final Conversation conversation) {
        for (final Query query : activeQueries) {
            if (query.conversation == conversation && query.isHistoryBackfill()) {
                return query;
            }
        }
        return null;
    }

    private void processFin(final Query query, final Fin fin) {
        boolean complete = fin.isComplete();
        Element set = fin.findChild("set", "http://jabber.org/protocol/rsm");
        Element last = set == null ? null : set.findChild("last");
        String count = set == null ? null : set.findChildContent("count");
        Element first = set == null ? null : set.findChild("first");
        Element relevant = query.getPagingOrder() == PagingOrder.NORMAL ? last : first;
        final boolean abort = hasReachedPageBudget(query);
        if (query.isHistoryBackfill() && query.getConversation() != null && first != null) {
            // Recent synchronization must not replace the cursor used for older-history paging.
            query.getConversation().setFirstMamReference(first.getContent());
        }
        if (complete || relevant == null || abort) {
            // XEP-0313's complete flag refers to the requested range. Only a full older-history
            // scan establishes archive exhaustion; duplicate-only pages and optional RSM counts
            // do not. A bounded recent sync can finish while much older history still exists.
            final boolean done = isHistoryComplete(query, complete);
            this.finalizeQuery(query, done);

            Log.d(
                    Config.LOGTAG,
                    getAccount().getJid().asBareJid()
                            + ": finished mam after "
                            + query.getTotalCount()
                            + "("
                            + query.getActualMessageCount()
                            + ") messages. complete="
                            + complete
                            + " history exhausted="
                            + done
                            + " count="
                            + count);
            if (query.isCatchup() && query.getActualMessageCount() > 0) {
                mXmppConnectionService.getNotificationService().finishBacklog(true, getAccount());
            }
            processPostponed(query);
        } else {
            final Query nextQuery;
            if (query.getPagingOrder() == PagingOrder.NORMAL) {
                nextQuery = query.next(last == null ? null : last.getContent());
            } else {
                nextQuery = query.prev(first == null ? null : first.getContent());
            }
            synchronized (this.queries) {
                this.queries.add(nextQuery);
            }
            // Register before sending so early result messages belong to this page. Only the
            // terminal page completes the caller's load, preserving a single UI re-anchor.
            this.finalizeQuery(query, false, false);
            this.execute(nextQuery);
        }
    }

    static boolean isHistoryComplete(final Query query, final boolean complete) {
        return query.isHistoryBackfill() && complete;
    }

    static boolean hasReachedPageBudget(final Query query) {
        // Servers can archive receipts, key transports, and messages already present locally.
        // A page of those stanzas must not consume the user's page of visible older messages.
        return (!query.isCatchup() && query.getActualMessageCount() >= Config.PAGE_SIZE)
                || query.getTotalCount() >= Config.MAM_MAX_MESSAGES;
    }

    static boolean shouldReleaseCancelledLoadGuard(
            final Query cancelled,
            final boolean hadCallback,
            final Iterable<Query> activeQueries) {
        return hadCallback && findHistoryQuery(activeQueries, cancelled.conversation) == null;
    }

    private void releaseCancelledLoadGuard(final Query query, final boolean hadCallback) {
        if (query.conversation != null
                && shouldReleaseCancelledLoadGuard(query, hadCallback, this.queries)) {
            query.conversation.messagesLoaded.set(true);
        }
    }

    static boolean shouldRetryWithoutReference(final Query query, final Throwable error) {
        return query.isHistoryBackfill()
                && query.reference != null
                && !query.retriedWithoutReference
                && error instanceof IqErrorException iqError
                && iqError.getErrorCondition() instanceof Condition.ItemNotFound;
    }

    public void kill(final Conversation conversation) {
        final ArrayList<Query> toBeKilled = new ArrayList<>();
        synchronized (this.queries) {
            for (final Query q : queries) {
                if (q.conversation == conversation) {
                    toBeKilled.add(q);
                }
            }
        }
        for (final Query q : toBeKilled) {
            kill(q);
        }
    }

    private void kill(final Query query) {
        Log.d(Config.LOGTAG, getAccount().getJid().asBareJid() + ": killing mam query prematurely");
        synchronized (this.queries) {
            final boolean hadCallback = query.hasCallback();
            query.callback = null;
            this.finalizeQuery(query, false, false);
            releaseCancelledLoadGuard(query, hadCallback);
        }
        // Publish cancellation after releasing its load guard so a refreshed conversation can
        // request history again, rather than observing the cancelled request as still loading.
        this.mXmppConnectionService.updateConversationUi();
        if (query.isCatchup() && query.getActualMessageCount() > 0) {
            mXmppConnectionService.getNotificationService().finishBacklog(true, getAccount());
        }
        this.processPostponed(query);
    }

    private void processPostponed(final Query query) {
        final var account = getAccount();
        account.getAxolotlService().processPostponed();
        query.pendingReceiptRequests.removeAll(query.receiptRequests);
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": found "
                        + query.pendingReceiptRequests.size()
                        + " pending receipt requests");
        final var deliveryReceiptManager = getManager(DeliveryReceiptManager.class);
        final var iterator = query.pendingReceiptRequests.iterator();
        while (iterator.hasNext()) {
            final var rr = iterator.next();
            deliveryReceiptManager.received(rr.getJid(), rr.getId());
            iterator.remove();
        }
    }

    public Query findQuery(@NonNull final String id) {
        synchronized (this.queries) {
            for (Query query : this.queries) {
                if (query.getQueryId().equals(id)) {
                    return query;
                }
            }
            return null;
        }
    }

    public boolean validFrom(final Query query, final Jid from) {
        if (query.muc()) {
            return query.getWith().equals(from);
        } else {
            return (from == null) || getAccount().getJid().asBareJid().equals(from.asBareJid());
        }
    }

    // the methods below are newly added to the manager

    public void fetchArchivingPreferences() {
        Futures.addCallback(
                getArchivingPreference(),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final Preferences.Default result) {
                        MessageArchiveManager.this.currentArchivingPreferences = result;
                    }

                    @Override
                    public void onFailure(@org.jspecify.annotations.NonNull Throwable t) {
                        MessageArchiveManager.this.currentArchivingPreferences = null;
                    }
                },
                MoreExecutors.directExecutor());
    }

    public ListenableFuture<Preferences.Default> getArchivingPreference() {
        final var iq = new Iq(Iq.Type.GET, new Preferences());
        final var future = connection.sendIqPacket(iq);
        return Futures.transform(
                future,
                result -> {
                    final var pref = result.getOnlyExtension(Preferences.class);
                    if (pref == null) {
                        throw new IllegalStateException("Server response did not contain pref");
                    }
                    return pref.getDefault();
                },
                MoreExecutors.directExecutor());
    }

    public ListenableFuture<Void> setArchivingPreference(final Preferences.Default preference) {
        final var iq = new Iq(Iq.Type.SET);
        final var preferences = iq.addExtension(new Preferences());
        preferences.setDefault(preference);
        final var future = connection.sendIqPacket(iq);
        return Futures.transform(future, result -> null, MoreExecutors.directExecutor());
    }

    public boolean isMamPreferenceAlways() {
        return this.currentArchivingPreferences == Preferences.Default.ALWAYS;
    }

    public boolean hasFeature() {
        return getManager(DiscoManager.class)
                .hasAccountFeature(Namespace.MESSAGE_ARCHIVE_MANAGEMENT);
    }

    private static im.conversations.android.xmpp.model.mam.Query toQuery(
            final MessageArchiveManager.Query mam) {
        final var query = new im.conversations.android.xmpp.model.mam.Query();
        query.setQueryId(mam.getQueryId());

        final ImmutableMap.Builder<String, Object> filter = ImmutableMap.builder();

        if (!mam.muc() && mam.getWith() != null) {

            // TODO use JID

            filter.put("with", mam.getWith().toString());
        }
        final long start = mam.getStart();
        final long end = mam.getEnd();
        if (start != 0) {

            // TODO use instant

            filter.put("start", AbstractGenerator.getTimestamp(start));
        }
        if (end != 0) {
            filter.put("end", AbstractGenerator.getTimestamp(end));
        }

        query.setFilter(filter.build());

        if (mam.getPagingOrder() == MessageArchiveManager.PagingOrder.REVERSE) {
            query.setResultSet(
                    im.conversations.android.xmpp.model.rsm.Set.of(
                            new Range(Range.Order.REVERSE, mam.getReference()), Config.PAGE_SIZE));
        } else if (mam.getReference() != null) {
            query.setResultSet(
                    im.conversations.android.xmpp.model.rsm.Set.of(
                            new Range(Range.Order.NORMAL, mam.getReference()), Config.PAGE_SIZE));
        }
        return query;
    }

    public enum PagingOrder {
        NORMAL,
        REVERSE
    }

    public static class Query {
        private HashSet<ReceiptRequest> pendingReceiptRequests = new HashSet<>();
        private HashSet<ReceiptRequest> receiptRequests = new HashSet<>();
        private int totalCount = 0;
        private int actualCount = 0;
        private int actualInThisQuery = 0;
        private long start;
        private final long end;
        private final String queryId;
        private String reference = null;
        private Conversation conversation;
        private PagingOrder pagingOrder = PagingOrder.NORMAL;
        private XmppConnectionService.OnMoreMessagesLoaded callback = null;
        private boolean catchup = true;
        private boolean historyBackfill = false;
        private boolean retriedWithoutReference = false;

        Query(Conversation conversation, MamReference start, long end, boolean catchup) {
            this(conversation, start, end, catchup, !catchup && start.getTimestamp() == 0);
        }

        Query(
                Conversation conversation,
                MamReference start,
                long end,
                boolean catchup,
                boolean historyBackfill) {
            this(catchup ? start : start.timeOnly(), end);
            this.conversation = conversation;
            this.pagingOrder = catchup ? PagingOrder.NORMAL : PagingOrder.REVERSE;
            this.catchup = catchup;
            this.historyBackfill = historyBackfill;
        }

        Query(MamReference start, long end) {
            if (start.getReference() != null) {
                this.reference = start.getReference();
            } else {
                this.start = start.getTimestamp();
            }
            this.end = end;
            this.queryId = new BigInteger(50, SECURE_RANDOM).toString(32);
        }

        private Query page(String reference) {
            Query query = new Query(new MamReference(this.start, reference), this.end);
            // The generic constructor chooses an ID reference over its timestamp. Pagination
            // still needs both the cursor and the original filter/retention lower bound.
            query.start = this.start;
            query.conversation = conversation;
            query.totalCount = totalCount;
            query.actualCount = actualCount;
            query.pendingReceiptRequests = pendingReceiptRequests;
            query.receiptRequests = receiptRequests;
            query.callback = callback;
            query.catchup = catchup;
            query.historyBackfill = historyBackfill;
            query.retriedWithoutReference = retriedWithoutReference;
            return query;
        }

        Query retryWithoutReference() {
            final Query retry = page(null);
            retry.pagingOrder = pagingOrder;
            retry.retriedWithoutReference = true;
            return retry;
        }

        public void removePendingReceiptRequest(ReceiptRequest receiptRequest) {
            if (!this.pendingReceiptRequests.remove(receiptRequest)) {
                this.receiptRequests.add(receiptRequest);
            }
        }

        public void addPendingReceiptRequest(ReceiptRequest receiptRequest) {
            this.pendingReceiptRequests.add(receiptRequest);
        }

        public boolean safeToExtractTrueCounterpart() {
            return muc();
        }

        public Query next(String reference) {
            Query query = page(reference);
            query.pagingOrder = PagingOrder.NORMAL;
            return query;
        }

        Query prev(String reference) {
            Query query = page(reference);
            query.pagingOrder = PagingOrder.REVERSE;
            return query;
        }

        public String getReference() {
            return reference;
        }

        public PagingOrder getPagingOrder() {
            return this.pagingOrder;
        }

        public String getQueryId() {
            return queryId;
        }

        public Jid getWith() {
            return conversation == null ? null : conversation.getAddress().asBareJid();
        }

        public boolean muc() {
            return conversation != null && conversation.getMode() == Conversation.MODE_MULTI;
        }

        public long getStart() {
            return start;
        }

        public boolean isCatchup() {
            return catchup;
        }

        boolean isHistoryBackfill() {
            return historyBackfill;
        }

        public void setCallback(XmppConnectionService.OnMoreMessagesLoaded callback) {
            this.callback = callback;
        }

        public void callback(boolean done) {
            if (this.callback != null) {
                this.callback.onMoreMessagesLoaded(actualCount, conversation);
                if (done) {
                    this.callback.informUser(R.string.no_more_history_on_server);
                }
            }
        }

        public long getEnd() {
            return end;
        }

        public Conversation getConversation() {
            return conversation;
        }

        public void incrementMessageCount() {
            this.totalCount++;
        }

        public void incrementActualMessageCount() {
            this.actualInThisQuery++;
            this.actualCount++;
        }

        int getTotalCount() {
            return this.totalCount;
        }

        int getActualMessageCount() {
            return this.actualCount;
        }

        public int getActualInThisQuery() {
            return this.actualInThisQuery;
        }

        @NonNull
        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            if (this.muc()) {
                builder.append("to=");
                builder.append(this.getWith().toString());
            } else {
                builder.append("with=");
                if (this.getWith() == null) {
                    builder.append("*");
                } else {
                    builder.append(getWith().toString());
                }
            }
            if (this.start != 0) {
                builder.append(", start=");
                builder.append(AbstractGenerator.getTimestamp(this.start));
            }
            if (this.end != 0) {
                builder.append(", end=");
                builder.append(AbstractGenerator.getTimestamp(this.end));
            }
            builder.append(", order=").append(pagingOrder.toString());
            if (this.reference != null) {
                if (this.pagingOrder == PagingOrder.NORMAL) {
                    builder.append(", after=");
                } else {
                    builder.append(", before=");
                }
                builder.append(this.reference);
            }
            builder.append(", catchup=").append(catchup);
            return builder.toString();
        }

        boolean hasCallback() {
            return this.callback != null;
        }

        public boolean isImplausibleFrom(final Jid from) {
            if (muc()) {
                if (from == null) {
                    return true;
                }
                return !from.asBareJid().equals(getWith());
            } else {
                return false;
            }
        }
    }

    public sealed interface Reference permits IdReference, InstantReference {}

    public record IdReference(String stanzaId) implements Reference {}

    public record InstantReference(Instant instant) implements Reference {}

    public record InstantIdReference(InstantReference instantReference, IdReference idReference) {
        public InstantIdReference {
            Preconditions.checkNotNull(instantReference, "Every reference must have an instant");
        }
    }
}
