package com.wprd.calling.webrtc

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.webrtc.IceCandidate

/**
 * Firebase Realtime Database signaling.
 *
 * Layout:
 *   calls/<callId>                     -> call metadata + status + offer/answer SDP
 *   calls/<callId>/candidates/<uid>/...  -> ICE candidates published by <uid>
 *
 * Status flow: RINGING -> ACCEPTED -> ENDED  (or DECLINED / MISSED)
 */
class SignalingClient(private val callId: String, private val myUid: String) {

    interface CallListener {
        fun onStatusChanged(status: String) {}
        fun onOfferReceived(sdp: String) {}
        fun onAnswerReceived(sdp: String) {}
        fun onRemoteIceCandidate(candidate: IceCandidate) {}
    }

    private val callRef: DatabaseReference =
        FirebaseDatabase.getInstance().reference.child("calls").child(callId)

    private var callListenerRef: ValueEventListener? = null
    private var iceListenerRef: ChildEventListener? = null
    private var lastOfferSdp: String? = null
    private var lastAnswerSdp: String? = null

    fun createCall(
        callerName: String, callerPhone: String,
        receiverUid: String, receiverName: String, receiverPhone: String,
        callType: String
    ) {
        val payload = hashMapOf<String, Any>(
            "callId" to callId,
            "callerUid" to myUid,
            "callerName" to callerName,
            "callerPhone" to callerPhone,
            "receiverUid" to receiverUid,
            "receiverName" to receiverName,
            "receiverPhone" to receiverPhone,
            "callType" to callType,
            "status" to "RINGING",
            "createdAt" to System.currentTimeMillis()
        )
        callRef.setValue(payload)
    }

    fun sendOffer(sdp: String) {
        callRef.child("offerSdp").setValue(sdp)
        callRef.child("offerBy").setValue(myUid)
    }
    fun sendAnswer(sdp: String) {
        callRef.child("answerSdp").setValue(sdp)
        callRef.child("answerBy").setValue(myUid)
    }
    fun setStatus(status: String) = callRef.child("status").setValue(status)

    fun sendIceCandidate(candidate: IceCandidate) {
        val data = hashMapOf<String, Any>(
            "sdpMid" to (candidate.sdpMid ?: ""),
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "candidate" to candidate.sdp
        )
        callRef.child("candidates").child(myUid).push().setValue(data)
    }

    fun observe(listener: CallListener, peerUid: String) {
        callListenerRef = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!snap.exists()) return
                snap.child("status").getValue(String::class.java)?.let { listener.onStatusChanged(it) }
                val offer = snap.child("offerSdp").getValue(String::class.java)
                val offerBy = snap.child("offerBy").getValue(String::class.java)
                if (!offer.isNullOrBlank() && offer != lastOfferSdp && offerBy != myUid) {
                    lastOfferSdp = offer
                    listener.onOfferReceived(offer)
                }
                val answer = snap.child("answerSdp").getValue(String::class.java)
                val answerBy = snap.child("answerBy").getValue(String::class.java)
                if (!answer.isNullOrBlank() && answer != lastAnswerSdp && answerBy != myUid) {
                    lastAnswerSdp = answer
                    listener.onAnswerReceived(answer)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        callRef.addValueEventListener(callListenerRef!!)

        iceListenerRef = object : ChildEventListener {
            override fun onChildAdded(snap: DataSnapshot, previousChildName: String?) {
                val mid = snap.child("sdpMid").getValue(String::class.java) ?: ""
                val index = snap.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                val sdp = snap.child("candidate").getValue(String::class.java) ?: return
                listener.onRemoteIceCandidate(IceCandidate(mid, index, sdp))
            }
            override fun onChildChanged(snap: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snap: DataSnapshot) {}
            override fun onChildMoved(snap: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        callRef.child("candidates").child(peerUid)
            .addChildEventListener(iceListenerRef!!)
    }

    fun stopObserving() {
        callListenerRef?.let { callRef.removeEventListener(it) }
        iceListenerRef?.let { callRef.child("candidates").removeEventListener(it) }
        callListenerRef = null; iceListenerRef = null
    }

    companion object {
        /** Listens on calls addressed to [myUid] that are still RINGING. Used by MainActivity. */
        fun observeIncomingCalls(
            myUid: String,
            onIncoming: (DataSnapshot) -> Unit
        ): ChildEventListener {
            val ref = FirebaseDatabase.getInstance().reference.child("calls")
            val listener = object : ChildEventListener {
                override fun onChildAdded(snap: DataSnapshot, previousChildName: String?) = check(snap)
                override fun onChildChanged(snap: DataSnapshot, previousChildName: String?) = check(snap)
                private fun check(snap: DataSnapshot) {
                    val receiver = snap.child("receiverUid").getValue(String::class.java)
                    val status = snap.child("status").getValue(String::class.java)
                    if (receiver == myUid && status == "RINGING") onIncoming(snap)
                }
                override fun onChildRemoved(snap: DataSnapshot) {}
                override fun onChildMoved(snap: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            }
            ref.addChildEventListener(listener)
            return listener
        }
    }
}
