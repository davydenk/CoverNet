package io.github.davydenk.covernet

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

/** Clock-face / lock-screen tile ("monotone" host). Face-1/Face-2 differ only in provider XML. */
abstract class NetWidget(private val variant: String, private val layout: Int, private val rootId: Int) : AppWidgetProvider() {

    override fun onEnabled(ctx: Context) { EventLog.append(ctx, "$variant onEnabled"); Refresh.schedule(ctx) }
    override fun onDisabled(ctx: Context) { EventLog.append(ctx, "$variant onDisabled") }   // chain stops itself when no widgets remain

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        EventLog.append(ctx, "$variant onUpdate " + ids.joinToString(" ") { id -> "id=$id[${describe(mgr.getAppWidgetOptions(id))}]" })
        Refresh.schedule(ctx)   // idempotent; also re-arms after app update
        val views = build(ctx)
        ids.forEach { mgr.updateAppWidget(it, views) }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        EventLog.append(ctx, "$variant optionsChanged id=$id ${describe(opts)}")
        mgr.updateAppWidget(id, build(ctx))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != AppWidgetManager.ACTION_APPWIDGET_UPDATE && a != AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED) {
            EventLog.append(ctx, "$variant onReceive $a ${describe(intent.extras)}")
        }
        when (a) {
            ACTION_REFRESH -> { if (refreshAll(ctx)) Refresh.schedule(ctx) }   // chain the next minute while any widget exists
            ACTION_VISIBILITY, ACTION_VISIBILITY_HOME -> refreshAll(ctx)   // tile just became visible (or hidden; harmless)
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
            AppWidgetManager.ACTION_APPWIDGET_ENABLED, AppWidgetManager.ACTION_APPWIDGET_DISABLED,
            AppWidgetManager.ACTION_APPWIDGET_DELETED, AppWidgetManager.ACTION_APPWIDGET_RESTORED -> super.onReceive(ctx, intent)
            else -> refreshAll(ctx)   // unknown (probably Samsung visibility) broadcast: refresh, it is cheap
        }
    }

    private fun build(ctx: Context): RemoteViews {
        val s = NetStatus.read(ctx)
        return RemoteViews(ctx.packageName, layout).apply {
            setImageViewResource(R.id.wifi_icon, if (s.wifiOk) R.drawable.ic_wifi else R.drawable.ic_wifi_off)
            setInt(R.id.wifi_icon, "setColorFilter", if (s.wifiOk) WHITE else RED)
            setTextViewText(R.id.wifi, s.wifiLabel)
            setImageViewResource(R.id.cell_icon, if (s.cellOk) R.drawable.ic_cell else R.drawable.ic_cell_off)
            setInt(R.id.cell_icon, "setColorFilter", if (s.cellOk) WHITE else RED)
            setTextViewText(R.id.cell, s.cellLabel)
            setOnClickPendingIntent(rootId, PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, this@NetWidget.javaClass).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
    }

    private fun describe(b: Bundle?): String =
        b?.keySet()?.joinToString(" ") { k -> "$k=${b.get(k)}" } ?: ""

    companion object {
        const val ACTION_REFRESH = "io.github.davydenk.covernet.REFRESH"
        const val ACTION_VISIBILITY = "com.samsung.android.sdk.subscreen.widget.action.VISIBILITY_CHANGED"
        const val ACTION_VISIBILITY_HOME = "com.samsung.android.honeyspace.widget.action.VISIBILITY_CHANGED"
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val RED = 0xFFFF5252.toInt()

        private val ALL = listOf(WidgetFace1::class.java, WidgetHome::class.java)

        /** Pushes an update to every placed widget; returns false when none exist. */
        fun refreshAll(ctx: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(ctx)
            var any = false
            for (cls in ALL) {
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
                if (ids.isNotEmpty()) {
                    any = true
                    ctx.sendBroadcast(Intent(ctx, cls).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
                }
            }
            return any
        }
    }
}

class WidgetFace1 : NetWidget("tile", R.layout.widget_face, R.id.root)
class WidgetHome : NetWidget("home", R.layout.widget_home, android.R.id.background)
