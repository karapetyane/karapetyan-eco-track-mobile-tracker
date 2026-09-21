package com.ecotrack.mobiletracker.tracking

import java.time.Instant

data class GpsSample(
    val recordedAt: Instant,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val altitudeM: Double? = null,
    val accuracyM: Double? = null,
    val messageId: String,
) {
    val speedKnots: Double?
        get() = speedMps?.takeIf { it >= 0 }?.let { it * 1.943844 }

    val speedKmh: Double?
        get() = speedMps?.takeIf { it >= 0 }?.let { it * 3.6 }
}

object LocationTimestamps {
    /**
     * Prefer GNSS/fix time when Android reports a positive Location.time; otherwise sampling clock.
     */
    fun recordedAtMillis(locationTimeMillis: Long, sampleTimeMillis: Long): Long {
        return if (locationTimeMillis > 0L) locationTimeMillis else sampleTimeMillis
    }
}
