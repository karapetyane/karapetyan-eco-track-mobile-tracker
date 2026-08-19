package com.ecotrack.mobiletracker.tracking

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Reads battery percentage from the sticky [Intent.ACTION_BATTERY_CHANGED] broadcast.
 * No runtime permission required.
 */
object BatteryReader {

    /**
     * @return integer percent 0..100, or null if level/scale are invalid
     */
    fun percentFromExtras(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0) return null
        return ((100.0 * level) / scale).toInt().coerceIn(0, 100)
    }

    /**
     * Best-effort read of current battery percent. Never throws.
     */
    fun readPercent(context: Context): Int? {
        return try {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            percentFromExtras(level, scale)
        } catch (_: Exception) {
            null
        }
    }
}
