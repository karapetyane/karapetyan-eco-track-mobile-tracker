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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ecotrack.mobiletracker.MainActivity
import com.ecotrack.mobiletracker.data.Settings
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.CurrentLocationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

class TrackerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    private var settings: Settings = Settings(
        host = Settings.DEFAULT_HOST,
        port = Settings.DEFAULT_PORT,
        deviceCode = Settings.DEFAULT_DEVICE_CODE,
        intervalSeconds = Settings.DEFAULT_INTERVAL_SECONDS,
    )

    private var tcp: TcpClient? = null
    private var lastLocation: Location? = null

    private val sentFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                settings = Settings(
                    host = intent.getStringExtra(EXTRA_HOST) ?: Settings.DEFAULT_HOST,
                    port = intent.getIntExtra(EXTRA_PORT, Settings.DEFAULT_PORT),
                    deviceCode = intent.getStringExtra(EXTRA_DEVICE_CODE) ?: Settings.DEFAULT_DEVICE_CODE,
                    intervalSeconds = intent.getLongExtra(EXTRA_INTERVAL_SECONDS, Settings.DEFAULT_INTERVAL_SECONDS).coerceAtLeast(1),
                )
                startForeground(NOTIF_ID, buildNotification("Starting…"))
                startLoop()
            }
            ACTION_STOP -> stopSelfSafely()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        tcp?.close()
        super.onDestroy()
    }

    private fun startLoop() {
        job?.cancel()
        job = scope.launch {
            broadcastStatus("Connecting…", null, null, "-")
            var backoffMs = 1000L

            while (isActive) {
                try {
                    if (tcp == null || tcp?.isConnected() != true) {
                        tcp?.close()
                        val client = TcpClient(settings.host, settings.port)
                        client.connect()
                        // PDEV: send $PDEV,<deviceCode>*CS once immediately after TCP connect
                        client.sendLine(NmeaBuilder.pdev(settings.deviceCode))
                        tcp = client
                        backoffMs = 1000L
                        broadcastStatus("Connected", null, null, "-")
                        updateNotification("Connected")
                    }

                    val loc = getCurrentLocationBestEffort()
                    if (loc != null) lastLocation = loc

                    val useLoc = lastLocation
                    if (useLoc != null) {
                        val now = Instant.now()
                        val rmc = NmeaBuilder.gprmc(
                            instant = now,
                            lat = useLoc.latitude,
                            lon = useLoc.longitude,
                            speedKnots = if (useLoc.hasSpeed()) (useLoc.speed * 1.943844) else null,
                            courseDeg = if (useLoc.hasBearing()) useLoc.bearing.toDouble() else null,
                        )
                        val gga = NmeaBuilder.gpgga(
                            instant = now,
                            lat = useLoc.latitude,
                            lon = useLoc.longitude,
                            altitudeMeters = if (useLoc.hasAltitude()) useLoc.altitude else null,
                        )
                        tcp?.sendLine(rmc)
                        tcp?.sendLine(gga)

                        val sentAt = sentFmt.format(now)
                        broadcastStatus("Connected (sending)", useLoc.latitude, useLoc.longitude, sentAt)
                        updateNotification("Sending: ${useLoc.latitude}, ${useLoc.longitude}")
                    } else {
                        broadcastStatus("Connected (waiting for GPS)", null, null, "-")
                        updateNotification("Waiting for GPS…")
                    }

                    delay(settings.intervalSeconds * 1000L)
                } catch (e: Exception) {
                    tcp?.close()
                    tcp = null
                    val msg = (e.message ?: e.javaClass.simpleName).take(160)
                    broadcastStatus("Disconnected: $msg", null, null, "-")
                    updateNotification("Disconnected")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private suspend fun getCurrentLocationBestEffort(): Location? {
        val client = LocationServices.getFusedLocationProviderClient(this)
        return try {
            val req = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setDurationMillis(7000)
                .build()
            @Suppress("MissingPermission")
            val task = client.getCurrentLocation(req, null)
            suspendCancellableCoroutine { cont ->
                task.addOnSuccessListener { cont.resume(it) }
                task.addOnFailureListener { cont.resume(null) }
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun stopSelfSafely() {
        job?.cancel()
        tcp?.close()
        tcp = null
        broadcastStatus("Stopped", null, null, "-")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(content: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
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

    private fun broadcastStatus(status: String, lat: Double?, lon: Double?, lastSent: String) {
        val i = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            if (lat != null) putExtra(EXTRA_LAT, lat)
            if (lon != null) putExtra(EXTRA_LON, lon)
            putExtra(EXTRA_LAST_SENT, lastSent)
        }
        sendBroadcast(i)
    }

    companion object {
        private const val CHANNEL_ID = "eco_track_tracking"
        private const val NOTIF_ID = 10001

        const val ACTION_START = "com.ecotrack.mobiletracker.action.START"
        const val ACTION_STOP = "com.ecotrack.mobiletracker.action.STOP"
        const val ACTION_STATUS = "com.ecotrack.mobiletracker.action.STATUS"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE_CODE = "deviceCode"
        const val EXTRA_INTERVAL_SECONDS = "intervalSeconds"

        const val EXTRA_STATUS = "status"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_LAST_SENT = "lastSent"

        fun start(context: Context, settings: Settings) {
            val i = Intent(context, TrackerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, settings.host)
                putExtra(EXTRA_PORT, settings.port)
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

