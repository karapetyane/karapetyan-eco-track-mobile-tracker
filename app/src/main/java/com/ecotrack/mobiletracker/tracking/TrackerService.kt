package com.ecotrack.mobiletracker.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ecotrack.mobiletracker.MainActivity
import com.ecotrack.mobiletracker.data.OfflineGapDatabase
import com.ecotrack.mobiletracker.data.RoomOfflinePointStore
import com.ecotrack.mobiletracker.data.Settings
import com.ecotrack.mobiletracker.data.TrackingStateStore
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class TrackerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var heartbeatStarted = false
    private val heartbeatInFlight = AtomicBoolean(false)
    private val heartbeatIoTimedOut = AtomicBoolean(false)
    private val heartbeatIoTimeoutRunnable = Runnable {
        if (!heartbeatIoTimedOut.compareAndSet(false, true)) return@Runnable
        Log.i(TAG, "PBAT heartbeat failed: timeout")
    }
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!heartbeatStarted) return
            Log.i(TAG, "PBAT heartbeat tick")
            heartbeatHandler.postDelayed(this, PBAT_HEARTBEAT_MS)
            scope.launch {
                if (!heartbeatInFlight.compareAndSet(false, true)) {
                    Log.i(TAG, "PBAT heartbeat skip: previous tick still running")
                    return@launch
                }
                try {
                    sendPbatHeartbeatBestEffort()
                } finally {
                    heartbeatInFlight.set(false)
                }
            }
        }
    }

    private lateinit var stateStore: TrackingStateStore
    private lateinit var pointStore: RoomOfflinePointStore

    private var settings: Settings = Settings(
        telemetryApiKey = Settings.DEFAULT_TELEMETRY_API_KEY,
        deviceCode = Settings.DEFAULT_DEVICE_CODE,
        intervalSeconds = Settings.DEFAULT_INTERVAL_SECONDS,
    )
    private var sessionId: String = ""
    private var coordinator: TrackingCoordinator? = null
    private var transport: TelemetryTransport? = null
    private var locationUpdatesActive = false
    private var lastAcceptedElapsedRealtimeNanos: Long = 0L
    private var lastAcceptedTimeMillis: Long = 0L
    private var trackingWakeLock: PowerManager.WakeLock? = null

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation
            if (loc == null) {
                gpsDiag("GPS: callback, no usable location")
                return
            }
            gpsDiag("GPS: callback with location")
            scope.launch { onFreshLocation(loc) }
        }
    }

    private val sentFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        stateStore = TrackingStateStore(this)
        pointStore = RoomOfflinePointStore(OfflineGapDatabase.get(this).offlinePointDao())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_STOP -> stopSelfSafely()
            intent?.action == ACTION_START && intent.getBooleanExtra(EXTRA_EXPLICIT_START, true) -> {
                beginExplicitSession(intent)
            }
            else -> resumePersistedSessionIfNeeded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        stopPbatHeartbeat()
        stopLocationUpdates()
        transport?.close()
        releaseTrackingWakeLock()
        super.onDestroy()
    }

    private fun beginExplicitSession(intent: Intent) {
        settings = settingsFromIntent(intent)
        sessionId = UUID.randomUUID().toString()
        stateStore.saveActiveSession(sessionId, settings)
        // startForeground MUST run on this main-thread callback before any Room/IO work.
        // Room cannot be touched here: deleteAll() on the main thread crashes the service.
        startForeground(NOTIF_ID, buildNotification("Starting…"))
        startLoop(clearPreviousSessionQueue = true)
    }

    /**
     * Android process death / sticky restart is NOT a new user Start.
     * Keep the current session queue.
     */
    private fun resumePersistedSessionIfNeeded() {
        if (!stateStore.isTracking()) {
            stopSelf()
            return
        }
        settings = stateStore.loadSettings()
        sessionId = stateStore.sessionId() ?: UUID.randomUUID().toString().also {
            stateStore.saveActiveSession(it, settings)
        }
        Log.i(TAG, "Resuming tracking session $sessionId (queue preserved)")
        startForeground(NOTIF_ID, buildNotification("Resuming…"))
        startLoop(clearPreviousSessionQueue = false)
    }

    private fun settingsFromIntent(intent: Intent): Settings {
        return Settings(
            telemetryApiKey = intent.getStringExtra(EXTRA_TELEMETRY_API_KEY) ?: Settings.DEFAULT_TELEMETRY_API_KEY,
            deviceCode = intent.getStringExtra(EXTRA_DEVICE_CODE) ?: Settings.DEFAULT_DEVICE_CODE,
            intervalSeconds = intent.getLongExtra(EXTRA_INTERVAL_SECONDS, Settings.DEFAULT_INTERVAL_SECONDS)
                .coerceAtLeast(1),
        )
    }

    private fun startLoop(clearPreviousSessionQueue: Boolean) {
        job?.cancel()
        stopPbatHeartbeat()
        stopLocationUpdates()
        transport?.close()
        lastAcceptedElapsedRealtimeNanos = 0L
        lastAcceptedTimeMillis = 0L
        val http = HttpTelemetryTransport(
            ingestUrl = Settings.DEFAULT_INGEST_URL,
            deviceCode = settings.deviceCode,
            apiKeyProvider = { settings.telemetryApiKey },
        )
        transport = http
        val coord = TrackingCoordinator(
            sessionId = sessionId,
            deviceCode = settings.deviceCode,
            store = pointStore,
            transport = http,
        )
        coordinator = coord
        acquireTrackingWakeLock()

        job = scope.launch {
            if (clearPreviousSessionQueue) {
                coord.onExplicitNewSession()
                Log.i(TAG, "Explicit Start: new session $sessionId; previous offline queue cleared")
            }
            broadcastStatus("Starting…", null, null, "-", null, coord.pendingCount())
            startLocationUpdates()
        }
        // TCP $PBAT heartbeat retired with HTTPS telemetry migration (no TCP/9100 path).
    }

    private fun acquireTrackingWakeLock() {
        val held = trackingWakeLock
        if (held?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ecotrack:tracking")
        wl.setReferenceCounted(false)
        wl.acquire()
        trackingWakeLock = wl
        Log.i(TAG, "wake lock acquired")
    }

    private fun releaseTrackingWakeLock() {
        val wl = trackingWakeLock ?: return
        trackingWakeLock = null
        if (wl.isHeld) {
            wl.release()
            Log.i(TAG, "wake lock released")
        }
    }

    private fun startLocationUpdates() {
        stopLocationUpdates()
        val intervalMs = settings.intervalSeconds.coerceAtLeast(1) * 1000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setMaxUpdateAgeMillis(intervalMs)
            .build()
        gpsDiag("GPS: registering")
        try {
            @Suppress("MissingPermission")
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                .addOnSuccessListener {
                    gpsDiag("GPS: registered, waiting for fix")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Location updates registration failed: ${e.message}")
                    gpsDiag("GPS registration failed: ${(e.message ?: e.javaClass.simpleName).take(80)}")
                }
            locationUpdatesActive = true
        } catch (_: SecurityException) {
            Log.w(TAG, "Location updates not started: missing permission")
            gpsDiag("GPS permission error")
        }
    }

    private fun startPbatHeartbeat() {
        stopPbatHeartbeat()
        heartbeatStarted = true
        heartbeatHandler.postDelayed(heartbeatRunnable, PBAT_HEARTBEAT_MS)
    }

    private fun stopPbatHeartbeat() {
        heartbeatStarted = false
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.removeCallbacks(heartbeatIoTimeoutRunnable)
        heartbeatInFlight.set(false)
        heartbeatIoTimedOut.set(true)
    }

    private fun sendPbatHeartbeatBestEffort() {
        // Retained for lifecycle safety; HTTPS ingest has no PBAT equivalent on this path.
        Log.i(TAG, "PBAT heartbeat skip: https transport")
    }

    private fun gpsDiag(status: String) {
        broadcastStatus(status, null, null, "-", null, 0)
    }

    private fun stopLocationUpdates() {
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {
        }
        locationUpdatesActive = false
    }

    private fun onFreshLocation(location: Location) {
        if (!isNewPhysicalFix(location)) return
        rememberAcceptedFix(location)
        val coord = coordinator ?: return
        val sample = toSample(location)
        val battery = BatteryReader.readPercent(this)
        val result = coord.onTick(sample, battery)
        applyTickResult(sample, battery, result)
    }

    private fun isNewPhysicalFix(location: Location): Boolean {
        val elapsed = location.elapsedRealtimeNanos
        if (elapsed > 0L && lastAcceptedElapsedRealtimeNanos > 0L &&
            elapsed == lastAcceptedElapsedRealtimeNanos
        ) {
            return false
        }
        if (elapsed <= 0L) {
            val time = location.time
            if (time > 0L && lastAcceptedTimeMillis > 0L && time == lastAcceptedTimeMillis) {
                return false
            }
        }
        return true
    }

    private fun rememberAcceptedFix(location: Location) {
        if (location.elapsedRealtimeNanos > 0L) {
            lastAcceptedElapsedRealtimeNanos = location.elapsedRealtimeNanos
        }
        if (location.time > 0L) {
            lastAcceptedTimeMillis = location.time
        }
    }

    private fun applyTickResult(sample: GpsSample, battery: Int?, result: TickResult) {
        if (result.droppedOldest > 0) {
            Log.w(
                TAG,
                "Offline queue at cap ${QueueBounds.MAX_PENDING_POINTS}; dropped ${result.droppedOldest} oldest point(s)",
            )
        }
        val pending = result.pendingCount
        when {
            result.usedFastPath -> {
                val sentAt = sentFmt.format(sample.recordedAt)
                broadcastStatus("Connected (sending)", sample.latitude, sample.longitude, sentAt, battery, pending)
                updateNotification("Sending: ${sample.latitude}, ${sample.longitude}")
            }
            result.replayedCount > 0 -> {
                broadcastStatus(
                    "Replaying offline gap ($pending left)",
                    sample.latitude,
                    sample.longitude,
                    sentFmt.format(sample.recordedAt),
                    battery,
                    pending,
                )
                updateNotification("Replaying gap • $pending pending")
            }
            else -> {
                broadcastStatus(
                    "Offline (queued)",
                    sample.latitude,
                    sample.longitude,
                    "-",
                    battery,
                    pending,
                )
                updateNotification("Offline — queued $pending")
            }
        }
    }

    private fun toSample(location: Location): GpsSample {
        val sampleClock = Instant.now().toEpochMilli()
        val recorded = LocationTimestamps.recordedAtMillis(location.time, sampleClock)
        return GpsSample(
            recordedAt = Instant.ofEpochMilli(recorded),
            latitude = location.latitude,
            longitude = location.longitude,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
            altitudeM = if (location.hasAltitude()) location.altitude else null,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            messageId = UUID.randomUUID().toString(),
        )
    }

    private fun stopSelfSafely() {
        job?.cancel()
        stopPbatHeartbeat()
        stopLocationUpdates()
        transport?.close()
        transport = null
        coordinator = null
        stateStore.markStopped()
        releaseTrackingWakeLock()
        broadcastStatus("Stopped", null, null, "-", null, 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(content: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Eco-Track Tracker")
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "Tracking", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun broadcastStatus(
        status: String,
        lat: Double?,
        lon: Double?,
        lastSent: String,
        batteryPercent: Int?,
        pendingCount: Int,
    ) {
        val i = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            if (lat != null) putExtra(EXTRA_LAT, lat)
            if (lon != null) putExtra(EXTRA_LON, lon)
            putExtra(EXTRA_LAST_SENT, lastSent)
            if (batteryPercent != null) putExtra(EXTRA_BATTERY_PERCENT, batteryPercent)
            putExtra(EXTRA_PENDING_COUNT, pendingCount)
        }
        sendBroadcast(i)
    }

    companion object {
        private const val TAG = "EcoTrackTracker"
        private const val CHANNEL_ID = "eco_track_tracking"
        private const val NOTIF_ID = 10001
        private const val PBAT_HEARTBEAT_MS = 30_000L
        private const val PBAT_HEARTBEAT_IO_TIMEOUT_MS = 8_000L

        const val ACTION_START = "com.ecotrack.mobiletracker.action.START"
        const val ACTION_STOP = "com.ecotrack.mobiletracker.action.STOP"
        const val ACTION_STATUS = "com.ecotrack.mobiletracker.action.STATUS"

        const val EXTRA_TELEMETRY_API_KEY = "telemetryApiKey"
        const val EXTRA_DEVICE_CODE = "deviceCode"
        const val EXTRA_INTERVAL_SECONDS = "intervalSeconds"
        const val EXTRA_EXPLICIT_START = "explicitStart"

        const val EXTRA_STATUS = "status"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_LAST_SENT = "lastSent"
        const val EXTRA_BATTERY_PERCENT = "batteryPercent"
        const val EXTRA_PENDING_COUNT = "pendingCount"

        fun start(context: Context, settings: Settings) {
            val i = Intent(context, TrackerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_EXPLICIT_START, true)
                putExtra(EXTRA_TELEMETRY_API_KEY, settings.telemetryApiKey)
                putExtra(EXTRA_DEVICE_CODE, settings.deviceCode)
                putExtra(EXTRA_INTERVAL_SECONDS, settings.intervalSeconds)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, TrackerService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }
}
