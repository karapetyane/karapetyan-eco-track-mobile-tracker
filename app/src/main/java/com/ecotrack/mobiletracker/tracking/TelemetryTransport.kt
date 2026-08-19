package com.ecotrack.mobiletracker.tracking

interface TelemetryTransport {
    val isConnected: Boolean
    fun connect()
    fun close()
    fun sendPdev(deviceCode: String)
    /** Live online path: optional PBAT, then GPRMC, then GPGGA. */
    fun sendLive(sample: GpsSample, batteryPercent: Int?)
    /** Gap replay: GPRMC only, using sample.recordedAt. */
    fun sendHistoricalRmc(sample: GpsSample)
}
