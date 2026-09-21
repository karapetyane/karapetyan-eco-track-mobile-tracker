package com.ecotrack.mobiletracker.data

data class Settings(
    val telemetryApiKey: String,
    val deviceCode: String,
    val intervalSeconds: Long,
) {
    companion object {
        const val DEFAULT_INGEST_URL = "https://167.233.232.174/api/v1/gps/telemetry/ingest"
        const val DEFAULT_DEVICE_CODE = "NMEA-TCP-001"
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val DEFAULT_TELEMETRY_API_KEY = ""
    }
}
