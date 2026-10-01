package io.github.davydenk.covernet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.HandlerThread
import android.telephony.TelephonyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One-shot snapshot of Wi-Fi / cellular state. */
data class NetStatus(
    val wifi: String, val wifiOk: Boolean, val ssid: String?,
    val cell: String, val cellOk: Boolean, val operator: String?,
    val time: String,
) {
    val wifiMark get() = mark(wifiOk, wifi)
    val cellMark get() = mark(cellOk, cell)

    /** Short labels for the clock-face tile. */
    val wifiLabel get() = when {
        wifiOk -> ssid ?: "Wi-Fi"
        wifi.contains("no internet") -> "no internet"
        else -> "off"
    }
    val cellLabel get() = if (cellOk) (operator ?: "Cell") else cell

    companion object {
        private fun mark(ok: Boolean, text: String) = when {
            ok -> "✓"
            text.contains("no internet") || text.contains("connecting") -> "~"
            else -> "✗"
        }

        fun hasLocation(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        fun hasBackgroundLocation(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun read(ctx: Context): NetStatus {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            var wifi = "off"; var wifiOk = false; var wifiNet: Network? = null
            var cellData = "no data"

            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                val validated = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val label = if (validated) "online" else "no internet"
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { wifi = label; wifiOk = validated; wifiNet = n }
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellData = "data $label"
            }
            val ssid = if (wifiNet != null && hasLocation(ctx)) cachedSsid(ctx, cm, wifiNet) else null

            // "Connected" for cellular = registered on a network (true even while Wi-Fi carries the data).
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            val op = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: tm.networkOperator?.takeIf { it.isNotBlank() }
            val cellOk = op != null
            val cell = when {
                op != null -> "$op · $cellData"
                tm.simState == TelephonyManager.SIM_STATE_READY -> "no service"
                else -> "no SIM"
            }

            val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            return NetStatus(wifi, wifiOk, ssid, cell, cellOk, op, t)
        }

        /**
         * Reading the SSID is a location access (privacy indicator lights up), so do it once per Wi-Fi
         * network and remember it keyed by the network handle.
         */
        private fun cachedSsid(ctx: Context, cm: ConnectivityManager, net: Network): String? {
            val p = ctx.getSharedPreferences("ssid", Context.MODE_PRIVATE)
            val handle = net.networkHandle
            if (p.getLong("handle", -1) == handle) return p.getString("ssid", null)
            val ssid = fetchSsid(cm)
            if (ssid != null) p.edit().putLong("handle", handle).putString("ssid", ssid).apply()
            return ssid
        }

        /**
         * SSID is redacted from getNetworkCapabilities(); it is only delivered to a callback registered with
         * FLAG_INCLUDE_LOCATION_INFO. Register on a worker thread, wait briefly for the initial callback.
         */
        private fun fetchSsid(cm: ConnectivityManager): String? {
            val thread = HandlerThread("ssid").apply { start() }
            val latch = CountDownLatch(1)
            var ssid: String? = null
            val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    (caps.transportInfo as? WifiInfo)?.let { ssid = it.ssid; latch.countDown() }
                }
            }
            return try {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    cb, Handler(thread.looper)
                )
                latch.await(1500, TimeUnit.MILLISECONDS)
                ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            } catch (e: Exception) { null } finally {
                try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
                thread.quitSafely()
            }
        }
    }
}
