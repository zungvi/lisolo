package eu.siacs.conversations.xmpp.jingle;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.services.XmppConnectionService;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

/** Resources shared by the peer connections that make up one MUJI room call. */
public final class WebRTCSharedResources {

    private static final ImmutableSet<String> HARDWARE_AEC_BLACKLIST =
            ImmutableSet.of(
                    "Pixel",
                    "Pixel XL",
                    "Moto G5",
                    "Moto G (5S) Plus",
                    "Moto G4",
                    "TA-1053",
                    "Mi A1",
                    "Mi A2",
                    "E5823",
                    "Redmi Note 5",
                    "FP2",
                    "FP4",
                    "MI 5",
                    "GT-I9515",
                    "GT-I9515L",
                    "GT-I9505",
                    "Nexus 7");

    private final Context context;
    private final EglBase eglBase;
    private final PeerConnectionFactory peerConnectionFactory;
    private final AudioSource audioSource;
    private final AudioTrack audioTrack;

    @Nullable private VideoSourceWrapper videoSourceWrapper;
    @Nullable private VideoTrack videoTrack;
    private int references = 1;
    private int activeVideoSenders;

    private WebRTCSharedResources(
            final Context context,
            final EglBase eglBase,
            final PeerConnectionFactory peerConnectionFactory,
            final AudioSource audioSource,
            final AudioTrack audioTrack) {
        this.context = context.getApplicationContext();
        this.eglBase = eglBase;
        this.peerConnectionFactory = peerConnectionFactory;
        this.audioSource = audioSource;
        this.audioTrack = audioTrack;
    }

    public static WebRTCSharedResources create(final XmppConnectionService service)
            throws WebRTCWrapper.InitializationException {
        try {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(service)
                            .setFieldTrials("WebRTC-BindUsingInterfaceName/Enabled/")
                            .createInitializationOptions());
        } catch (final UnsatisfiedLinkError e) {
            throw new WebRTCWrapper.InitializationException(
                    "Unable to initialize PeerConnectionFactory", e);
        }

        final EglBase eglBase;
        try {
            eglBase = EglBase.create();
        } catch (final RuntimeException e) {
            throw new WebRTCWrapper.InitializationException("Unable to create EGL base", e);
        }

        PeerConnectionFactory peerConnectionFactory = null;
        AudioSource audioSource = null;
        AudioTrack audioTrack = null;
        try {
            final boolean setUseHardwareAcousticEchoCanceler =
                    !HARDWARE_AEC_BLACKLIST.contains(Build.MODEL);
            Log.d(
                    Config.LOGTAG,
                    "setUseHardwareAcousticEchoCanceler(" + setUseHardwareAcousticEchoCanceler
                            + ") model=" + Build.MODEL);
            peerConnectionFactory =
                    PeerConnectionFactory.builder()
                            .setVideoDecoderFactory(
                                    new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                            .setVideoEncoderFactory(
                                    new DefaultVideoEncoderFactory(
                                            eglBase.getEglBaseContext(), true, true))
                            .setAudioDeviceModule(
                                    JavaAudioDeviceModule.builder(service)
                                            .setUseHardwareAcousticEchoCanceler(
                                                    setUseHardwareAcousticEchoCanceler)
                                            .createAudioDeviceModule())
                            .createPeerConnectionFactory();
            audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
            audioTrack =
                    peerConnectionFactory.createAudioTrack(
                            TrackWrapper.id(AudioTrack.class), audioSource);
            return new WebRTCSharedResources(
                    service, eglBase, peerConnectionFactory, audioSource, audioTrack);
        } catch (final RuntimeException | UnsatisfiedLinkError e) {
            if (audioTrack != null) {
                audioTrack.dispose();
            }
            if (audioSource != null) {
                audioSource.dispose();
            }
            if (peerConnectionFactory != null) {
                peerConnectionFactory.dispose();
            }
            eglBase.release();
            throw new WebRTCWrapper.InitializationException(
                    "Unable to create shared WebRTC media resources", e);
        }
    }

    synchronized WebRTCSharedResources acquire() {
        Preconditions.checkState(references > 0, "WebRTC resources have been released");
        references++;
        return this;
    }

    PeerConnectionFactory getPeerConnectionFactory() {
        return peerConnectionFactory;
    }

    AudioTrack getAudioTrack() {
        return audioTrack;
    }

    synchronized boolean setMicrophoneEnabled(final boolean enabled) {
        try {
            audioTrack.setEnabled(enabled);
            return true;
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to toggle shared audio track", e);
            return false;
        }
    }

    EglBase.Context getEglBaseContext() {
        return eglBase.getEglBaseContext();
    }

    synchronized VideoTrack startVideoSending() {
        final VideoTrack track = getOrCreateVideoTrack();
        if (activeVideoSenders == 0) {
            getVideoSourceWrapper().startCapture();
        }
        activeVideoSenders++;
        return track;
    }

    synchronized void stopVideoSending() {
        if (activeVideoSenders == 0) {
            return;
        }
        activeVideoSenders--;
        if (activeVideoSenders == 0) {
            try {
                getVideoSourceWrapper().stopCapture();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(Config.LOGTAG, "could not stop shared video capture", e);
            }
        }
    }

    synchronized boolean isCameraSwitchable() {
        return videoSourceWrapper != null && videoSourceWrapper.isCameraSwitchable();
    }

    synchronized boolean isFrontCamera() {
        return videoSourceWrapper == null || videoSourceWrapper.isFrontCamera();
    }

    synchronized ListenableFuture<Boolean> switchCamera() {
        if (videoSourceWrapper == null) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException("VideoSourceWrapper has not been initialized"));
        }
        return videoSourceWrapper.switchCamera();
    }

    public synchronized void release() {
        Preconditions.checkState(references > 0, "WebRTC resources released more than once");
        references--;
        if (references != 0) {
            return;
        }
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        final VideoTrack videoTrack = this.videoTrack;
        this.videoSourceWrapper = null;
        this.videoTrack = null;
        if (videoTrack != null) {
            videoTrack.dispose();
        }
        if (videoSourceWrapper != null) {
            if (activeVideoSenders > 0) {
                try {
                    videoSourceWrapper.stopCapture();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.e(Config.LOGTAG, "unable to stop shared video capture", e);
                }
            }
            videoSourceWrapper.dispose();
        }
        this.audioTrack.dispose();
        this.audioSource.dispose();
        this.peerConnectionFactory.dispose();
        this.eglBase.release();
    }

    private VideoTrack getOrCreateVideoTrack() {
        if (videoTrack != null) {
            return videoTrack;
        }
        final VideoSourceWrapper videoSourceWrapper = getVideoSourceWrapper();
        this.videoTrack =
                peerConnectionFactory.createVideoTrack(
                        TrackWrapper.id(VideoTrack.class), videoSourceWrapper.getVideoSource());
        return videoTrack;
    }

    private VideoSourceWrapper getVideoSourceWrapper() {
        if (videoSourceWrapper != null) {
            return videoSourceWrapper;
        }
        final VideoSourceWrapper source = new VideoSourceWrapper.Factory(context).create();
        if (source == null) {
            throw new IllegalStateException("Could not instantiate VideoSourceWrapper");
        }
        source.initialize(peerConnectionFactory, context, eglBase.getEglBaseContext());
        this.videoSourceWrapper = source;
        return source;
    }
}
