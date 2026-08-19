package com.ecotrack.mobiletracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "offline_points",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sessionId", "recordedAtMillis", "id"]),
    ],
)
data class OfflinePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val recordedAtMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val altitudeM: Double?,
)
