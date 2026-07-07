package com.ecotrack.mobiletracker.data

data class Settings(
    val host: String,
    val port: Int,
    val deviceCode: String,
    val intervalSeconds: Long,
) {
    companion object {
        const val DEFAULT_HOST = "167.233.232.174"
        const val DEFAULT_PORT = 9100
        const val DEFAULT_DEVICE_CODE = "NMEA-TCP-001"
        const val DEFAULT_INTERVAL_SECONDS = 5L
    }
}

