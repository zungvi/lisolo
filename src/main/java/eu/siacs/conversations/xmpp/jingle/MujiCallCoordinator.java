package eu.siacs.conversations.xmpp.jingle;

import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.services.CallIntegration;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.manager.MultiUserChatManager;
import im.conversations.android.xmpp.model.jingle.Muji;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Owns the peer sessions and shared media resources for one MUJI room call. */
public final class MujiCallCoordinator implements CallIntegration.Callback, OngoingRtpSession {

    /** Initial mesh limit, including the local participant. */
    public static final int MAX_PARTICIPANTS = 5;
    private static final int MAX_REMOTE_PARTICIPANTS = MAX_PARTICIPANTS - 1;

    private final XmppConnectionService service;
    private final Conversation conversation;
    private final Jid room;
    private final AbstractJingleConnection.Id id;
    private final WebRTCSharedResources webRTCSharedResources;
    private final CallIntegration callIntegration;
    private final LinkedHashMap<Jid, JingleRtpConnection> peerSessions = new LinkedHashMap<>();
    private final LinkedHashMap<Jid, RtpEndUserState> participantStates = new LinkedHashMap<>();

    private boolean ending;
    private boolean finished;
    private boolean audioRoutingStarted;
    private boolean started;
    private boolean hasConnectedPeer;
    private boolean callIntegrationActive;
    private long connectedAt;
    @Nullable private Set<Media> sharedMedia;
    @Nullable private MujiCodecCompatibility.Result codecCompatibility;
    @Nullable private RtpContentMap agreedContentMap;
    @Nullable private Listener listener;
    @Nullable private Consumer<MujiCallCoordinator> onFinished;

    private MujiCallCoordinator(
            final XmppConnectionService service,
            final Conversation conversation,
            final WebRTCSharedResources webRTCSharedResources) {
        this.service = service;
        this.conversation = conversation;
        this.room = conversation.getAddress().asBareJid();
        this.id = AbstractJingleConnection.Id.of(conversation.getAccount(), room);
        this.webRTCSharedResources = webRTCSharedResources;
        this.callIntegration = new CallIntegration(service.getApplicationContext());
        this.callIntegration.setCallback(this);
        this.callIntegration.setAddress(
                CallIntegration.address(room), TelecomManager.PRESENTATION_ALLOWED);
        this.callIntegration.setCallerDisplayName(
                conversation.getName().toString(), TelecomManager.PRESENTATION_ALLOWED);
        this.callIntegration.setInitialized();
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
        final WebRTCSharedResources resources = WebRTCSharedResources.create(service);
        try {
            return new MujiCallCoordinator(service, conversation, resources);
        } catch (final RuntimeException | Error e) {
            resources.release();
            throw e;
        }
    }

    /** Register a peer before starting its Jingle session so it uses this call's shared tracks. */
    public void addPeerSession(final JingleRtpConnection connection) {
        Preconditions.checkNotNull(connection);
        final Jid participant = connection.getWith().asBareJid();
        final Set<Media> media = ImmutableSet.copyOf(connection.getMedia());
        Preconditions.checkArgument(
                connection.getId().account.getUuid().equals(conversation.getAccount().getUuid()),
                "Peer session belongs to a different account");
        Preconditions.checkArgument(
                !participant.equals(room), "A room cannot be registered as its own peer");
        Preconditions.checkArgument(
                isActiveMujiParticipant(participant),
                "Peer has not published ready MUJI media in this room");
        requireAgreedMedia(media);

        final Map<Jid, RtpEndUserState> stateSnapshot;
        final boolean startAudioRouting;
        synchronized (this) {
            Preconditions.checkState(!ending, "Cannot add a peer after the room call is ending");
            Preconditions.checkState(
                    peerSessions.size() < MAX_REMOTE_PARTICIPANTS,
                    "MUJI mesh calls are limited to " + MAX_PARTICIPANTS + " participants");
            Preconditions.checkArgument(
                    sharedMedia == null || sharedMedia.equals(media),
                    "All MUJI peer sessions must use the room's shared media set");
            Preconditions.checkState(
                    !peerSessions.containsKey(participant),
                    "A Jingle session already exists for this participant");
            if (sharedMedia == null) {
                sharedMedia = media;
            }
            connection.setSharedWebRTCResources(webRTCSharedResources);
            connection.setMujiCallCoordinator(this);
            peerSessions.put(participant, connection);
            participantStates.put(participant, connection.getEndUserState());
            started = true;
            startAudioRouting = !audioRoutingStarted;
            audioRoutingStarted = true;
            stateSnapshot = immutableParticipantStates();
        }
        if (startAudioRouting) {
            callIntegration.setVideoState(
                    Media.audioOnly(media)
                            ? VideoProfile.STATE_AUDIO_ONLY
                            : VideoProfile.STATE_BIDIRECTIONAL);
            callIntegration.setInitialAudioDevice(CallIntegration.initialAudioDevice(media));
            callIntegration.setDialing();
            callIntegration.startAudioRouting();
        }
        notifyListener(stateSnapshot);
    }

