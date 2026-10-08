package com.example.autosilent.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.autosilent.data.local.dao.OccurrenceOverrideDao
import com.example.autosilent.data.local.dao.ScheduleDao
import com.example.autosilent.data.local.entity.OccurrenceOverrideEntity
import com.example.autosilent.data.local.entity.ScheduleEntity

@Database(
    entities = [
        ScheduleEntity::class,
        OccurrenceOverrideEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scheduleDao(): ScheduleDao
    abstract fun occurrenceOverrideDao(): OccurrenceOverrideDao

    companion object {
        const val DATABASE_NAME = "auto_silent.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
