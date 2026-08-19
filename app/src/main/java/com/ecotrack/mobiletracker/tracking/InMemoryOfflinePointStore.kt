package com.ecotrack.mobiletracker.tracking

/**
 * Durable-queue stand-in for unit tests. Pass a shared [rows] list to simulate
 * process/repository recreation against the same backing storage.
 */
class InMemoryOfflinePointStore(
    private val rows: MutableList<QueuedPoint> = mutableListOf(),
) : OfflinePointStore {
    private var nextId = (rows.maxOfOrNull { it.id } ?: 0L) + 1L

    @Synchronized
    override fun insert(point: QueuedPoint): Long {
        val id = if (point.id != 0L) point.id else nextId++
        if (id >= nextId) nextId = id + 1
        rows.add(point.copy(id = id))
        return id
    }

    @Synchronized
    override fun pending(sessionId: String, limit: Int): List<QueuedPoint> {
        return rows
            .filter { it.sessionId == sessionId }
            .sortedWith(compareBy<QueuedPoint> { it.recordedAtMillis }.thenBy { it.id })
            .take(limit)
    }

    @Synchronized
    override fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    @Synchronized
    override fun deleteAll() {
        rows.clear()
    }

    @Synchronized
    override fun count(sessionId: String): Int = rows.count { it.sessionId == sessionId }
}
