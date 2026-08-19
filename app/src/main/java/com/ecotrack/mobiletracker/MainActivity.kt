package com.ecotrack.mobiletracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ecotrack.mobiletracker.data.Settings
import com.ecotrack.mobiletracker.data.SettingsStore
import com.ecotrack.mobiletracker.tracking.TrackerService

class MainActivity : AppCompatActivity() {

    private lateinit var settingsStore: SettingsStore

    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var deviceCodeEdit: EditText
    private lateinit var intervalEdit: EditText

    private lateinit var statusText: TextView
    private lateinit var lastLatText: TextView
    private lateinit var lastLonText: TextView
    private lateinit var lastSentText: TextView
    private lateinit var batteryText: TextView
    private lateinit var pendingText: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TrackerService.ACTION_STATUS) return
            val status = intent.getStringExtra(TrackerService.EXTRA_STATUS) ?: "Unknown"
            val lat = intent.getDoubleExtra(TrackerService.EXTRA_LAT, Double.NaN)
            val lon = intent.getDoubleExtra(TrackerService.EXTRA_LON, Double.NaN)
            val lastSent = intent.getStringExtra(TrackerService.EXTRA_LAST_SENT) ?: "-"

            statusText.text = status
            lastLatText.text = "Last latitude: " + (if (lat.isNaN()) "-" else lat.toString())
            lastLonText.text = "Last longitude: " + (if (lon.isNaN()) "-" else lon.toString())
            lastSentText.text = "Last sent: $lastSent"

            if (intent.hasExtra(TrackerService.EXTRA_BATTERY_PERCENT)) {
                val pct = intent.getIntExtra(TrackerService.EXTRA_BATTERY_PERCENT, -1)
                batteryText.text = if (pct in 0..100) "Մարտկոց: $pct%" else "Մարտկոց: -"
            }
            val pending = intent.getIntExtra(TrackerService.EXTRA_PENDING_COUNT, 0)
            pendingText.text = "Անցանց կետեր: $pending"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsStore = SettingsStore(this)

        hostEdit = findViewById(R.id.hostEdit)
        portEdit = findViewById(R.id.portEdit)
        deviceCodeEdit = findViewById(R.id.deviceCodeEdit)
        intervalEdit = findViewById(R.id.intervalEdit)

        statusText = findViewById(R.id.statusText)
        lastLatText = findViewById(R.id.lastLatText)
        lastLonText = findViewById(R.id.lastLonText)
        lastSentText = findViewById(R.id.lastSentText)
        batteryText = findViewById(R.id.batteryText)
        pendingText = findViewById(R.id.pendingText)

        val settings = settingsStore.load()
        hostEdit.setText(settings.host)
        portEdit.setText(settings.port.toString())
        deviceCodeEdit.setText(settings.deviceCode)
        intervalEdit.setText(settings.intervalSeconds.toString())

        findViewById<Button>(R.id.startButton).setOnClickListener {
            requestPermissionsIfNeeded()
            val newSettings = readSettingsFromUi()
            settingsStore.save(newSettings)
            TrackerService.start(this, newSettings)
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            TrackerService.stop(this)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TrackerService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    private fun readSettingsFromUi(): Settings {
        val host = hostEdit.text?.toString()?.trim().orEmpty().ifEmpty { Settings.DEFAULT_HOST }
        val port = portEdit.text?.toString()?.toIntOrNull() ?: Settings.DEFAULT_PORT
        val deviceCode = deviceCodeEdit.text?.toString()?.trim().orEmpty().ifEmpty { Settings.DEFAULT_DEVICE_CODE }
        val intervalSeconds = intervalEdit.text?.toString()?.toLongOrNull()?.coerceAtLeast(1) ?: Settings.DEFAULT_INTERVAL_SECONDS
        return Settings(host = host, port = port, deviceCode = deviceCode, intervalSeconds = intervalSeconds)
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            // Ask background later if user granted fine location; for v1 we request directly to keep it simple.
            needed += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }
}

