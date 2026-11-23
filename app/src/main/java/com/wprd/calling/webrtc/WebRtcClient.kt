package com.wprd.calling.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.EglBase

/**
 * Native WebRTC peer connection wrapper (audio + optional video).
 *
 * Lightweight notes:
 * - Single PeerConnection per call, one audio track, at most one video track.
 * - Opus / VP8 defaults from the native stack; no extra codecs are loaded.
 * - TURN is optional: add a TURN entry in [ICE_SERVERS] when you have
 *   credentials, otherwise calls work on most networks via Google STUN.
 */
class WebRtcClient(
    private val context: Context,
    private val isVideoCall: Boolean,
    private val listener: Listener
) {
    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onConnectionConnected()
        fun onConnectionDisconnected()
    }

    companion object {
        private const val TAG = "WebRtcClient"
        // Metered TURN credentials (user's account, added 2026-11-22) so calls also
        // connect behind strict NAT / mobile data, not just open networks.
        private const val TURN_USERNAME = "072370e632e8dc050df33b70"
        private const val TURN_CREDENTIAL = "u2qkp9zLPtnnFl22"
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer(),
            PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80")
                .setUsername(TURN_USERNAME).setPassword(TURN_CREDENTIAL).createIceServer(),
            PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80?transport=tcp")
                .setUsername(TURN_USERNAME).setPassword(TURN_CREDENTIAL).createIceServer(),
            PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443")
                .setUsername(TURN_USERNAME).setPassword(TURN_CREDENTIAL).createIceServer(),
            PeerConnection.IceServer.builder("turns:global.relay.metered.ca:443?transport=tcp")
                .setUsername(TURN_USERNAME).setPassword(TURN_CREDENTIAL).createIceServer()
        )
        @Volatile private var factoryInitialized = false
    }

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // Remote video: the client owns both ends of the sink attach, so whichever
    // arrives second (track from onAddTrack, or renderer from the activity)
    // completes the connection. Attaching only from the activity callback
    // risks a track that never gets a sink (blank remote video).
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    var isMicEnabled = true
        private set
    var isVideoEnabled = isVideoCall
        private set

    init {
        initFactory()
    }

    private fun initFactory() {
        synchronized(WebRtcClient::class.java) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions()
                )
                factoryInitialized = true
            }
        }
        eglBase = EglBase.create()
        // CRITICAL: the factory's video encoder/decoder MUST share this same
        // EglBase as the SurfaceViewRenderers and the camera capturer.
        // PeerConnectionFactory.builder() with no factories uses hardware
        // codecs bound to the factory's own internal EGL context; remote
        // frames decoded there are textures in a different GL context and
        // the activity's renderer (initialised with this eglBase) draws
        // nothing for them — the classic "local preview fine, remote video
        // black/blank" bug. Sharing one EglBase end to end fixes it.
        val sharedEglContext = eglBase?.eglBaseContext
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(sharedEglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(sharedEglContext))
            .createPeerConnectionFactory()
    }

    fun eglContext() = eglBase?.eglBaseContext

    fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Audio-first: keep bandwidth low for voice, adaptive for video.
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onLocalIceCandidate(candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> listener.onConnectionConnected()
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> listener.onConnectionDisconnected()
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                (receiver.track() as? VideoTrack)?.let { track ->
                    track.setEnabled(true)
                    remoteVideoTrack = track
                    // Attach immediately if the renderer is already known;
                    // if it is set later, setRemoteRenderer() attaches then.
                    remoteRenderer?.let { track.addSink(it) }
                    listener.onRemoteVideoTrack(track)
                }
            }
        })
    }

    /** Creates local audio (and video when [isVideoCall]) and attaches them to the peer connection. */
    fun startLocalMedia(localRenderer: SurfaceViewRenderer?) {
        val f = factory ?: return
        // Audio
        audioSource = f.createAudioSource(MediaConstraints())
        localAudioTrack = f.createAudioTrack("audio0", audioSource)
        localAudioTrack?.setEnabled(true)
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("stream0")) }

        if (!isVideoCall) return
        // Video
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        videoCapturer = enumerator.createCapturer(deviceName, null)
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
        videoSource = f.createVideoSource(videoCapturer?.isScreencast ?: false)
        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        // 720p max keeps the app fast / light on mid-range phones and mobile data.
        videoCapturer?.startCapture(1280, 720, 30)
        localVideoTrack = f.createVideoTrack("video0", videoSource)
        localVideoTrack?.setEnabled(true)
        localRenderer?.let { localVideoTrack?.addSink(it) }
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("stream0")) }
    }

    /** Adds a video track mid-call (voice -> video upgrade) and reuses the same peer connection. */
    fun enableVideo(localRenderer: SurfaceViewRenderer?, remoteRenderer: SurfaceViewRenderer?) {
        // Make sure the client knows the remote renderer before renegotiation,
        // so the incoming remote video track gets its sink attached on arrival.
        if (remoteRenderer != null) setRemoteRenderer(remoteRenderer)
        if (localVideoTrack != null) { setVideoEnabled(true); return }
        val f = factory ?: return
        val enumerator = Camera2Enumerator(context)
        val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        videoCapturer = enumerator.createCapturer(deviceName, null)
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
        videoSource = f.createVideoSource(videoCapturer?.isScreencast ?: false)
        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30)
        localVideoTrack = f.createVideoTrack("video0", videoSource)
        localVideoTrack?.setEnabled(true)
        localRenderer?.let { localVideoTrack?.addSink(it) }
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("stream0")) }
        isVideoEnabled = true
    }

    /** Registers the renderer that should show the remote party's video. */
    fun setRemoteRenderer(renderer: SurfaceViewRenderer?) {
        remoteRenderer = renderer
        // Track may have arrived before the renderer was set: attach now.
        if (renderer != null) remoteVideoTrack?.let { it.setEnabled(true); it.addSink(renderer) }
    }

    /**
     * Remote renderer ko doosre renderer se badalta hai (voice screen se
     * video screen par jaate samay). Purane renderer se sink pehle hataya
     * jata hai, warna track do jagah jud jata hai aur purana surface release
     * hone par frame atak/crash ho sakta hai.
     */
    fun swapRemoteRenderer(renderer: SurfaceViewRenderer?) {
        val old = remoteRenderer
        if (old != null && old !== renderer) {
            try { remoteVideoTrack?.removeSink(old) } catch (_: Exception) {}
        }
        setRemoteRenderer(renderer)
    }

    fun attachRemoteVideo(track: VideoTrack, remoteRenderer: SurfaceViewRenderer?) {
        remoteVideoTrack = track
        this.remoteRenderer = remoteRenderer
        remoteRenderer?.let { track.addSink(it) }
    }

    private val sdpObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) { Log.w(TAG, "SDP create failed: $error") }
        override fun onSetFailure(error: String) { Log.w(TAG, "SDP set failed: $error") }
    }

    fun createOffer(onOffer: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
        }
        peerConnection?.createOffer(object : SdpObserver by sdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(sdpObserver, desc)
                onOffer(desc)
            }
        }, constraints)
    }

    fun createAnswer(onAnswer: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
        }
        peerConnection?.createAnswer(object : SdpObserver by sdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(sdpObserver, desc)
                onAnswer(desc)
            }
        }, constraints)
    }

    fun setRemoteDescription(type: SessionDescription.Type, sdp: String, onDone: () -> Unit = {}) {
        val desc = SessionDescription(type, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver by sdpObserver {
            override fun onSetSuccess() { onDone() }
        }, desc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun setMicEnabled(enabled: Boolean) {
        isMicEnabled = enabled
        localAudioTrack?.setEnabled(enabled)
    }

    fun setVideoEnabled(enabled: Boolean) {
        isVideoEnabled = enabled
        localVideoTrack?.setEnabled(enabled)
        try {
            if (enabled) videoCapturer?.startCapture(1280, 720, 30) else videoCapturer?.stopCapture()
        } catch (_: Exception) {}
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun close() {
        remoteRenderer?.let { r -> try { remoteVideoTrack?.removeSink(r) } catch (_: Exception) {} }
        remoteVideoTrack = null; remoteRenderer = null
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        videoCapturer?.dispose(); videoCapturer = null
        surfaceTextureHelper?.dispose(); surfaceTextureHelper = null
        videoSource?.dispose(); videoSource = null
        audioSource?.dispose(); audioSource = null
        peerConnection?.close(); peerConnection?.dispose(); peerConnection = null
        factory?.dispose(); factory = null
        eglBase?.release(); eglBase = null
    }
}
