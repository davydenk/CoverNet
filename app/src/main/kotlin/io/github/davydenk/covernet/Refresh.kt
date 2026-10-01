package io.github.davydenk.covernet

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Refresh chain: one alarm a minute, re-armed by the receiver. ELAPSED_REALTIME (non-wakeup) never wakes the
 * CPU; in Doze it is simply delivered when the device wakes — i.e. right when the cover screen turns on.
 * Exact alarms (if the user allowed them) are not batched, so the tile is at most ~1 min stale while awake.
 */
object Refresh {
    private const val INTERVAL_MS = 60_000L

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, WidgetFace1::class.java).setAction(NetWidget.ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + INTERVAL_MS
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, at, pending(ctx))
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, at, pending(ctx))
    }

    fun cancel(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
}

/** Alarms don't survive a reboot; re-arm. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            EventLog.append(ctx, "boot completed")
            Refresh.schedule(ctx)
            NetWidget.refreshAll(ctx)
        }
    }
}
