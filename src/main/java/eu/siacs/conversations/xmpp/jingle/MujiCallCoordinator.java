package eu.siacs.conversations.xmpp.jingle;

import android.util.Log;
import androidx.annotation.Nullable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.manager.MultiUserChatManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Owns the peer sessions and shared media resources for one MUJI room call. */
public final class MujiCallCoordinator {

    private final Conversation conversation;
    private final Jid room;
    private final WebRTCSharedResources webRTCSharedResources;
    private final LinkedHashMap<Jid, JingleRtpConnection> peerSessions = new LinkedHashMap<>();
    private final LinkedHashMap<Jid, RtpEndUserState> participantStates = new LinkedHashMap<>();

    private boolean ending;
    private boolean finished;
    @Nullable private Listener listener;
    @Nullable private Consumer<MujiCallCoordinator> onFinished;

    private MujiCallCoordinator(
            final Conversation conversation,
            final WebRTCSharedResources webRTCSharedResources) {
        this.conversation = conversation;
        this.room = conversation.getAddress().asBareJid();
        this.webRTCSharedResources = webRTCSharedResources;
    }

    public static MujiCallCoordinator create(
            final XmppConnectionService service, final Conversation conversation)
            throws WebRTCWrapper.InitializationException {
        Preconditions.checkArgument(
                conversation.getMode() == Conversation.MODE_MULTI,
                "MUJI calls require a group conversation");
        Preconditions.checkArgument(
                conversation.getMucOptions().isPrivateAndNonAnonymous(),
                "MUJI calls require a members-only, non-anonymous room");
        return new MujiCallCoordinator(
                conversation, WebRTCSharedResources.create(service));
    }

    /** Register a peer before starting its Jingle session so it uses this call's shared tracks. */
    public void addPeerSession(final JingleRtpConnection connection) {
        Preconditions.checkNotNull(connection);
        final Jid participant = connection.getWith().asBareJid();
        Preconditions.checkArgument(
                connection.getId().account.getUuid().equals(conversation.getAccount().getUuid()),
                "Peer session belongs to a different account");
        Preconditions.checkArgument(
                !participant.equals(room), "A room cannot be registered as its own peer");

        final Map<Jid, RtpEndUserState> stateSnapshot;
        synchronized (this) {
            Preconditions.checkState(!ending, "Cannot add a peer after the room call is ending");
            Preconditions.checkState(
                    !peerSessions.containsKey(participant),
                    "A Jingle session already exists for this participant");
            connection.setSharedWebRTCResources(webRTCSharedResources);
            connection.setMujiCallCoordinator(this);
            peerSessions.put(participant, connection);
            participantStates.put(participant, connection.getEndUserState());
            stateSnapshot = immutableParticipantStates();
        }
        notifyListener(stateSnapshot);
    }

    public synchronized List<JingleRtpConnection> getPeerSessions() {
        return Collections.unmodifiableList(new ArrayList<>(peerSessions.values()));
    }

    /** Returns the latest state for active and recently ended participants. */
    public synchronized Map<Jid, RtpEndUserState> getParticipantStates() {
        return immutableParticipantStates();
    }

    public synchronized RtpEndUserState getAggregateState() {
        return aggregateState(participantStates, ending);
    }

