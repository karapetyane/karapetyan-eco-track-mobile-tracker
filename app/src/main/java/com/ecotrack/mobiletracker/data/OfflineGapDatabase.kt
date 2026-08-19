package com.ecotrack.mobiletracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [OfflinePointEntity::class], version = 1, exportSchema = false)
abstract class OfflineGapDatabase : RoomDatabase() {
    abstract fun offlinePointDao(): OfflinePointDao

    companion object {
        private const val DB_NAME = "offline_gap.db"

        @Volatile
        private var instance: OfflineGapDatabase? = null

        fun get(context: Context): OfflineGapDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OfflineGapDatabase::class.java,
                    DB_NAME,
                )
                    // Previous APKs had no this DB; future schema bumps must not crash on upgrade.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
