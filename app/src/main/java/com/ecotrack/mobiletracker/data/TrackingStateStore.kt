package com.ecotrack.mobiletracker.data

import android.content.Context

/**
 * Persists whether tracking is active and the current session id so
 * START_STICKY recreation can resume without treating it as a new user Start.
 */
class TrackingStateStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val apiKeyStore = SecureTelemetryApiKeyStore(context)

    fun isTracking(): Boolean = prefs.getBoolean(KEY_TRACKING, false)

    fun sessionId(): String? = prefs.getString(KEY_SESSION_ID, null)

    fun loadSettings(): Settings {
        val deviceCode = prefs.getString(KEY_DEVICE_CODE, Settings.DEFAULT_DEVICE_CODE) ?: Settings.DEFAULT_DEVICE_CODE
        val intervalSeconds = prefs.getLong(KEY_INTERVAL, Settings.DEFAULT_INTERVAL_SECONDS)
        return Settings(
            telemetryApiKey = apiKeyStore.load(),
            deviceCode = deviceCode,
            intervalSeconds = intervalSeconds,
        )
    }

    fun saveActiveSession(sessionId: String, settings: Settings) {
        apiKeyStore.save(settings.telemetryApiKey)
        prefs.edit()
            .putBoolean(KEY_TRACKING, true)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_DEVICE_CODE, settings.deviceCode)
            .putLong(KEY_INTERVAL, settings.intervalSeconds)
            .apply()
    }

    fun markStopped() {
        prefs.edit().putBoolean(KEY_TRACKING, false).apply()
    }

    companion object {
        private const val PREFS_NAME = "eco_track_tracking_state"
        private const val KEY_TRACKING = "tracking"
        private const val KEY_SESSION_ID = "sessionId"
        private const val KEY_DEVICE_CODE = "deviceCode"
        private const val KEY_INTERVAL = "intervalSeconds"
    }
}
