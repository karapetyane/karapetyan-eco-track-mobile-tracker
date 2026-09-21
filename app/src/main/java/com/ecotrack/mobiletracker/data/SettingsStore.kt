package com.ecotrack.mobiletracker.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val apiKeyStore = SecureTelemetryApiKeyStore(context)

    fun load(): Settings {
        val deviceCode = prefs.getString(KEY_DEVICE_CODE, Settings.DEFAULT_DEVICE_CODE) ?: Settings.DEFAULT_DEVICE_CODE
        val intervalSeconds = prefs.getLong(KEY_INTERVAL, Settings.DEFAULT_INTERVAL_SECONDS)
        return Settings(
            telemetryApiKey = apiKeyStore.load(),
            deviceCode = deviceCode,
            intervalSeconds = intervalSeconds,
        )
    }

    fun save(settings: Settings) {
        apiKeyStore.save(settings.telemetryApiKey)
        prefs.edit()
            .putString(KEY_DEVICE_CODE, settings.deviceCode)
            .putLong(KEY_INTERVAL, settings.intervalSeconds)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "eco_track_settings"
        private const val KEY_DEVICE_CODE = "deviceCode"
        private const val KEY_INTERVAL = "intervalSeconds"
    }
}
