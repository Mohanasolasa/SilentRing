package com.example.autosilent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.autosilent.data.local.entity.OccurrenceOverrideEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface OccurrenceOverrideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(override: OccurrenceOverrideEntity): Long

    @Query("SELECT * FROM occurrence_overrides")
    suspend fun getAllOverrides(): List<OccurrenceOverrideEntity>

    @Query("SELECT * FROM occurrence_overrides")
    fun getAllOverridesFlow(): Flow<List<OccurrenceOverrideEntity>>

    @Query("SELECT * FROM occurrence_overrides WHERE scheduleId = :scheduleId")
    suspend fun getOverridesForSchedule(scheduleId: Long): List<OccurrenceOverrideEntity>

    @Query("SELECT * FROM occurrence_overrides WHERE occurrenceEnd > :now")
    suspend fun getActiveOverrides(now: Instant): List<OccurrenceOverrideEntity>

    @Query("SELECT * FROM occurrence_overrides WHERE occurrenceEnd > :now")
    fun getActiveOverridesFlow(now: Instant): Flow<List<OccurrenceOverrideEntity>>

    @Query("DELETE FROM occurrence_overrides WHERE occurrenceEnd <= :now")
    suspend fun deleteExpiredOverrides(now: Instant): Int

    @Query("DELETE FROM occurrence_overrides WHERE scheduleId = :scheduleId")
    suspend fun deleteByScheduleId(scheduleId: Long): Int

    @Query("DELETE FROM occurrence_overrides WHERE scheduleId = :scheduleId AND occurrenceStart = :occurrenceStart")
    suspend fun deleteByScheduleAndOccurrence(scheduleId: Long, occurrenceStart: Instant): Int
}