    public synchronized boolean isEnding() {
        return ending;
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    public void setListener(@Nullable final Listener listener) {
        final Map<Jid, RtpEndUserState> stateSnapshot;
        synchronized (this) {
            this.listener = listener;
            stateSnapshot = immutableParticipantStates();
        }
        notifyListener(listener, stateSnapshot);
    }

    public void endCall() {
        final List<JingleRtpConnection> sessions;
        synchronized (this) {
            if (ending) {
                return;
            }
            ending = true;
            sessions = new ArrayList<>(peerSessions.values());
        }

        // MUJI presence is removed before pairwise sessions are closed, as required by XEP-0272.
        try {
            conversation
                    .getAccount()
                    .getXmppConnection()
                    .getManager(MultiUserChatManager.class)
                    .publishMujiPresence(conversation, null);
        } catch (final RuntimeException e) {
            // The MUC may already be unavailable. Still close the peer sessions so local media
            // stops and the call can release its WebRTC resources.
            Log.w(Config.LOGTAG, "Unable to clear MUJI presence while ending " + room, e);
        }

        for (final JingleRtpConnection session : sessions) {
            session.endCall();
        }
        releaseResourcesIfFinished();
        notifyListener(getParticipantStates());
    }

    void onPeerStateChanged(
            final JingleRtpConnection connection, final RtpEndUserState state) {
        final Jid participant = connection.getWith().asBareJid();
        final Map<Jid, RtpEndUserState> stateSnapshot;
        synchronized (this) {
            if (peerSessions.get(participant) != connection) {
                return;
            }
            participantStates.put(participant, state);
            stateSnapshot = immutableParticipantStates();
        }
        notifyListener(stateSnapshot);
    }

    void onPeerFinished(final JingleRtpConnection connection) {
        final Map<Jid, RtpEndUserState> stateSnapshot;
        synchronized (this) {
            final Jid participant = connection.getWith().asBareJid();
            if (peerSessions.get(participant) != connection) {
                return;
            }
            participantStates.put(participant, connection.getEndUserState());
            peerSessions.remove(participant);
            stateSnapshot = immutableParticipantStates();
        }
        notifyListener(stateSnapshot);
        releaseResourcesIfFinished();
    }

    public synchronized void setOnFinished(final Consumer<MujiCallCoordinator> onFinished) {
        Preconditions.checkState(!finished, "MUJI coordinator is already finished");
        Preconditions.checkState(
                this.onFinished == null, "MUJI completion callback is already set");
        this.onFinished = onFinished;
    }

    private Map<Jid, RtpEndUserState> immutableParticipantStates() {
        return ImmutableMap.copyOf(participantStates);
    }

    private void notifyListener(final Map<Jid, RtpEndUserState> stateSnapshot) {
        final Listener listener;
        synchronized (this) {
            listener = this.listener;
        }
        notifyListener(listener, stateSnapshot);
    }

    private void notifyListener(
            @Nullable final Listener listener,
            final Map<Jid, RtpEndUserState> stateSnapshot) {
        if (listener != null) {
            listener.onParticipantStatesChanged(
                    stateSnapshot, aggregateState(stateSnapshot, isEnding()));
        }
    }

    private void releaseResourcesIfFinished() {
        final Consumer<MujiCallCoordinator> callback;
        synchronized (this) {
            if (!ending || !peerSessions.isEmpty() || finished) {
                return;
            }
            finished = true;
            callback = onFinished;
        }
        webRTCSharedResources.release();
        if (callback != null) {
            callback.accept(this);
        }
    }

    private static RtpEndUserState aggregateState(
            final Map<Jid, RtpEndUserState> states, final boolean ending) {
        if (states.isEmpty()) {
            return ending ? RtpEndUserState.ENDED : RtpEndUserState.CONNECTING;
        }
        if (ending) {
            return states.values().stream().anyMatch(MujiCallCoordinator::isOngoing)
                    ? RtpEndUserState.ENDING_CALL
                    : RtpEndUserState.ENDED;
        }
        if (states.containsValue(RtpEndUserState.CONNECTED)) {
            return RtpEndUserState.CONNECTED;
        }
        if (states.containsValue(RtpEndUserState.RECONNECTING)) {
            return RtpEndUserState.RECONNECTING;
        }
        if (states.containsValue(RtpEndUserState.CONNECTING)
                || states.containsValue(RtpEndUserState.ENDING_CALL)
                || states.containsValue(RtpEndUserState.INCOMING_CONTENT_ADD)) {
            return RtpEndUserState.CONNECTING;
        }
        if (states.containsValue(RtpEndUserState.RINGING)
                || states.containsValue(RtpEndUserState.FINDING_DEVICE)) {
            return RtpEndUserState.RINGING;
        }
        if (states.containsValue(RtpEndUserState.ACCEPTING_CALL)) {
            return RtpEndUserState.ACCEPTING_CALL;
        }
        if (states.containsValue(RtpEndUserState.INCOMING_CALL)) {
            return RtpEndUserState.INCOMING_CALL;
        }
        if (states.containsValue(RtpEndUserState.SECURITY_ERROR)) {
            return RtpEndUserState.SECURITY_ERROR;
        }
        if (states.containsValue(RtpEndUserState.APPLICATION_ERROR)) {
            return RtpEndUserState.APPLICATION_ERROR;
        }
        if (states.containsValue(RtpEndUserState.CONNECTIVITY_LOST_ERROR)) {
            return RtpEndUserState.CONNECTIVITY_LOST_ERROR;
        }
        if (states.containsValue(RtpEndUserState.CONNECTIVITY_ERROR)) {
            return RtpEndUserState.CONNECTIVITY_ERROR;
        }
        if (states.containsValue(RtpEndUserState.DECLINED_OR_BUSY)) {
            return RtpEndUserState.DECLINED_OR_BUSY;
        }
        if (states.containsValue(RtpEndUserState.CONTACT_OFFLINE)) {
            return RtpEndUserState.CONTACT_OFFLINE;
        }
        if (states.containsValue(RtpEndUserState.RETRACTED)) {
            return RtpEndUserState.RETRACTED;
        }
        return RtpEndUserState.ENDED;
    }

    private static boolean isOngoing(final RtpEndUserState state) {
        return switch (state) {
            case INCOMING_CALL,
                    CONNECTING,
                    CONNECTED,
                    RECONNECTING,
                    INCOMING_CONTENT_ADD,
                    RINGING,
                    ACCEPTING_CALL,
                    ENDING_CALL,
                    FINDING_DEVICE -> true;
            default -> false;
        };
    }

    public interface Listener {
        void onParticipantStatesChanged(
                Map<Jid, RtpEndUserState> participantStates, RtpEndUserState aggregateState);
    }
}
