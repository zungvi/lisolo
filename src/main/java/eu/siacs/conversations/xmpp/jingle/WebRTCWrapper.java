package eu.siacs.conversations.xmpp.jingle;

import android.media.ToneGenerator;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.manager.JingleManager;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.webrtc.AudioTrack;
import org.webrtc.CandidatePairChangeEvent;
import org.webrtc.DataChannel;
import org.webrtc.DtmfSender;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;

import java.util.Map;
public class WebRTCWrapper {

    private static final String EXTENDED_LOGGING_TAG = WebRTCWrapper.class.getSimpleName();

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final ExecutorService localDescriptionExecutorService =
            Executors.newSingleThreadExecutor();

    private static final int TONE_DURATION = 200;
    private static final Map<String,Integer> TONE_CODES;
    static {
        ImmutableMap.Builder<String,Integer> builder = new ImmutableMap.Builder<>();
        builder.put("0", ToneGenerator.TONE_DTMF_0);
        builder.put("1", ToneGenerator.TONE_DTMF_1);
        builder.put("2", ToneGenerator.TONE_DTMF_2);
        builder.put("3", ToneGenerator.TONE_DTMF_3);
        builder.put("4", ToneGenerator.TONE_DTMF_4);
        builder.put("5", ToneGenerator.TONE_DTMF_5);
        builder.put("6", ToneGenerator.TONE_DTMF_6);
        builder.put("7", ToneGenerator.TONE_DTMF_7);
        builder.put("8", ToneGenerator.TONE_DTMF_8);
        builder.put("9", ToneGenerator.TONE_DTMF_9);
        builder.put("*", ToneGenerator.TONE_DTMF_S);
        builder.put("#", ToneGenerator.TONE_DTMF_P);
        TONE_CODES = builder.build();
    }

    private final EventCallback eventCallback;
    private final AtomicBoolean readyToReceivedIceCandidates = new AtomicBoolean(false);
    private final Queue<PeerConnection.PeerConnectionState> stateHistory = new LinkedList<>();
    private final Queue<IceCandidate> iceCandidates = new LinkedList<>();
    private boolean requiresArtificialFail = false;
    private TrackWrapper<AudioTrack> localAudioTrack = null;
    private TrackWrapper<VideoTrack> localVideoTrack = null;
    private VideoTrack remoteVideoTrack = null;

    private final SettableFuture<Void> iceGatheringComplete = SettableFuture.create();
    private final PeerConnection.Observer peerConnectionObserver =
            new PeerConnection.Observer() {
                @Override
                public void onSignalingChange(final PeerConnection.SignalingState signalingState) {
                    // this is called after removeTrack or addTrack
                    // and should then trigger a content-add or content-remove or something
                    // https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/removeTrack
                    Log.d(EXTENDED_LOGGING_TAG, "onSignalingChange(" + signalingState + ")");

                    // if we limit ice transports types to RELAYS and do not actually provide any
                    // WebRTC will never go into CONNECTING state and thus never into FAILED state.
                    // To work around that issue we trigger an artificial application layer FAILED
                    // once both descriptions (remote and local) have been set
                    if (requiresArtificialFail
                            && signalingState == PeerConnection.SignalingState.STABLE
                            && stateHistory.isEmpty()) {
                        Log.d(EXTENDED_LOGGING_TAG, "triggering artificial fail");
                        this.onConnectionChange(PeerConnection.PeerConnectionState.FAILED);
                    }
                }

                @Override
                public void onConnectionChange(final PeerConnection.PeerConnectionState state) {
                    stateHistory.add(state);
                    Log.d(Config.LOGTAG, "onConnectionChange(" + state + ")");
                    eventCallback.onConnectionChange(state);
                }

                @Override
                public void onIceConnectionChange(
                        PeerConnection.IceConnectionState iceConnectionState) {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onIceConnectionChange(" + iceConnectionState + ")");
                }

                @Override
                public void onSelectedCandidatePairChanged(CandidatePairChangeEvent event) {
                    Log.d(Config.LOGTAG, "remote candidate selected: " + event.remote);
                    Log.d(Config.LOGTAG, "local candidate selected: " + event.local);
                }

                @Override
                public void onIceConnectionReceivingChange(boolean b) {}

                @Override
                public void onIceGatheringChange(
                        final PeerConnection.IceGatheringState iceGatheringState) {
                    Log.d(EXTENDED_LOGGING_TAG, "onIceGatheringChange(" + iceGatheringState + ")");
                    if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                        iceGatheringComplete.set(null);
                    }
                }

