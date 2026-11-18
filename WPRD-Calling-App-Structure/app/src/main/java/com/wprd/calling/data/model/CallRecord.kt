package com.wprd.calling.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "call_records")
data class CallRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerUid: String,
    val peerName: String,
    val peerPhone: String,
    val callType: String, // VOICE or VIDEO
    val direction: String, // INCOMING, OUTGOING, MISSED
    val timestamp: Long,
    val durationSeconds: Int = 0
)
