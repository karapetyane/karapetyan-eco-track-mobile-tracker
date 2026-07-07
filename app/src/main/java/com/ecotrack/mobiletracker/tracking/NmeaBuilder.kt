package com.ecotrack.mobiletracker.tracking

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

object NmeaBuilder {
    private val timeFmt = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC)
    private val dateFmt = DateTimeFormatter.ofPattern("ddMMyy").withZone(ZoneOffset.UTC)

    fun gprmc(
        instant: Instant,
        lat: Double,
        lon: Double,
        speedKnots: Double? = null,
        courseDeg: Double? = null,
    ): String {
        val t = timeFmt.format(instant)
        val d = dateFmt.format(instant)
        val (latField, latHem) = latToNmea(lat)
        val (lonField, lonHem) = lonToNmea(lon)
        val spd = speedKnots?.takeIf { it >= 0 }?.let { String.format(Locale.US, "%.1f", it) } ?: ""
        val crs = courseDeg?.takeIf { it >= 0 }?.let { String.format(Locale.US, "%.1f", it) } ?: ""
        val body = "GPRMC,$t.00,A,$latField,$latHem,$lonField,$lonHem,$spd,$crs,$d,,"
        return withChecksum(body)
    }

    fun gpgga(
        instant: Instant,
        lat: Double,
        lon: Double,
        fixQuality: Int = 1,
        satellites: Int = 8,
        hdop: Double = 1.0,
        altitudeMeters: Double? = null,
    ): String {
        val t = timeFmt.format(instant)
        val (latField, latHem) = latToNmea(lat)
        val (lonField, lonHem) = lonToNmea(lon)
        val alt = altitudeMeters?.let { String.format(Locale.US, "%.1f", it) } ?: "0.0"
        val body = "GPGGA,$t.00,$latField,$latHem,$lonField,$lonHem,$fixQuality,$satellites,${String.format(Locale.US, "%.1f", hdop)},$alt,M,,M,,"
        return withChecksum(body)
    }

    fun pdev(deviceCode: String): String {
        val safe = deviceCode.replace(",", "_").take(64)
        val body = "PDEV,$safe"
        return withChecksum(body)
    }

    private fun withChecksum(body: String): String {
        var cs = 0
        for (c in body) cs = cs xor c.code
        return "\$${body}*${cs.toString(16).uppercase().padStart(2, '0')}"
    }

    private fun latToNmea(lat: Double): Pair<String, String> {
        val hem = if (lat >= 0) "N" else "S"
        val a = abs(lat)
        val deg = a.toInt()
        val min = (a - deg) * 60.0
        return String.format(Locale.US, "%02d%07.4f", deg, min) to hem
    }

    private fun lonToNmea(lon: Double): Pair<String, String> {
        val hem = if (lon >= 0) "E" else "W"
        val a = abs(lon)
        val deg = a.toInt()
        val min = (a - deg) * 60.0
        return String.format(Locale.US, "%03d%07.4f", deg, min) to hem
    }
}

