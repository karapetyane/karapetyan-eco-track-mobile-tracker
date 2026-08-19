package com.ecotrack.mobiletracker.tracking

interface OfflinePointStore {
    fun insert(point: QueuedPoint): Long
    fun pending(sessionId: String, limit: Int): List<QueuedPoint>
    fun deleteById(id: Long)
    fun deleteAll()
    fun count(sessionId: String): Int
}
