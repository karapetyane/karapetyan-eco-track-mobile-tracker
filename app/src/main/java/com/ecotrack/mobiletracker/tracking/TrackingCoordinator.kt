package com.ecotrack.mobiletracker.tracking

data class TickResult(
    val pendingCount: Int,
    val usedFastPath: Boolean,
    val persistedSample: Boolean,
    val replayedCount: Int,
    val droppedOldest: Int,
    val connected: Boolean,
    val liveSent: Boolean,
)

/**
 * Gap-recovery coordinator: live TCP fast path when online with empty queue;
 * persist only when disconnected, send fails, or a backlog is draining.
 */
class TrackingCoordinator(
    private val sessionId: String,
    private val deviceCode: String,
    private val store: OfflinePointStore,
    private val transport: TelemetryTransport,
    private val maxQueueSize: Int = QueueBounds.MAX_PENDING_POINTS,
    private val replayBatchSize: Int = DEFAULT_REPLAY_BATCH,
) {
    fun pendingCount(): Int = store.count(sessionId)

    fun onExplicitNewSession() {
        store.deleteAll()
    }

    fun onTick(sample: GpsSample?, batteryPercent: Int? = null): TickResult {
        if (sample == null) {
            tryConnectAndReplay()
            return TickResult(
                pendingCount = store.count(sessionId),
                usedFastPath = false,
                persistedSample = false,
                replayedCount = 0,
                droppedOldest = 0,
                connected = transport.isConnected,
                liveSent = false,
            )
        }

        val backlog = store.count(sessionId)
        if (transport.isConnected && backlog == 0) {
            return tryLiveFastPath(sample, batteryPercent)
        }

        store.insert(QueuedPoint.fromSample(sessionId, sample))
        val dropped = QueueBounds.trimOldest(store, sessionId, maxQueueSize)
        val replayed = tryConnectAndReplay()
        return TickResult(
            pendingCount = store.count(sessionId),
            usedFastPath = false,
            persistedSample = true,
            replayedCount = replayed,
            droppedOldest = dropped,
            connected = transport.isConnected,
            liveSent = false,
        )
    }

    private fun tryLiveFastPath(sample: GpsSample, batteryPercent: Int?): TickResult {
        return try {
            transport.sendLive(sample, batteryPercent)
            TickResult(
                pendingCount = 0,
                usedFastPath = true,
                persistedSample = false,
                replayedCount = 0,
                droppedOldest = 0,
                connected = true,
                liveSent = true,
            )
        } catch (_: Exception) {
            transport.close()
            store.insert(QueuedPoint.fromSample(sessionId, sample))
            val dropped = QueueBounds.trimOldest(store, sessionId, maxQueueSize)
            TickResult(
                pendingCount = store.count(sessionId),
                usedFastPath = false,
                persistedSample = true,
                replayedCount = 0,
                droppedOldest = dropped,
                connected = false,
                liveSent = false,
            )
        }
    }

    /**
     * @return number of historical RMC sentences successfully flushed and deleted
     */
    fun tryConnectAndReplay(): Int {
        if (!transport.isConnected) {
            try {
                transport.connect()
                transport.sendPdev(deviceCode)
            } catch (_: Exception) {
                transport.close()
                return 0
            }
        }
        return drainBatch()
    }

    private fun drainBatch(): Int {
        var sent = 0
        val batch = store.pending(sessionId, replayBatchSize)
        for (point in batch) {
            try {
                transport.sendHistoricalRmc(point.toSample())
                store.deleteById(point.id)
                sent++
            } catch (_: Exception) {
                transport.close()
                break
            }
        }
        return sent
    }

    companion object {
        const val DEFAULT_REPLAY_BATCH = 40
    }
}
