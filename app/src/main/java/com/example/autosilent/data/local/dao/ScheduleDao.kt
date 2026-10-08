package com.example.autosilent.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.autosilent.data.local.entity.ScheduleEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface ScheduleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(schedule: ScheduleEntity): Long

    @Update
    suspend fun update(schedule: ScheduleEntity)

    @Delete
    suspend fun delete(schedule: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getScheduleById(id: Long): ScheduleEntity?

    @Query("SELECT * FROM schedules ORDER BY startTime ASC")
    fun getAllSchedulesFlow(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE enabled = 1 ORDER BY startTime ASC")
    suspend fun getEnabledSchedules(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE enabled = 1 ORDER BY startTime ASC")
    fun getEnabledSchedulesFlow(): Flow<List<ScheduleEntity>>

    @Query("UPDATE schedules SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Instant)
}
