package com.wprd.calling.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.wprd.calling.data.model.CallRecord
import com.wprd.calling.data.model.ChatMessage

@Database(entities = [CallRecord::class, ChatMessage::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callDao(): CallDao
}
