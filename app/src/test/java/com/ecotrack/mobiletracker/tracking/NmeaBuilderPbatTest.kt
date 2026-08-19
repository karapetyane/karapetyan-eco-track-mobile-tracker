package com.ecotrack.mobiletracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NmeaBuilderPbatTest {

    @Test
    fun pbat_0() {
        assertEquals(expected("PBAT,0"), NmeaBuilder.pbat(0))
    }

    @Test
    fun pbat_1() {
        assertEquals(expected("PBAT,1"), NmeaBuilder.pbat(1))
    }

    @Test
    fun pbat_67() {
        assertEquals(expected("PBAT,67"), NmeaBuilder.pbat(67))
    }

    @Test
    fun pbat_99() {
        assertEquals(expected("PBAT,99"), NmeaBuilder.pbat(99))
    }

    @Test
    fun pbat_100() {
        assertEquals(expected("PBAT,100"), NmeaBuilder.pbat(100))
    }

    @Test
    fun checksum_correctness_for_67() {
        val sentence = NmeaBuilder.pbat(67)
        assertTrue(sentence.startsWith("\$PBAT,67*"))
        val body = sentence.substring(1, sentence.indexOf('*'))
        val cs = sentence.substring(sentence.indexOf('*') + 1)
        assertEquals(xorChecksum(body), cs)
    }

    @Test
    fun sentence_has_no_crlf_builder_only() {
        val sentence = NmeaBuilder.pbat(67)
        assertFalse(sentence.contains("\r"))
        assertFalse(sentence.contains("\n"))
    }

    @Test
    fun wire_line_ending_is_crlf_via_tcp_client_contract() {
        // TcpClient.sendLine appends \r\n after the NMEA sentence.
        val sentence = NmeaBuilder.pbat(67)
        val wire = sentence + "\r\n"
        assertTrue(wire.endsWith("\r\n"))
        assertEquals(1, wire.count { it == '\r' })
        assertEquals(1, wire.count { it == '\n' })
        assertTrue(wire.startsWith("\$PBAT,67*"))
    }

    @Test
    fun invalid_percent_below_zero_throws() {
        try {
            NmeaBuilder.pbat(-1)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected — TrackerService must skip PBAT and continue GPS
        }
    }

    @Test
    fun invalid_percent_above_100_throws() {
        try {
            NmeaBuilder.pbat(101)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun gps_sentences_still_build_when_battery_unavailable() {
        // Simulates: skip PBAT, still produce RMC/GGA
        val batteryPercent: Int? = null
        val lines = mutableListOf<String>()
        if (batteryPercent != null) {
            lines += NmeaBuilder.pbat(batteryPercent)
        }
        val now = java.time.Instant.parse("2026-08-02T12:00:00Z")
        lines += NmeaBuilder.gprmc(now, 40.0, 44.0)
        lines += NmeaBuilder.gpgga(now, 40.0, 44.0)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("\$GPRMC,"))
        assertTrue(lines[1].startsWith("\$GPGGA,"))
    }

    private fun expected(body: String): String = "\$${body}*${xorChecksum(body)}"

    private fun xorChecksum(body: String): String {
        var cs = 0
        for (c in body) cs = cs xor c.code
        return cs.toString(16).uppercase().padStart(2, '0')
    }
}
