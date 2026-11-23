package com.wprd.calling.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wprd.calling.data.model.CallRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {
    @Query("SELECT * FROM call_records WHERE ownerKey = :ownerKey ORDER BY timestamp DESC")
    fun getAllCallRecords(ownerKey: String): Flow<List<CallRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallRecord(record: CallRecord)
}
