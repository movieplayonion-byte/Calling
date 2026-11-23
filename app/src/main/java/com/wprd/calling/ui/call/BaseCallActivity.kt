package com.wprd.calling.ui.call

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wprd.calling.data.local.AppDatabase
import com.wprd.calling.data.local.ProfileSyncManager
import com.wprd.calling.data.model.CallRecord
import com.wprd.calling.service.CallForegroundService
import com.wprd.calling.webrtc.AudioRouteManager
import com.wprd.calling.webrtc.SignalingClient
import com.wprd.calling.webrtc.WebRtcClient
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Shared call session logic for voice & video screens:
 * signaling (Firebase RTDB), WebRTC peer connection, ICE, timer,
 * foreground service, audio routing and call-history saving.
 */
abstract class BaseCallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_PEER_UID = "extra_peer_uid"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_PEER_PHONE = "extra_peer_phone"
        const val EXTRA_CALL_TYPE = "extra_call_type" // VOICE / VIDEO
        const val EXTRA_IS_CALLER = "extra_is_caller"
    }

    protected lateinit var callId: String
    protected lateinit var peerUid: String
    protected lateinit var peerName: String
    protected lateinit var peerPhone: String
    protected var callType: String = "VOICE"
    protected var isCaller: Boolean = true

    protected lateinit var myUid: String
    protected lateinit var myName: String
    protected lateinit var myPhone: String

    protected var webRtcClient: WebRtcClient? = null
    protected var signaling: SignalingClient? = null
    protected lateinit var audioRoute: AudioRouteManager

    protected var localRenderer: SurfaceViewRenderer? = null
    protected var remoteRenderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null

    private val handler = Handler(Looper.getMainLooper())
    private var callStartMillis = 0L
    private var connected = false
    private var finished = false
    private var recordSaved = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (callStartMillis > 0) {
                val secs = ((System.currentTimeMillis() - callStartMillis) / 1000).toInt()
                onTimerTick(formatDuration(secs))
            }
            handler.postDelayed(this, 1000)
        }
    }

    /** Subclasses update their status label here. */
    abstract fun onStatusText(text: String)
    abstract fun onTimerTick(formatted: String)

    /** Called (on the UI thread) when the remote party's video track arrives. */
    protected open fun onRemoteVideoReady() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
        peerUid = intent.getStringExtra(EXTRA_PEER_UID) ?: ""
        peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Unknown"
        peerPhone = intent.getStringExtra(EXTRA_PEER_PHONE) ?: ""
        callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "VOICE"
        isCaller = intent.getBooleanExtra(EXTRA_IS_CALLER, true)

        val profile = ProfileSyncManager.getLocalProfile(this, "")
        myPhone = profile?.phone ?: ""
        myName = profile?.name ?: "Me"
        myUid = ProfileSyncManager.normalizePhoneKey(myPhone)
        audioRoute = AudioRouteManager(this)
    }

    protected fun startSession(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        localRenderer = local; remoteRenderer = remote
        val isVideo = callType == "VIDEO"
        listOf(local, remote).forEach { renderer ->
            renderer?.let {
                // Egl context is shared from the client created below; init lazily in client callback.
                it.setEnableHardwareScaler(true)
            }
        }
        val client = WebRtcClient(this, isVideo, object : WebRtcClient.Listener {
            override fun onLocalIceCandidate(candidate: IceCandidate) {
                signaling?.sendIceCandidate(candidate)
            }
            override fun onRemoteVideoTrack(track: VideoTrack) {
                remoteVideoTrack = track
                // The sink attach itself is owned by WebRtcClient (it holds
                // both the track and the renderer and joins them in either
                // arrival order). Here we only notify the UI.
                runOnUiThread { onRemoteVideoReady() }
            }
            override fun onConnectionConnected() {
                runOnUiThread {
                    if (!connected) {
                        connected = true
                        callStartMillis = System.currentTimeMillis()
                        onStatusText("Connected")
                        handler.post(timerRunnable)
                    }
                }
            }
            override fun onConnectionDisconnected() {
                runOnUiThread { if (!finished) endCall(remoteEnded = true) }
            }
        })
        webRtcClient = client
        // Renderers need the EGL context from this client.
        listOf(local, remote).forEach { it?.init(client.eglContext(), null) }
        local?.setMirror(true)
        remote?.setMirror(false)
        // Hand the remote renderer to the client so it can attach the remote
        // video track's sink itself whenever the track arrives.
        client.setRemoteRenderer(remote)

        client.createPeerConnection()
        client.startLocalMedia(local)
        audioRoute.start(forVideo = isVideo)
        CallForegroundService.start(this, peerName, isVideo)

        val sig = SignalingClient(callId, myUid)
        signaling = sig
        sig.observe(object : SignalingClient.CallListener {
            override fun onStatusChanged(status: String) {
                runOnUiThread {
                    when (status) {
                        "DECLINED" -> { toast("Call declined"); endCall(remoteEnded = true) }
                        "ENDED" -> endCall(remoteEnded = true)
                        "ACCEPTED" -> if (isCaller) onStatusText("Connecting…")
                    }
                }
            }
            override fun onOfferReceived(sdp: String) {
                // Handles both the initial offer (callee) and mid-call renegotiation (either side).
                client.setRemoteDescription(SessionDescription.Type.OFFER, sdp) {
                    client.createAnswer { answer -> sig.sendAnswer(answer.description) }
                }
            }
            override fun onAnswerReceived(sdp: String) {
                client.setRemoteDescription(SessionDescription.Type.ANSWER, sdp)
            }
            override fun onRemoteIceCandidate(candidate: IceCandidate) {
                client.addIceCandidate(candidate)
            }
        }, peerUid = peerUid)

        if (isCaller) {
            sig.createCall(
                callerName = myName, callerPhone = myPhone,
                receiverUid = peerUid, receiverName = peerName, receiverPhone = peerPhone,
                callType = callType
            )
            client.createOffer { offer -> sig.sendOffer(offer.description) }
            onStatusText("Ringing…")
        } else {
            sig.setStatus("ACCEPTED")
            onStatusText("Connecting…")
        }
    }

    /** Voice -> video upgrade inside the same call (renegotiation). */
    protected fun upgradeToVideo() {
        val client = webRtcClient ?: return
        callType = "VIDEO"
        client.enableVideo(localRenderer, remoteRenderer)
        client.createOffer { offer -> signaling?.sendOffer(offer.description) }
    }

    protected fun endCall(remoteEnded: Boolean = false) {
        if (finished) return
        finished = true
        if (!remoteEnded) signaling?.setStatus("ENDED")
        saveCallRecord()
        cleanup()
        finish()
    }

    private fun saveCallRecord() {
        if (recordSaved) return
        recordSaved = true
        val duration = if (callStartMillis > 0)
            ((System.currentTimeMillis() - callStartMillis) / 1000).toInt() else 0
        val direction = when {
            !isCaller && !connected -> "MISSED"
            isCaller -> "OUTGOING"
            else -> "INCOMING"
        }
        lifecycleScope.launch {
            try {
                AppDatabase.getInstance(this@BaseCallActivity).callDao().insertCallRecord(
                    CallRecord(
                        peerUid = peerUid, peerName = peerName, peerPhone = peerPhone,
                        callType = callType, direction = direction,
                        timestamp = System.currentTimeMillis(), durationSeconds = duration
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private fun cleanup() {
        handler.removeCallbacks(timerRunnable)
        signaling?.stopObserving()
        webRtcClient?.close(); webRtcClient = null
        if (::audioRoute.isInitialized) audioRoute.stop()
        CallForegroundService.stop(this)
        localRenderer?.release(); remoteRenderer?.release()
    }

    override fun onBackPressed() {
        // A live call must be ended with the End button, not silently dropped.
        endCall()
    }

    override fun onDestroy() {
        if (!finished) { finished = true; saveCallRecord(); cleanup() }
        super.onDestroy()
    }

    protected fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun formatDuration(totalSecs: Int): String =
        "%02d:%02d".format(totalSecs / 60, totalSecs % 60)
}
