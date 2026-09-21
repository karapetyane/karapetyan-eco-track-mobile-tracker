package com.ecotrack.mobiletracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [OfflinePointEntity::class], version = 2, exportSchema = false)
abstract class OfflineGapDatabase : RoomDatabase() {
    abstract fun offlinePointDao(): OfflinePointDao

    companion object {
        private const val DB_NAME = "offline_gap.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offline_points ADD COLUMN accuracyM REAL")
                db.execSQL(
                    "ALTER TABLE offline_points ADD COLUMN messageId TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "UPDATE offline_points SET messageId = 'legacy-' || id WHERE messageId = ''",
                )
            }
        }

        @Volatile
        private var instance: OfflineGapDatabase? = null

        fun get(context: Context): OfflineGapDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OfflineGapDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    // Last resort for unknown older schemas; prefer MIGRATION_1_2 for v1→v2.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
