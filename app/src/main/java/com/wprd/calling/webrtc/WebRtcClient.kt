package com.wprd.calling.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
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
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            // TODO(TURN): add your TURN server here for calls behind strict NAT / mobile data:
            // PeerConnection.IceServer.builder("turn:YOUR_TURN_HOST:3478")
            //     .setUsername("USER").setPassword("PASS").createIceServer()
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
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
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
                (receiver.track() as? VideoTrack)?.let { listener.onRemoteVideoTrack(it) }
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

    fun attachRemoteVideo(track: VideoTrack, remoteRenderer: SurfaceViewRenderer?) {
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
