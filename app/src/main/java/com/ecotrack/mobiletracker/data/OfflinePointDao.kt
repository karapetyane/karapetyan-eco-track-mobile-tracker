package com.ecotrack.mobiletracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OfflinePointDao {
    @Insert
    fun insert(entity: OfflinePointEntity): Long

    @Query(
        "SELECT * FROM offline_points WHERE sessionId = :sessionId " +
            "ORDER BY recordedAtMillis ASC, id ASC LIMIT :limit",
    )
    fun pending(sessionId: String, limit: Int): List<OfflinePointEntity>

    @Query("DELETE FROM offline_points WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM offline_points")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM offline_points WHERE sessionId = :sessionId")
    fun count(sessionId: String): Int
}
