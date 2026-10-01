package io.github.davydenk.covernet

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Lists every visible access point, strongest first, and marks the one we're associated with. */
class ScanActivity : Activity() {
    private lateinit var out: TextView
    private lateinit var status: TextView
    private val wifi by lazy { getSystemService(WifiManager::class.java) }

    private val results = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val fresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            render(if (fresh) "scan complete" else "scan failed — showing last cached results")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        root.addView(Button(this).apply { text = "Scan"; setOnClickListener { scan() } })
        status = TextView(this).apply { textSize = 12f }
        root.addView(status)
        out = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE }
        root.addView(ScrollView(this).apply { addView(out) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(results, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        if (ensurePermissions()) scan()
    }

    override fun onPause() { super.onPause(); unregisterReceiver(results) }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.all { it == PackageManager.PERMISSION_GRANTED }) scan() else status.text = "Need location + nearby-devices permission to list access points."
    }

    private fun ensurePermissions(): Boolean {
        val needed = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) return true
        requestPermissions(needed.toTypedArray(), 10)
        return false
    }

    private fun scan() {
        if (!ensurePermissions()) return
        @Suppress("DEPRECATION")
        val started = wifi.startScan()
        status.text = if (started) "scanning…" else "scan throttled (Android allows 4 per 2 min) — showing last results"
        if (!started) render(status.text.toString())
    }

    @Suppress("DEPRECATION")
    private fun render(note: String) {
        val list = try { wifi.scanResults } catch (e: SecurityException) { status.text = "no permission: $e"; return }
        val me = currentWifiInfo()
        val myBssid = me?.bssid?.lowercase()
        val sb = StringBuilder()
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        sb.appendLine("$t  $note")
        if (me != null && myBssid != null && myBssid != "02:00:00:00:00:00") {
            sb.appendLine("Connected: ${me.ssid.trim('"')}  $myBssid  ${me.rssi} dBm  ${band(me.frequency)} ch${channel(me.frequency)}  ${me.linkSpeed} Mbps  ${std(me.wifiStandard)}")
        } else sb.appendLine("Connected: (not on Wi-Fi, or SSID/BSSID hidden — needs location permission + location on)")
        sb.appendLine()

        val sorted = list.sortedByDescending { it.level }
        val mySsid = me?.ssid?.trim('"')
        val same = sorted.count { it.SSID == mySsid }
        sb.appendLine("${sorted.size} access points visible" + (if (mySsid != null) ", $same broadcasting \"$mySsid\"" else ""))
        sb.appendLine("     dBm  band   ch  std      BSSID              SSID")
        for (r in sorted) {
            val mark = if (r.BSSID.lowercase() == myBssid) "★" else " "
            val ssid = r.SSID.ifBlank { "<hidden>" }
            sb.appendLine("$mark ${r.level.toString().padStart(5)}  ${band(r.frequency).padEnd(5)} ${channel(r.frequency).toString().padStart(4)}  ${std(r.wifiStandard).padEnd(7)}  ${r.BSSID}  $ssid")
        }
        out.text = sb.toString()
        status.text = note
    }

    private fun band(freq: Int) = when {
        freq in 2400..2500 -> "2.4G"
        freq in 5150..5900 -> "5G"
        freq >= 5925 -> "6G"
        else -> "?"
    }

    private fun channel(freq: Int) = when {
        freq == 2484 -> 14
        freq in 2400..2500 -> (freq - 2407) / 5
        freq in 5150..5900 -> (freq - 5000) / 5
        freq >= 5925 -> (freq - 5950) / 5
        else -> 0
    }

    private fun std(s: Int) = when (s) {
        ScanResult.WIFI_STANDARD_LEGACY -> "a/b/g"
        ScanResult.WIFI_STANDARD_11N -> "n (4)"
        ScanResult.WIFI_STANDARD_11AC -> "ac (5)"
        ScanResult.WIFI_STANDARD_11AX -> "ax (6)"
        ScanResult.WIFI_STANDARD_11AD -> "ad"
        ScanResult.WIFI_STANDARD_11BE -> "be (7)"
        else -> "?"
    }

    /** Unredacted WifiInfo (BSSID/SSID) only arrives via a callback registered with FLAG_INCLUDE_LOCATION_INFO. */
    private fun currentWifiInfo(): WifiInfo? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val thread = HandlerThread("wifiinfo").apply { start() }
        val latch = CountDownLatch(1)
        var info: WifiInfo? = null
        val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                (caps.transportInfo as? WifiInfo)?.let { info = it; latch.countDown() }
            }
        }
        return try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb, Handler(thread.looper))
            latch.await(1500, TimeUnit.MILLISECONDS)
            info
        } catch (e: Exception) { null } finally {
            try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
            thread.quitSafely()
        }
    }
}
