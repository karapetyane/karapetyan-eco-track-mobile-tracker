package com.ecotrack.mobiletracker.tracking

class TcpNmeaTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 3_000,
) : TelemetryTransport {
    private var client: TcpClient? = null

    override val isConnected: Boolean
        get() = client?.isConnected() == true

    override fun connect() {
        close()
        val c = TcpClient(host, port)
        c.connect(connectTimeoutMs)
        client = c
    }

    override fun close() {
        client?.close()
        client = null
    }

    override fun sendPdev(deviceCode: String) {
        clientOrThrow().sendLine(NmeaBuilder.pdev(deviceCode))
    }

    override fun sendLive(sample: GpsSample, batteryPercent: Int?) {
        val tcp = clientOrThrow()
        if (batteryPercent != null) {
            tcp.sendLine(NmeaBuilder.pbat(batteryPercent))
        }
        tcp.sendLine(rmc(sample))
        tcp.sendLine(
            NmeaBuilder.gpgga(
                instant = sample.recordedAt,
                lat = sample.latitude,
                lon = sample.longitude,
                altitudeMeters = sample.altitudeM,
            ),
        )
    }

    override fun sendHistoricalRmc(sample: GpsSample) {
        clientOrThrow().sendLine(rmc(sample))
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

    private fun clientOrThrow(): TcpClient {
        val c = client
        if (c == null || !c.isConnected()) throw IllegalStateException("Not connected")
        return c
    }
}
