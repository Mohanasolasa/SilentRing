package com.example.autosilent.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "occurrence_overrides",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["scheduleId", "occurrenceStart"], unique = true),
        Index(value = ["occurrenceEnd"])
    ]
)
data class OccurrenceOverrideEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val scheduleId: Long,
    val occurrenceStart: Instant,
    val occurrenceEnd: Instant,
    val createdAt: Instant = Instant.now()
)
