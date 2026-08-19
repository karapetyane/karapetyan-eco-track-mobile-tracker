package com.ecotrack.mobiletracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class LocationTimestampsTest {
    @Test
    fun prefers_positive_location_time() {
        assertEquals(1_700_000_000_000L, LocationTimestamps.recordedAtMillis(1_700_000_000_000L, 99L))
    }

    @Test
    fun falls_back_when_location_time_invalid() {
        assertEquals(50L, LocationTimestamps.recordedAtMillis(0L, 50L))
        assertEquals(50L, LocationTimestamps.recordedAtMillis(-1L, 50L))
    }
}

class HistoricalRmcTimestampTest {
    @Test
    fun rmc_uses_stored_recorded_at_not_send_time() {
        val recorded = Instant.parse("2026-08-19T15:16:17Z")
        val rmc = NmeaBuilder.gprmc(recorded, 40.0, 44.0)
        assertEquals(true, rmc.contains("151617"))
        assertEquals(true, rmc.contains("190826"))
    }
}
