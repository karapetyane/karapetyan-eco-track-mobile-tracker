package com.ecotrack.mobiletracker.tracking

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * HTTPS GPS telemetry upload to the Eco-Track ingest API.
 * Success = HTTP 2xx. Does not log the API key.
 */
class HttpTelemetryTransport(
    private val ingestUrl: String,
    private val deviceCode: String,
    private val apiKeyProvider: () -> String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : TelemetryTransport {
    @Volatile
    private var ready: Boolean = true

    override val isConnected: Boolean
        get() = ready

    override fun connect() {
        ready = true
    }

    override fun close() {
        ready = false
    }

    override fun sendPdev(deviceCode: String) {
        // Device identity is included on every HTTPS payload.
    }

    override fun sendLive(sample: GpsSample, batteryPercent: Int?) {
        postSample(sample)
    }

    override fun sendHistoricalRmc(sample: GpsSample) {
        postSample(sample)
    }

    private fun postSample(sample: GpsSample) {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            throw IOException("telemetry API key not configured")
        }
        val body = buildPayload(sample).toString()
        val url = URL(ingestUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty(API_KEY_HEADER, apiKey)
        }
        try {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = readBody(conn, error = true)
                throw IOException("telemetry HTTP $code${if (err.isNotBlank()) ": $err" else ""}")
            }
            // Drain success body to free the connection; ignore content.
            readBody(conn, error = false)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildPayload(sample: GpsSample): JSONObject {
        return JSONObject().apply {
            put("deviceCode", deviceCode)
            put("recordedAt", RECORDED_AT_FMT.format(sample.recordedAt))
            put("latitude", sample.latitude)
            put("longitude", sample.longitude)
            putNullable("speedKmh", sample.speedKmh)
            putNullable("headingDeg", sample.bearingDeg)
            putNullable("altitudeM", sample.altitudeM)
            putNullable("accuracyM", sample.accuracyM)
            put("messageId", sample.messageId)
        }
    }

    private fun JSONObject.putNullable(key: String, value: Double?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun readBody(conn: HttpURLConnection, error: Boolean): String {
        val stream = try {
            if (error) conn.errorStream else conn.inputStream
        } catch (_: Exception) {
            null
        } ?: return ""
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            .take(500)
    }

    companion object {
        const val API_KEY_HEADER = "x-gps-telemetry-api-key"
        private val RECORDED_AT_FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)
    }
}
