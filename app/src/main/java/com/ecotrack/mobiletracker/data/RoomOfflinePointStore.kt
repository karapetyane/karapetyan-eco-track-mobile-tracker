package com.ecotrack.mobiletracker.data

import com.ecotrack.mobiletracker.tracking.OfflinePointStore
import com.ecotrack.mobiletracker.tracking.QueuedPoint

class RoomOfflinePointStore(
    private val dao: OfflinePointDao,
) : OfflinePointStore {
    override fun insert(point: QueuedPoint): Long {
        return dao.insert(
            OfflinePointEntity(
                sessionId = point.sessionId,
                recordedAtMillis = point.recordedAtMillis,
                latitude = point.latitude,
                longitude = point.longitude,
                speedMps = point.speedMps,
                bearingDeg = point.bearingDeg,
                altitudeM = point.altitudeM,
            ),
        )
    }

    override fun pending(sessionId: String, limit: Int): List<QueuedPoint> {
        return dao.pending(sessionId, limit).map { it.toQueued() }
    }

    override fun deleteById(id: Long) {
        dao.deleteById(id)
    }

    override fun deleteAll() {
        dao.deleteAll()
    }

    override fun count(sessionId: String): Int = dao.count(sessionId)

    private fun OfflinePointEntity.toQueued() = QueuedPoint(
        id = id,
        sessionId = sessionId,
        recordedAtMillis = recordedAtMillis,
        latitude = latitude,
        longitude = longitude,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
        altitudeM = altitudeM,
    )
}