    /** Inject shared resources before a peer builds its first local SDP offer. */
    public void preparePeerSession(final JingleRtpConnection connection) {
        Preconditions.checkNotNull(connection);
        Preconditions.checkArgument(
                connection.getId().account.getUuid().equals(conversation.getAccount().getUuid()),
                "Peer session belongs to a different account");
        Preconditions.checkArgument(
                !connection.getWith().asBareJid().equals(room),
                "A room cannot be registered as its own peer");
        connection.setSharedWebRTCResources(webRTCSharedResources);
        connection.setMujiCallCoordinator(this);
    }

    /** Keep a published room call ready for participants who join after the host starts. */
    public void startWaitingForParticipants() {
        final Set<Media> media;
        synchronized (this) {
            Preconditions.checkState(!ending, "Cannot start a room call that is ending");
            Preconditions.checkState(hasCodecCompatibility(), "MUJI codec map is not configured");
            Preconditions.checkState(peerSessions.isEmpty());
            Preconditions.checkState(!started, "MUJI room call has already started");
            started = true;
            audioRoutingStarted = true;
            media = sharedMedia;
        }
        callIntegration.setVideoState(
                Media.audioOnly(media)
                        ? VideoProfile.STATE_AUDIO_ONLY
                        : VideoProfile.STATE_BIDIRECTIONAL);
        callIntegration.setInitialAudioDevice(CallIntegration.initialAudioDevice(media));
        callIntegration.setDialing();
        callIntegration.startAudioRouting();
        notifyListener(getParticipantStates());
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized List<JingleRtpConnection> getPeerSessions() {
        return Collections.unmodifiableList(new ArrayList<>(peerSessions.values()));
    }

    @Nullable
    public synchronized JingleRtpConnection getPeerSession(final Jid participant) {
        return peerSessions.get(participant.asBareJid());
    }

    public synchronized void replacePeerSessionForCollision(
            final JingleRtpConnection connection) {
        final Jid participant = connection.getWith().asBareJid();
        Preconditions.checkState(
                peerSessions.get(participant) == connection,
                "Peer session is not registered with this coordinator");
        peerSessions.remove(participant);
    }

    /** Returns the latest state for active and recently ended participants. */
    public synchronized Map<Jid, RtpEndUserState> getParticipantStates() {
        return immutableParticipantStates();
    }

    public synchronized RtpEndUserState getAggregateState() {
        return aggregateState(participantStates, ending, started && peerSessions.isEmpty());
    }

    public synchronized boolean isEnding() {
        return ending;
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    public CallIntegration getCallIntegration() {
        return callIntegration;
    }

    @Override
    public Jid getWith() {
        return room;
    }

    @Override
    public String getSessionId() {
        return id.sessionId;
    }

    @Override
    public synchronized Set<Media> getMedia() {
        return sharedMedia == null ? ImmutableSet.of() : sharedMedia;
    }

    public synchronized void setCodecCompatibility(
            final MujiCodecCompatibility.Result codecCompatibility,
            final RtpContentMap proposedMedia) {
        Preconditions.checkArgument(
                codecCompatibility.compatible(),
                "Cannot configure a room call with incompatible media");
        Preconditions.checkState(!ending && peerSessions.isEmpty());
        Preconditions.checkState(
                this.codecCompatibility == null,
                "MUJI codec compatibility has already been configured");
        this.agreedContentMap = codecCompatibility.retainSharedPayloadTypes(proposedMedia);
        this.codecCompatibility = codecCompatibility;
        this.sharedMedia = ImmutableSet.copyOf(agreedContentMap.getMedia());
    }

    public synchronized boolean hasCodecCompatibility() {
        return codecCompatibility != null && agreedContentMap != null;
    }

    public synchronized void requireAgreedMedia(final Set<Media> media) {
        Preconditions.checkState(hasCodecCompatibility(), "MUJI codec map is not configured");
        Preconditions.checkArgument(
                agreedContentMap.getMedia().equals(media),
                "Requested MUJI media does not match the room-wide agreement");
    }

    public synchronized RtpContentMap retainSharedPayloadTypes(final RtpContentMap contentMap) {
        Preconditions.checkState(hasCodecCompatibility(), "MUJI codec map is not configured");
        Preconditions.checkArgument(
                ImmutableSet.copyOf(agreedContentMap.getNames())
                        .equals(ImmutableSet.copyOf(contentMap.getNames())),
                "Local Jingle session is using different media contents than MUJI presence");
        return codecCompatibility.retainSharedPayloadTypes(contentMap);
    }

    public synchronized void validatePeerContentMap(final RtpContentMap peerContentMap) {
        Preconditions.checkState(hasCodecCompatibility(), "MUJI codec map is not configured");
        Preconditions.checkArgument(
                ImmutableSet.copyOf(agreedContentMap.getNames())
                        .equals(ImmutableSet.copyOf(peerContentMap.getNames())),
                "MUJI peer is using different media contents");
        final MujiCodecCompatibility.Result peerAnnouncement =
                MujiCodecCompatibility.inspect(
                        List.of(peerContentMap.toMujiAnnouncement()));
        final Map<String, Set<Integer>> expectedPayloadTypes =
                codecCompatibility.sharedPayloadTypes();
        for (final Map.Entry<String, Set<Integer>> entry :
                peerAnnouncement.sharedPayloadTypes().entrySet()) {
            Preconditions.checkArgument(
                    expectedPayloadTypes.containsKey(entry.getKey())
                            && expectedPayloadTypes.get(entry.getKey()).containsAll(entry.getValue()),
                    "MUJI peer advertised payload types outside the room-wide mapping");
        }
        final MujiCodecCompatibility.Result peerCompatibility =
                MujiCodecCompatibility.inspect(
                        List.of(
                                agreedContentMap.toMujiAnnouncement(),
                                peerContentMap.toMujiAnnouncement()));
        Preconditions.checkArgument(
                peerCompatibility.compatible()
                        && peerCompatibility.sharedPayloadTypes().keySet()
                                .equals(expectedPayloadTypes.keySet()),
                "MUJI peer is not using the room-wide payload mapping");
    }

    public List<Jid> getReadyMujiParticipants() {
        final LinkedHashSet<Jid> participants = new LinkedHashSet<>();
        for (final MucOptions.User user : conversation.getMucOptions().getUsers()) {
            if (user.resource() == null || !user.isInMujiCall() || user.isPreparingMujiCall()) {
                continue;
            }
            final Jid realJid = user.getRealJid();
            if (realJid == null
                    || realJid
                            .asBareJid()
                            .equals(conversation.getAccount().getJid().asBareJid())) {
                continue;
            }
            participants.add(realJid.asBareJid());
        }
        return ImmutableList.copyOf(participants);
    }

    public synchronized boolean isPeerCompatible(final Jid participant) {
        if (!hasCodecCompatibility()) {
            return false;
        }
        final Muji announcement =
                conversation.getMucOptions().getUsers().stream()
                        .filter(
                                user -> {
                                    final Jid realJid = user.getRealJid();
                                    return user.resource() != null
                                            && user.isInMujiCall()
                                            && !user.isPreparingMujiCall()
                                            && realJid != null
                                            && realJid.asBareJid().equals(participant.asBareJid());
                                })
                        .map(MucOptions.User::getMuji)
                        .findFirst()
                        .orElse(null);
        if (announcement == null) {
            return false;
        }
        final MujiCodecCompatibility.Result compatibility =
                MujiCodecCompatibility.inspect(
                        List.of(agreedContentMap.toMujiAnnouncement(), announcement));
        return compatibility.compatible()
                && compatibility.sharedPayloadTypes().keySet()
                        .equals(codecCompatibility.sharedPayloadTypes().keySet());
    }

    public Jid getRoom() {
        return room;
    }

    public String getAccountUuid() {
        return conversation.getAccount().getUuid();
    }

    public Conversation getConversation() {
        return conversation;
    }

    @Nullable
    public synchronized JingleRtpConnection getRepresentativePeerSession() {
        return peerSessions.values().stream().findFirst().orElse(null);
    }

    public synchronized int getActiveParticipantCount() {
        return peerSessions.size();
    }

    public synchronized boolean zeroDuration() {
        return connectedAt == 0;
    }

    public synchronized long getCallDuration() {
        return connectedAt == 0 ? 0 : SystemClock.elapsedRealtime() - connectedAt;
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

        final boolean wasConnected;
        synchronized (this) {
            wasConnected = hasConnectedPeer;
        }
        if (wasConnected) {
            callIntegration.success();
        } else {
            callIntegration.retracted();
        }
        service.removeOngoingCall();

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
        final boolean activateCallIntegration;
        synchronized (this) {
            if (peerSessions.get(participant) != connection) {
                return;
            }
            participantStates.put(participant, state);
            hasConnectedPeer |= state == RtpEndUserState.CONNECTED;
            if (state == RtpEndUserState.CONNECTED && connectedAt == 0) {
                connectedAt = SystemClock.elapsedRealtime();
            }
            activateCallIntegration =
                    state == RtpEndUserState.CONNECTED && !ending && !callIntegrationActive;
            if (activateCallIntegration) {
                callIntegrationActive = true;
            }
            stateSnapshot = immutableParticipantStates();
        }
        if (activateCallIntegration) {
            callIntegration.setActive();
        }
        notifyListener(stateSnapshot);
    }

    void onPeerMediaChanged(
            final JingleRtpConnection connection, final java.util.Set<Media> media) {
        synchronized (this) {
            if (peerSessions.get(connection.getWith().asBareJid()) != connection || ending) {
                return;
            }
            sharedMedia = ImmutableSet.copyOf(media);
        }
        callIntegration.setVideoState(
                Media.audioOnly(media)
                        ? VideoProfile.STATE_AUDIO_ONLY
                        : VideoProfile.STATE_BIDIRECTIONAL);
        callIntegration.setAudioDeviceWhenAvailable(CallIntegration.initialAudioDevice(media));
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

    private boolean isActiveMujiParticipant(final Jid participant) {
        return conversation.getMucOptions().getUsers().stream()
                .anyMatch(
                        user -> {
                            final Jid realJid = user.getRealJid();
                            return realJid != null
                                    && realJid.asBareJid().equals(participant)
                                    && user.isInMujiCall()
                                    && !user.isPreparingMujiCall();
                        });
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
        final boolean waiting;
        final boolean ending;
        synchronized (this) {
            waiting = started && peerSessions.isEmpty();
            ending = this.ending;
        }
        final RtpEndUserState aggregateState = aggregateState(stateSnapshot, ending, waiting);
        service.notifyMujiCallUpdate(
                conversation.getAccount(), room, id.sessionId, stateSnapshot, aggregateState);
        if (!isEnding() && isStarted() && !getMedia().isEmpty()) {
            service.setOngoingCall(
                    id, getMedia(), aggregateState == RtpEndUserState.RECONNECTING);
        } else if (getActiveParticipantCount() == 0) {
            service.removeOngoingCall();
        }
        if (listener != null) {
            listener.onParticipantStatesChanged(stateSnapshot, aggregateState);
        }
    }

    public void fireStateUpdate() {
        notifyListener(getParticipantStates());
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
        callIntegration.verifyDisconnected();
        if (callback != null) {
            callback.accept(this);
        }
    }

    private static RtpEndUserState aggregateState(
            final Map<Jid, RtpEndUserState> states,
            final boolean ending,
            final boolean waiting) {
        if (waiting && !ending) {
            return RtpEndUserState.CONNECTING;
        }
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

        default void onAnswerRequested() {}

        default void onIncomingCallUiRequested() {}
    }

    @Override
    public void onCallIntegrationShowIncomingCallUi() {
        final Listener current;
        synchronized (this) {
            current = listener;
        }
        if (current != null) {
            current.onIncomingCallUiRequested();
        }
    }

    @Override
    public void onCallIntegrationDisconnect() {
        endCall();
    }

    @Override
    public void onAudioDeviceChanged(
            final CallIntegration.AudioDevice selectedAudioDevice,
            final java.util.Set<CallIntegration.AudioDevice> availableAudioDevices) {
        service.notifyJingleRtpConnectionUpdate(selectedAudioDevice, availableAudioDevices);
    }

    @Override
    public void onCallIntegrationReject() {
        endCall();
    }

    @Override
    public void onCallIntegrationAnswer() {
        final Listener current;
        synchronized (this) {
            current = listener;
        }
        if (current != null) {
            current.onAnswerRequested();
        }
    }

    @Override
    public void onCallIntegrationSilence() {
        service.getNotificationService().stopSoundAndVibration();
    }

    @Override
    public void onCallIntegrationMicrophoneEnabled(final boolean enabled) {
        webRTCSharedResources.setMicrophoneEnabled(enabled);
    }
}
