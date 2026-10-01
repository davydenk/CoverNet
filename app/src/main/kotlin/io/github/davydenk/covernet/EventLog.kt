package io.github.davydenk.covernet

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug ring-buffer log (last [MAX] lines in SharedPreferences) showing which broadcasts reach the widget.
 * Off by default; the user turns it on from the main screen when troubleshooting.
 */
object EventLog {
    private const val MAX = 150
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("events", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        if (!on) clear(ctx)
    }

    fun append(ctx: Context, msg: String) {
        val p = prefs(ctx)
        if (!p.getBoolean("enabled", false)) return
        val t = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val lines = (p.getString("log", "") ?: "").lines().filter { it.isNotBlank() }.takeLast(MAX - 1)
        p.edit().putString("log", (lines + "$t  $msg").joinToString("\n")).apply()
    }

    fun read(ctx: Context): String = prefs(ctx).getString("log", "") ?: ""
    fun clear(ctx: Context) = prefs(ctx).edit().remove("log").apply()
}
