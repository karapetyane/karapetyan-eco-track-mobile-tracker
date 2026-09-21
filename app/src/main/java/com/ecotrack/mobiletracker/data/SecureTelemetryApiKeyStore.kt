package com.ecotrack.mobiletracker.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed encrypted storage for the GPS telemetry API key.
 * Never logs the key value.
 */
class SecureTelemetryApiKeyStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy { encryptedPrefs() }

    fun load(): String {
        migratePlaintextIfPresent()
        return prefs.getString(KEY_TELEMETRY_API_KEY, Settings.DEFAULT_TELEMETRY_API_KEY)
            ?: Settings.DEFAULT_TELEMETRY_API_KEY
    }

    fun save(apiKey: String) {
        prefs.edit().putString(KEY_TELEMETRY_API_KEY, apiKey).apply()
        purgePlaintextCopies()
    }

    private fun encryptedPrefs() =
        EncryptedSharedPreferences.create(
            appContext,
            ENCRYPTED_PREFS_NAME,
            MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private fun migratePlaintextIfPresent() {
        if (prefs.contains(KEY_TELEMETRY_API_KEY)) {
            purgePlaintextCopies()
            return
        }
        val legacy = readPlaintextLegacy()
        if (legacy != null) {
            prefs.edit().putString(KEY_TELEMETRY_API_KEY, legacy).apply()
        }
        purgePlaintextCopies()
    }

    private fun readPlaintextLegacy(): String? {
        val fromSettings = appContext
            .getSharedPreferences(LEGACY_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TELEMETRY_API_KEY, null)
        if (!fromSettings.isNullOrEmpty()) return fromSettings
        val fromTracking = appContext
            .getSharedPreferences(LEGACY_TRACKING_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TELEMETRY_API_KEY, null)
        return fromTracking?.takeIf { it.isNotEmpty() }
    }

    private fun purgePlaintextCopies() {
        appContext.getSharedPreferences(LEGACY_SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TELEMETRY_API_KEY)
            .apply()
        appContext.getSharedPreferences(LEGACY_TRACKING_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TELEMETRY_API_KEY)
            .apply()
    }

    companion object {
        private const val ENCRYPTED_PREFS_NAME = "eco_track_telemetry_api_key_encrypted"
        private const val KEY_TELEMETRY_API_KEY = "telemetryApiKey"
        private const val LEGACY_SETTINGS_PREFS = "eco_track_settings"
        private const val LEGACY_TRACKING_PREFS = "eco_track_tracking_state"
    }
}
