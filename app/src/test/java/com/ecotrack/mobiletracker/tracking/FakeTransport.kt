package com.ecotrack.mobiletracker.tracking

import java.io.IOException

class FakeTransport : TelemetryTransport {
    var connected: Boolean = false
    var failConnect: Boolean = false
    var failNextSend: Boolean = false
    var connectCount: Int = 0
    val lines: MutableList<String> = mutableListOf()

    override val isConnected: Boolean
        get() = connected

    override fun connect() {
        if (failConnect) throw IOException("connect failed")
        connected = true
        connectCount++
    }

    override fun close() {
        connected = false
    }

    override fun sendPdev(deviceCode: String) {
        failIfNeeded()
        lines += NmeaBuilder.pdev(deviceCode)
    }

    override fun sendLive(sample: GpsSample, batteryPercent: Int?) {
        failIfNeeded()
        if (batteryPercent != null) {
            lines += NmeaBuilder.pbat(batteryPercent)
        }
        lines += rmc(sample)
        lines += NmeaBuilder.gpgga(
            instant = sample.recordedAt,
            lat = sample.latitude,
            lon = sample.longitude,
            altitudeMeters = sample.altitudeM,
        )
    }

    override fun sendHistoricalRmc(sample: GpsSample) {
        failIfNeeded()
        lines += rmc(sample)
    }

    private fun rmc(sample: GpsSample): String {
        return NmeaBuilder.gprmc(
            instant = sample.recordedAt,
            lat = sample.latitude,
            lon = sample.longitude,
            speedKnots = sample.speedKnots,
            courseDeg = sample.bearingDeg,
        )
    }

    private fun failIfNeeded() {
        if (failNextSend) {
            failNextSend = false
            connected = false
            throw IOException("send failed")
        }
        if (!connected) throw IllegalStateException("Not connected")
    }
}