                @Override
                public void onIceCandidate(final IceCandidate iceCandidate) {
                    requiresArtificialFail = false;
                    if (readyToReceivedIceCandidates.get()) {
                        eventCallback.onIceCandidate(iceCandidate);
                    } else {
                        iceCandidates.add(iceCandidate);
                    }
                }

                @Override
                public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}

                @Override
                public void onAddStream(MediaStream mediaStream) {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onAddStream(numAudioTracks="
                                    + mediaStream.audioTracks.size()
                                    + ",numVideoTracks="
                                    + mediaStream.videoTracks.size()
                                    + ")");
                }

                @Override
                public void onRemoveStream(MediaStream mediaStream) {}

                @Override
                public void onDataChannel(DataChannel dataChannel) {}

                @Override
                public void onRenegotiationNeeded() {
                    Log.d(EXTENDED_LOGGING_TAG, "onRenegotiationNeeded()");
                    final PeerConnection.PeerConnectionState currentState =
                            peerConnection == null ? null : peerConnection.connectionState();
                    if (currentState != null
                            && currentState != PeerConnection.PeerConnectionState.NEW) {
                        eventCallback.onRenegotiationNeeded();
                    }
                }

                @Override
                public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                    final MediaStreamTrack track = rtpReceiver.track();
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onAddTrack(kind="
                                    + (track == null ? "null" : track.kind())
                                    + ",numMediaStreams="
                                    + mediaStreams.length
                                    + ")");
                    if (track instanceof VideoTrack) {
                        remoteVideoTrack = (VideoTrack) track;
                    }
                }

                @Override
                public void onTrack(final RtpTransceiver transceiver) {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onTrack(mid="
                                    + transceiver.getMid()
                                    + ",media="
                                    + transceiver.getMediaType()
                                    + ",direction="
                                    + transceiver.getDirection()
                                    + ")");
                }

                @Override
                public void onRemoveTrack(final RtpReceiver receiver) {
                    Log.d(EXTENDED_LOGGING_TAG, "onRemoveTrack(" + receiver.id() + ")");
                }
            };
    @Nullable private PeerConnection peerConnection = null;
    @Nullable private WebRTCSharedResources sharedResources;
    private boolean isSendingVideo;

    WebRTCWrapper(final EventCallback eventCallback) {
        this.eventCallback = eventCallback;
    }

    private static void dispose(final PeerConnection peerConnection) {
        try {
            peerConnection.dispose();
        } catch (final IllegalStateException e) {
            Log.e(Config.LOGTAG, "unable to dispose of peer connection", e);
        }
    }

    public void setup(final XmppConnectionService service) throws InitializationException {
        Preconditions.checkState(this.sharedResources == null, "WebRTC is already set up");
        this.sharedResources = WebRTCSharedResources.create(service);
    }

    synchronized void setup(
            final XmppConnectionService service, final WebRTCSharedResources sharedResources) {
        Preconditions.checkState(this.sharedResources == null, "WebRTC is already set up");
        Preconditions.checkNotNull(service);
        this.sharedResources = Preconditions.checkNotNull(sharedResources).acquire();
    }

    synchronized void initializePeerConnection(
            final Set<Media> media,
            final Collection<PeerConnection.IceServer> iceServers,
            final boolean trickle,
            final boolean useRelays)
            throws InitializationException {
        final WebRTCSharedResources sharedResources = requireSharedResources();
        Preconditions.checkNotNull(media);
        Preconditions.checkArgument(
                !media.isEmpty(), "media can not be empty when initializing peer connection");
        final var relaysAvailable = Iterables.any(iceServers, i -> i != null && isTurnRelay(i));
        this.requiresArtificialFail = useRelays && !relaysAvailable;

        final PeerConnection.RTCConfiguration rtcConfig =
                buildConfiguration(iceServers, trickle, useRelays);
        final PeerConnection peerConnection =
                sharedResources
                        .getPeerConnectionFactory()
                        .createPeerConnection(rtcConfig, peerConnectionObserver);
        if (peerConnection == null) {
            throw new InitializationException("Unable to create PeerConnection");
        }

        if (media.contains(Media.VIDEO)) {
            addVideoTrack(peerConnection);
        }

        if (media.contains(Media.AUDIO)) {
            addAudioTrack(peerConnection);
        }
        peerConnection.setAudioPlayout(true);
        peerConnection.setAudioRecording(true);

        this.peerConnection = peerConnection;
    }

    public synchronized boolean addTrack(final Media media) {
        if (media == Media.VIDEO) {
            return addVideoTrack(requirePeerConnection());
        } else if (media == Media.AUDIO) {
            return addAudioTrack(requirePeerConnection());
        }
        throw new IllegalStateException(String.format("Could not add track for %s", media));
    }

    public synchronized void removeTrack(final Media media) {
        if (media == Media.VIDEO) {
            removeVideoTrack(requirePeerConnection());
        }
    }

    private boolean addAudioTrack(final PeerConnection peerConnection) {
        this.localAudioTrack =
                TrackWrapper.addTrack(peerConnection, requireSharedResources().getAudioTrack());
        return true;
    }

    private boolean addVideoTrack(final PeerConnection peerConnection) {
        final TrackWrapper<VideoTrack> existing = this.localVideoTrack;
        if (existing != null) {
            final RtpTransceiver transceiver =
                    TrackWrapper.getTransceiver(peerConnection, existing);
            if (transceiver == null) {
                Log.w(EXTENDED_LOGGING_TAG, "unable to restart video transceiver");
                return false;
            }
            transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
            if (!isSendingVideo) {
                requireSharedResources().startVideoSending();
                isSendingVideo = true;
            }
            return true;
        }
        final VideoTrack videoTrack;
        try {
            videoTrack = requireSharedResources().startVideoSending();
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "could not add video track", e);
            return false;
        }
        try {
            this.localVideoTrack = TrackWrapper.addTrack(peerConnection, videoTrack);
            isSendingVideo = true;
            return true;
        } catch (final RuntimeException e) {
            requireSharedResources().stopVideoSending();
            throw e;
        }
    }

    private void removeVideoTrack(final PeerConnection peerConnection) {
        final TrackWrapper<VideoTrack> localVideoTrack = this.localVideoTrack;
        if (localVideoTrack != null) {

            final RtpTransceiver exactTransceiver =
                    TrackWrapper.getTransceiver(peerConnection, localVideoTrack);
            if (exactTransceiver == null) {
                throw new IllegalStateException();
            }
            exactTransceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE);
        }
        if (isSendingVideo) {
            requireSharedResources().stopVideoSending();
            isSendingVideo = false;
        }
    }

    public static PeerConnection.RTCConfiguration buildConfiguration(
            final Collection<PeerConnection.IceServer> iceServers,
            final boolean trickle,
            final boolean useRelay) {
        if (iceServers.isEmpty() && useRelay) {
            Log.w(EXTENDED_LOGGING_TAG, "no ice servers and 'useRelay' are a dangerous combo");
        }
        final PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(ImmutableList.copyOf(iceServers));
        rtcConfig.tcpCandidatePolicy =
                PeerConnection.TcpCandidatePolicy.DISABLED; // XEP-0176 doesn't support tcp
        if (trickle) {
            rtcConfig.continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        } else {
            rtcConfig.continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        }
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.NEGOTIATE;
        rtcConfig.enableImplicitRollback = true;
        rtcConfig.iceTransportsType =
                useRelay
                        ? PeerConnection.IceTransportsType.RELAY
                        : PeerConnection.IceTransportsType.ALL;
        return rtcConfig;
    }

    void reconfigurePeerConnection(
            final Set<PeerConnection.IceServer> iceServers,
            final boolean trickle,
            final boolean useRelay) {
        requirePeerConnection().setConfiguration(buildConfiguration(iceServers, trickle, useRelay));
    }

    void restartIceAsync() {
        this.execute(this::restartIce);
    }

    private void restartIce() {
        final PeerConnection peerConnection;
        try {
            peerConnection = requirePeerConnection();
        } catch (final PeerConnectionNotInitialized e) {
            Log.w(EXTENDED_LOGGING_TAG, "PeerConnection vanished before we could execute restart");
            return;
        }
        setIsReadyToReceiveIceCandidates(false);
        peerConnection.restartIce();
    }

    public void setIsReadyToReceiveIceCandidates(final boolean ready) {
        readyToReceivedIceCandidates.set(ready);
        final int was = iceCandidates.size();
        while (ready && iceCandidates.peek() != null) {
            eventCallback.onIceCandidate(iceCandidates.poll());
        }
        final int is = iceCandidates.size();
        Log.d(
                EXTENDED_LOGGING_TAG,
                "setIsReadyToReceiveCandidates(" + ready + ") was=" + was + " is=" + is);
    }

    synchronized void close() {
        final PeerConnection peerConnection = this.peerConnection;
        final WebRTCSharedResources sharedResources = this.sharedResources;
        this.sharedResources = null;
        if (peerConnection != null) {
            this.peerConnection = null;
            dispose(peerConnection);
        }
        if (isSendingVideo && sharedResources != null) {
            sharedResources.stopVideoSending();
        }
        isSendingVideo = false;
        this.localVideoTrack = null;
        this.remoteVideoTrack = null;
        this.localAudioTrack = null;
        if (sharedResources != null) {
            sharedResources.release();
        }
    }

    synchronized void verifyClosed() {
        if (this.peerConnection != null
                || this.sharedResources != null
                || this.localVideoTrack != null
                || this.localAudioTrack != null
                || this.remoteVideoTrack != null) {
            final AssertionError e =
                    new AssertionError("WebRTCWrapper hasn't been closed properly");
            Log.e(Config.LOGTAG, "verifyClosed() failed. Going to throw", e);
            throw e;
        }
    }

    boolean isCameraSwitchable() {
        final WebRTCSharedResources sharedResources = this.sharedResources;
        return sharedResources != null && sharedResources.isCameraSwitchable();
    }

    boolean isFrontCamera() {
        final WebRTCSharedResources sharedResources = this.sharedResources;
        return sharedResources == null || sharedResources.isFrontCamera();
    }

    ListenableFuture<Boolean> switchCamera() {
        final WebRTCSharedResources sharedResources = this.sharedResources;
        if (sharedResources == null) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException("WebRTC resources have not been initialized"));
        }
        return sharedResources.switchCamera();
    }

    boolean isMicrophoneEnabled() {
        final Optional<AudioTrack> audioTrack =
                TrackWrapper.get(peerConnection, this.localAudioTrack);
        if (audioTrack.isPresent()) {
            try {
                return audioTrack.get().enabled();
            } catch (final IllegalStateException e) {
                // sometimes UI might still be rendering the buttons when a background thread has
                // already ended the call
                return false;
            }
        } else {
            return false;
        }
    }

    boolean setMicrophoneEnabledOrThrow(final boolean enabled) {
        final Optional<AudioTrack> audioTrack =
                TrackWrapper.get(peerConnection, this.localAudioTrack);
        if (audioTrack.isPresent()) {
            return setEnabled(audioTrack.get(), enabled);

        } else {
            throw new IllegalStateException("Local audio track does not exist (yet)");
        }
    }

    private static boolean setEnabled(final AudioTrack audioTrack, final boolean enabled) {
        try {
            audioTrack.setEnabled(enabled);
            return true;
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to toggle audio track", e);
            // ignoring race condition in case MediaStreamTrack has been disposed
            return false;
        }
    }

    void setMicrophoneEnabled(final boolean enabled) {
        final Optional<AudioTrack> audioTrack =
                TrackWrapper.get(peerConnection, this.localAudioTrack);
        if (audioTrack.isPresent()) {
            setEnabled(audioTrack.get(), enabled);
        }
    }

    boolean isVideoEnabled() {
        final Optional<VideoTrack> videoTrack =
                TrackWrapper.get(peerConnection, this.localVideoTrack);
        if (videoTrack.isPresent()) {
            return videoTrack.get().enabled();
        }
        return false;
    }

    void setVideoEnabled(final boolean enabled) {
        final Optional<VideoTrack> videoTrack =
                TrackWrapper.get(peerConnection, this.localVideoTrack);
        if (videoTrack.isPresent()) {
            videoTrack.get().setEnabled(enabled);
            return;
        }
        throw new IllegalStateException("Local video track does not exist");
    }

    synchronized ListenableFuture<SessionDescription> setLocalDescription(
            final boolean waitForCandidates) {
        this.setIsReadyToReceiveIceCandidates(false);
        return Futures.transformAsync(
                getPeerConnectionFuture(),
                peerConnection -> {
                    if (peerConnection == null) {
                        return Futures.immediateFailedFuture(
                                new IllegalStateException("PeerConnection was null"));
                    }
                    final SettableFuture<SessionDescription> future = SettableFuture.create();
                    peerConnection.setLocalDescription(
                            new SetSdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    if (waitForCandidates) {
                                        final var delay = getIceGatheringCompleteOrTimeout();
                                        final var delayedSessionDescription =
                                                Futures.transformAsync(
                                                        delay,
                                                        v -> {
                                                            iceCandidates.clear();
                                                            return getLocalDescriptionFuture();
                                                        },
                                                        MoreExecutors.directExecutor());
                                        future.setFuture(delayedSessionDescription);
                                    } else {
                                        future.setFuture(getLocalDescriptionFuture());
                                    }
                                }

                                @Override
                                public void onSetFailure(final String message) {
                                    future.setException(
                                            new FailureToSetDescriptionException(message));
                                }
                            });
                    return future;
                },
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<Void> getIceGatheringCompleteOrTimeout() {
        return Futures.catching(
                Futures.withTimeout(
                        iceGatheringComplete,
                        2,
                        TimeUnit.SECONDS,
                        JingleManager.SCHEDULED_EXECUTOR_SERVICE),
                TimeoutException.class,
                ex -> {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "timeout while waiting for ICE gathering to complete");
                    return null;
                },
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<SessionDescription> getLocalDescriptionFuture() {
        return Futures.submit(
                () -> {
                    final SessionDescription description =
                            requirePeerConnection().getLocalDescription();
                    Log.d(EXTENDED_LOGGING_TAG, "local description:");
                    logDescription(description);
                    return description;
                },
                localDescriptionExecutorService);
    }

    public static void logDescription(final SessionDescription sessionDescription) {
        for (final String line :
                sessionDescription.description.split(
                        eu.siacs.conversations.xmpp.jingle.SessionDescription.LINE_DIVIDER)) {
            Log.d(EXTENDED_LOGGING_TAG, line);
        }
    }

    public static boolean isTurnRelay(final PeerConnection.IceServer iceServer) {
        return Iterables.any(
                iceServer.urls,
                u -> u != null && u.length() > 4 && u.substring(0, 4).equalsIgnoreCase("turn"));
    }

    synchronized ListenableFuture<Void> setRemoteDescription(
            final SessionDescription sessionDescription) {
        Log.d(EXTENDED_LOGGING_TAG, "setting remote description:");
        logDescription(sessionDescription);
        return Futures.transformAsync(
                getPeerConnectionFuture(),
                peerConnection -> {
                    if (peerConnection == null) {
                        return Futures.immediateFailedFuture(
                                new IllegalStateException("PeerConnection was null"));
                    }
                    final SettableFuture<Void> future = SettableFuture.create();
                    peerConnection.setRemoteDescription(
                            new SetSdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    future.set(null);
                                }

                                @Override
                                public void onSetFailure(final String message) {
                                    future.setException(
                                            new FailureToSetDescriptionException(message));
                                }
                            },
                            sessionDescription);
                    return future;
                },
                MoreExecutors.directExecutor());
    }

    @NonNull
    private ListenableFuture<PeerConnection> getPeerConnectionFuture() {
        final PeerConnection peerConnection = this.peerConnection;
        if (peerConnection == null) {
            return Futures.immediateFailedFuture(new PeerConnectionNotInitialized());
        } else {
            return Futures.immediateFuture(peerConnection);
        }
    }

    //TODO: remove - hack to test dtmfSending
    public DtmfSender getDtmfSender() {
        return peerConnection.getSenders().get(0).dtmf();
    }

    public boolean applyDtmfTone(String tone) {
        if (peerConnection == null || localAudioTrack == null) {
            return false;
        }
        localAudioTrack.rtpSender.dtmf().insertDtmf(tone, TONE_DURATION, 100);
        // FIXME: ToneManager has been removed upstream. Need to fix this code
        // to play the DTMF tone locally without ToneManager:
        //toneManager.startTone(TONE_CODES.get(tone), TONE_DURATION);
        return true;
    }

    @NonNull
    private PeerConnection requirePeerConnection() {
        final PeerConnection peerConnection = this.peerConnection;
        if (peerConnection == null) {
            throw new PeerConnectionNotInitialized();
        }
        return peerConnection;
    }

    @NonNull
    private WebRTCSharedResources requireSharedResources() {
        final WebRTCSharedResources sharedResources = this.sharedResources;
        if (sharedResources == null) {
            throw new IllegalStateException("Make sure WebRTC is initialized");
        }
        return sharedResources;
    }

    void addIceCandidate(IceCandidate iceCandidate) {
        requirePeerConnection().addIceCandidate(iceCandidate);
    }

    PeerConnection.PeerConnectionState getState() {
        return requirePeerConnection().connectionState();
    }

    public PeerConnection.SignalingState getSignalingState() {
        try {
            return requirePeerConnection().signalingState();
        } catch (final IllegalStateException e) {
            return PeerConnection.SignalingState.CLOSED;
        }
    }

    EglBase.Context getEglBaseContext() {
        return requireSharedResources().getEglBaseContext();
    }

    Optional<VideoTrack> getLocalVideoTrack() {
        return TrackWrapper.get(peerConnection, this.localVideoTrack);
    }

    Optional<VideoTrack> getRemoteVideoTrack() {
        return Optional.fromNullable(this.remoteVideoTrack);
    }

    void execute(final Runnable command) {
        this.executorService.execute(command);
    }

    public interface EventCallback {
        void onIceCandidate(IceCandidate iceCandidate);

        void onConnectionChange(PeerConnection.PeerConnectionState newState);

        void onRenegotiationNeeded();
    }

    public abstract static class SetSdpObserver implements SdpObserver {

        @Override
        public void onCreateSuccess(org.webrtc.SessionDescription sessionDescription) {
            throw new IllegalStateException("Not able to use SetSdpObserver");
        }

        @Override
        public void onCreateFailure(String s) {
            throw new IllegalStateException("Not able to use SetSdpObserver");
        }
    }

    public static class InitializationException extends Exception {

        InitializationException(final String message, final Throwable throwable) {
            super(message, throwable);
        }

        InitializationException(final String message) {
            super(message);
        }
    }

    public static class PeerConnectionNotInitialized extends IllegalStateException {

        public PeerConnectionNotInitialized() {
            super("initialize PeerConnection first");
        }
    }

    public static class FailureToSetDescriptionException extends IllegalArgumentException {
        public FailureToSetDescriptionException(String message) {
            super(message);
        }
    }
}
