package com.ecotrack.mobiletracker.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Settings {
        val host = prefs.getString(KEY_HOST, Settings.DEFAULT_HOST) ?: Settings.DEFAULT_HOST
        val port = prefs.getInt(KEY_PORT, Settings.DEFAULT_PORT)
        val deviceCode = prefs.getString(KEY_DEVICE_CODE, Settings.DEFAULT_DEVICE_CODE) ?: Settings.DEFAULT_DEVICE_CODE
        val intervalSeconds = prefs.getLong(KEY_INTERVAL, Settings.DEFAULT_INTERVAL_SECONDS)
        return Settings(host = host, port = port, deviceCode = deviceCode, intervalSeconds = intervalSeconds)
    }

    fun save(settings: Settings) {
        prefs.edit()
            .putString(KEY_HOST, settings.host)
            .putInt(KEY_PORT, settings.port)
            .putString(KEY_DEVICE_CODE, settings.deviceCode)
            .putLong(KEY_INTERVAL, settings.intervalSeconds)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "eco_track_settings"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_DEVICE_CODE = "deviceCode"
        private const val KEY_INTERVAL = "intervalSeconds"
    }
}

