package com.wprd.calling.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey val messageId: String,
    val senderUid: String,
    val receiverUid: String,
    val text: String,
    val timestamp: Long,
    val isDelivered: Boolean = false,
    val isRead: Boolean = false
)
