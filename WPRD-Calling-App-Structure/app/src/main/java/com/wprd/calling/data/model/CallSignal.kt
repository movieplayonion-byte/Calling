package com.wprd.calling.data.model

data class CallSignal(
    val callId: String = "",
    val callerUid: String = "",
    val callerName: String = "",
    val receiverUid: String = "",
    val callType: String = "VOICE", // VOICE or VIDEO
    val type: String = "", // OFFER, ANSWER, ICE, END
    val sdp: String = "",
    val iceCandidate: String = "",
    val timestamp: Long = 0L
)
