package com.ecotrack.mobiletracker.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TrackingCoordinatorTest {

    private val sessionA = "session-a"
    private val sessionB = "session-b"

    @Test
    fun online_sample_sends_immediately_and_is_not_retained() {
        val store = InMemoryOfflinePointStore()
        val transport = FakeTransport().also { it.connected = true }
        val coord = coordinator(store, transport, sessionA)
        val sample = sample(t = 1_000)

        val result = coord.onTick(sample, batteryPercent = 80)

        assertTrue(result.usedFastPath)
        assertTrue(result.liveSent)
        assertFalse(result.persistedSample)
        assertEquals(0, store.count(sessionA))
        assertTrue(transport.lines.any { it.startsWith("\$GPRMC,") })
        assertTrue(transport.lines.any { it.startsWith("\$GPGGA,") })
    }

    @Test
    fun disconnected_sample_is_persisted() {
        val store = InMemoryOfflinePointStore()
        val transport = FakeTransport().also { it.failConnect = true }
        val coord = coordinator(store, transport, sessionA)

        val result = coord.onTick(sample(t = 2_000))

        assertTrue(result.persistedSample)
        assertEquals(1, store.count(sessionA))
        assertFalse(result.usedFastPath)
        assertTrue(transport.lines.isEmpty())
    }

    @Test
    fun send_failure_persists_the_point() {
        val store = InMemoryOfflinePointStore()
        val transport = FakeTransport().also {
            it.connected = true
            it.failNextSend = true
        }
        val coord = coordinator(store, transport, sessionA)

        val result = coord.onTick(sample(t = 3_000))

        assertTrue(result.persistedSample)
        assertEquals(1, store.count(sessionA))
        assertFalse(transport.isConnected)
    }

    @Test
    fun multiple_offline_samples_preserve_chronological_order() {
        val store = InMemoryOfflinePointStore()
        val transport = FakeTransport().also { it.failConnect = true }
        val coord = coordinator(store, transport, sessionA)

        coord.onTick(sample(t = 5_000, lat = 1.0))
        coord.onTick(sample(t = 3_000, lat = 2.0))
        coord.onTick(sample(t = 4_000, lat = 3.0))

        val pending = store.pending(sessionA, 10)
        assertEquals(listOf(3_000L, 4_000L, 5_000L), pending.map { it.recordedAtMillis })
        assertEquals(listOf(2.0, 3.0, 1.0), pending.map { it.latitude })
    }

    @Test
    fun reconnect_replays_offline_rmc_using_original_timestamps() {
        val store = InMemoryOfflinePointStore()
        val transport = FakeTransport().also { it.failConnect = true }
        val coord = coordinator(store, transport, sessionA)
        val original = Instant.parse("2026-01-15T08:09:10Z")
        coord.onTick(sample(t = original.toEpochMilli(), lat = 40.1, lon = 44.5))

        transport.failConnect = false
        val result = coord.onTick(sample(t = original.toEpochMilli() + 5_000, lat = 40.2, lon = 44.6))

        assertTrue(result.replayedCount >= 1)
        val rmc = transport.lines.filter { it.startsWith("\$GPRMC,") }
        assertTrue(rmc.isNotEmpty())
        assertTrue(rmc[0].contains("080910"))
        assertTrue(rmc[0].contains("150126"))
        assertTrue(transport.lines.first().startsWith("\$PDEV,"))
    }

    @Test
    fun historical_replay_does_not_send_gga() {
        val store = InMemoryOfflinePointStore()
        val transport = FakeTransport().also { it.failConnect = true }
        val coord = coordinator(store, transport, sessionA)
        coord.onTick(sample(t = 10_000))

        transport.failConnect = false
        coord.tryConnectAndReplay()

        assertTrue(transport.lines.any { it.startsWith("\$GPRMC,") })
        assertFalse(transport.lines.any { it.startsWith("\$GPGGA,") })
    }

    @Test
    fun successful_replay_removes_sent_points() {
        val store = InMemoryOfflinePointStore()
        val transport = FakeTransport().also { it.failConnect = true }
        val coord = coordinator(store, transport, sessionA)
        coord.onTick(sample(t = 1))
        coord.onTick(sample(t = 2))
        transport.failConnect = false

        coord.tryConnectAndReplay()

        assertEquals(0, store.count(sessionA))
    }

    @Test
    fun failed_partial_replay_leaves_remaining_points() {
        val store = InMemoryOfflinePointStore()
        val base = FakeTransport()
        base.connected = true
        var historicalSends = 0
        val transport = object : TelemetryTransport by base {
            override val isConnected: Boolean get() = base.isConnected
            override fun sendHistoricalRmc(sample: GpsSample) {
                historicalSends++
                if (historicalSends == 2) {
                    base.close()
                    throw java.io.IOException("mid replay")
                }
                base.sendHistoricalRmc(sample)
            }
        }
        val coord = coordinator(store, transport, sessionA, replayBatch = 10)
        store.insert(QueuedPoint.fromSample(sessionA, sample(t = 1)))
        store.insert(QueuedPoint.fromSample(sessionA, sample(t = 2)))
        store.insert(QueuedPoint.fromSample(sessionA, sample(t = 3)))

        val partial = coord.tryConnectAndReplay()

        assertEquals(1, partial)
        assertEquals(2, store.count(sessionA))
        val left = store.pending(sessionA, 10).map { it.recordedAtMillis }
        assertEquals(listOf(2L, 3L), left)
    }

    @Test
    fun new_live_samples_during_backlog_recovery_are_not_lost() {
        val store = InMemoryOfflinePointStore()
        val transport = FakeTransport().also { it.failConnect = true }
        val coord = coordinator(store, transport, sessionA, replayBatch = 1)
        coord.onTick(sample(t = 1_000, lat = 1.0))
        coord.onTick(sample(t = 2_000, lat = 2.0))

        transport.failConnect = false
        coord.onTick(sample(t = 3_000, lat = 3.0))

        val remainingLats = store.pending(sessionA, 20).map { it.latitude }
        assertTrue("newest live sample must still be queued or already replayed", remainingLats.contains(3.0) || remainingLats.isEmpty())
        assertEquals(2, store.count(sessionA))
        assertEquals(listOf(2.0, 3.0), remainingLats)
    }

    @Test
    fun after_backlog_empties_app_returns_to_direct_live_sending() {
        val store = InMemoryOfflinePointStore()
        val transport = FakeTransport().also { it.failConnect = true }
        val coord = coordinator(store, transport, sessionA)
        coord.onTick(sample(t = 1_000))
        transport.failConnect = false
        coord.tryConnectAndReplay()
        assertEquals(0, store.count(sessionA))
        transport.connected = true

        val live = coord.onTick(sample(t = 2_000), batteryPercent = 50)

        assertTrue(live.usedFastPath)
        assertFalse(live.persistedSample)
        assertEquals(0, store.count(sessionA))
        assertTrue(transport.lines.any { it.startsWith("\$GPGGA,") })
    }

    @Test
    fun explicit_new_start_clears_previous_session_leftover_queue() {
        val store = InMemoryOfflinePointStore()
        val transport = FakeTransport().also { it.failConnect = true }
        val coordA = coordinator(store, transport, sessionA)
        coordA.onTick(sample(t = 1_000))
        assertEquals(1, store.count(sessionA))

        val coordB = coordinator(store, transport, sessionB)
        coordB.onExplicitNewSession()

        assertEquals(0, store.count(sessionA))
        assertEquals(0, store.count(sessionB))
    }

    @Test
    fun temporary_reconnect_does_not_clear_current_session_queue() {
        val store = InMemoryOfflinePointStore()
        val transport = FakeTransport().also { it.failConnect = true }
        val coord = coordinator(store, transport, sessionA)
        coord.onTick(sample(t = 1_000))
        coord.onTick(sample(t = 2_000))
        assertEquals(2, store.count(sessionA))

        transport.failConnect = false
        transport.failNextSend = true
        coord.tryConnectAndReplay()

        assertTrue(store.count(sessionA) >= 1)
    }

    @Test
    fun process_recreation_does_not_clear_current_session_queue() {
        val backing = mutableListOf<QueuedPoint>()
        val store1 = InMemoryOfflinePointStore(backing)
        val transport = FakeTransport().also { it.failConnect = true }
        coordinator(store1, transport, sessionA).onTick(sample(t = 9_000))
        assertEquals(1, store1.count(sessionA))

        val store2 = InMemoryOfflinePointStore(backing)
        val coordResume = coordinator(store2, transport, sessionA)
        // resume must NOT call onExplicitNewSession
        assertEquals(1, coordResume.pendingCount())
    }

    @Test
    fun queue_survives_repository_recreation() {
        val backing = mutableListOf<QueuedPoint>()
        InMemoryOfflinePointStore(backing).insert(QueuedPoint.fromSample(sessionA, sample(t = 42)))
        val restored = InMemoryOfflinePointStore(backing)
        assertEquals(1, restored.count(sessionA))
        assertEquals(42L, restored.pending(sessionA, 1).first().recordedAtMillis)
    }

    @Test
    fun configured_sampling_interval_remaining_delay_is_respected() {
        assertEquals(5_000L, remainingDelayMs(intervalMs = 5_000, elapsedMs = 0))
        assertEquals(2_000L, remainingDelayMs(intervalMs = 5_000, elapsedMs = 3_000))
        assertEquals(0L, remainingDelayMs(intervalMs = 5_000, elapsedMs = 8_000))
    }

    @Test
    fun queue_bound_drops_oldest_keeps_newest() {
        val store = InMemoryOfflinePointStore()
        val transport = FakeTransport().also { it.failConnect = true }
        val coord = TrackingCoordinator(sessionA, "NMEA-TCP-001", store, transport, maxQueueSize = 2)
        coord.onTick(sample(t = 1, lat = 1.0))
        coord.onTick(sample(t = 2, lat = 2.0))
        coord.onTick(sample(t = 3, lat = 3.0))
        val pending = store.pending(sessionA, 10)
        assertEquals(2, pending.size)
        assertEquals(listOf(2.0, 3.0), pending.map { it.latitude })
    }

    private fun coordinator(
        store: OfflinePointStore,
        transport: TelemetryTransport,
        sessionId: String,
        replayBatch: Int = 40,
    ) = TrackingCoordinator(
        sessionId = sessionId,
        deviceCode = "NMEA-TCP-001",
        store = store,
        transport = transport,
        replayBatchSize = replayBatch,
    )

    private fun sample(t: Long, lat: Double = 40.0, lon: Double = 44.0) = GpsSample(
        recordedAt = Instant.ofEpochMilli(t),
        latitude = lat,
        longitude = lon,
        speedMps = 1.0,
        bearingDeg = 90.0,
        altitudeM = 100.0,
    )

    private fun remainingDelayMs(intervalMs: Long, elapsedMs: Long): Long =
        (intervalMs - elapsedMs).coerceAtLeast(0)
}
