package com.ecotrack.mobiletracker.tracking

import java.time.Instant

data class QueuedPoint(
    val id: Long = 0L,
    val sessionId: String,
    val recordedAtMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val altitudeM: Double? = null,
    val accuracyM: Double? = null,
    val messageId: String,
) {
    fun toSample(): GpsSample = GpsSample(
        recordedAt = Instant.ofEpochMilli(recordedAtMillis),
        latitude = latitude,
        longitude = longitude,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
        altitudeM = altitudeM,
        accuracyM = accuracyM,
        messageId = messageId,
    )

    companion object {
        fun fromSample(sessionId: String, sample: GpsSample): QueuedPoint = QueuedPoint(
            sessionId = sessionId,
            recordedAtMillis = sample.recordedAt.toEpochMilli(),
            latitude = sample.latitude,
            longitude = sample.longitude,
            speedMps = sample.speedMps,
            bearingDeg = sample.bearingDeg,
            altitudeM = sample.altitudeM,
            accuracyM = sample.accuracyM,
            messageId = sample.messageId,
        )
    }
}

object QueueBounds {
    const val MAX_PENDING_POINTS = 10_000

    /**
     * Drop oldest points first so the newest route segment is kept.
     * @return number of dropped rows
     */
    fun trimOldest(store: OfflinePointStore, sessionId: String, maxCount: Int = MAX_PENDING_POINTS): Int {
        var dropped = 0
        while (store.count(sessionId) > maxCount) {
            val oldest = store.pending(sessionId, limit = 1).firstOrNull() ?: break
            store.deleteById(oldest.id)
            dropped++
        }
        return dropped
    }
}
