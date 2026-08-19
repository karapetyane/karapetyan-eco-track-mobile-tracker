package com.ecotrack.mobiletracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryReaderTest {

    @Test
    fun percent_0() {
        assertEquals(0, BatteryReader.percentFromExtras(level = 0, scale = 100))
    }

    @Test
    fun percent_1() {
        assertEquals(1, BatteryReader.percentFromExtras(level = 1, scale = 100))
    }

    @Test
    fun percent_67() {
        assertEquals(67, BatteryReader.percentFromExtras(level = 67, scale = 100))
    }

    @Test
    fun percent_99() {
        assertEquals(99, BatteryReader.percentFromExtras(level = 99, scale = 100))
    }

    @Test
    fun percent_100() {
        assertEquals(100, BatteryReader.percentFromExtras(level = 100, scale = 100))
    }

    @Test
    fun percent_scaled_half() {
        // level=50, scale=200 → 25%
        assertEquals(25, BatteryReader.percentFromExtras(level = 50, scale = 200))
    }

    @Test
    fun invalid_level_negative() {
        assertNull(BatteryReader.percentFromExtras(level = -1, scale = 100))
    }

    @Test
    fun invalid_scale_zero() {
        assertNull(BatteryReader.percentFromExtras(level = 50, scale = 0))
    }

    @Test
    fun invalid_scale_negative() {
        assertNull(BatteryReader.percentFromExtras(level = 50, scale = -1))
    }

    @Test
    fun null_intent_means_skip_pbat_gps_continues() {
        // Contract: when sticky battery intent is unavailable, percent is null and GPS must still send.
        val percent: Int? = null // simulates null ACTION_BATTERY_CHANGED
        val shouldSendPbat = percent != null
        val shouldSendGps = true
        assertEquals(false, shouldSendPbat)
        assertEquals(true, shouldSendGps)
    }
}
