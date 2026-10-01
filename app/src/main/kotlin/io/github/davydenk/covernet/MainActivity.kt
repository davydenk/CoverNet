package io.github.davydenk.covernet

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logToggle: Button
    private lateinit var logHeader: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        status = TextView(this).apply { textSize = 16f }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "1. Allow location (needed for Wi-Fi name)"
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1) }
        })
        root.addView(Button(this).apply {
            text = "2. Allow location \"all the time\" (widget updates in background)"
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2) }
        })
        root.addView(Button(this).apply {
            text = "3. Battery → Unrestricted (else Android throttles refreshes to a few per hour)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "4. Allow exact alarms (refresh on the minute)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "Wi-Fi access points nearby (scan)"
            setOnClickListener { startActivity(Intent(this@MainActivity, ScanActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "Refresh widgets now"
            setOnClickListener { NetWidget.refreshAll(this@MainActivity); refresh() }
        })
        root.addView(Button(this).apply {
            text = "Run diagnostics (host scan)"
            setOnClickListener { startActivity(Intent(this@MainActivity, DiagActivity::class.java)) }
        })
        logToggle = Button(this).apply {
            setOnClickListener {
                EventLog.setEnabled(this@MainActivity, !EventLog.isEnabled(this@MainActivity)); refresh()
            }
        }
        root.addView(logToggle)
        logHeader = TextView(this).apply { textSize = 14f }
        root.addView(logHeader)
        log = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        NetWidget.refreshAll(this); refresh()
    }

    private fun refresh() {
        val s = NetStatus.read(this)
        status.text = """
            CoverNet ${BuildConfig.VERSION_NAME}

            Wi-Fi: ${s.wifi}${s.ssid?.let { " · $it" } ?: ""}
            Cellular: ${s.cell}
            (read at ${s.time})

            Location: ${if (NetStatus.hasLocation(this)) "granted" else "NOT granted"}, background: ${if (NetStatus.hasBackgroundLocation(this)) "granted" else "NOT granted"}
            Battery unrestricted: ${getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)}
            Exact alarms allowed: ${getSystemService(AlarmManager::class.java).canScheduleExactAlarms()}
        """.trimIndent()
        val on = EventLog.isEnabled(this)
        logToggle.text = if (on) "Event log: ON (tap to turn off and clear)" else "Event log: off (tap to enable for troubleshooting)"
        logHeader.text = if (on) "Event log — what reaches the widget, newest first (last 150 lines kept):" else ""
        log.text = if (on) EventLog.read(this).lines().reversed().joinToString("\n") else ""
    }
}
