package com.wprd.calling.data.model

data class User(
    val uid: String = "",
    val name: String = "",
    val phone: String = "",
    val avatarUrl: String = "",
    val isOnline: Boolean = false,
    val lastSeen: Long = 0L
)
