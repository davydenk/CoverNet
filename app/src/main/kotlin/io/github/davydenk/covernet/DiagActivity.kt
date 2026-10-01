package io.github.davydenk.covernet

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageItemInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.xmlpull.v1.XmlPullParser
import android.content.ContentValues
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

/**
 * Dumps everything that could explain how an app gets into the cover-screen clock-face widget picker:
 * every AppWidget receiver with its android + Samsung provider XML, Samsung-looking meta-data keys,
 * Samsung "face widget" (service box) implementers, and Samsung-defined widget-ish permissions.
 */
class DiagActivity : Activity() {
    private var report: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; text = "Scanning… (host code scan takes a few seconds)" }
        val savedView = TextView(this).apply { textSize = 12f }
        Thread {
            val report = try { buildReport() } catch (t: Throwable) { "REPORT FAILED: $t\n${t.stackTraceToString()}" }
            val saved = saveToDownloads(report)
            runOnUiThread { savedView.text = saved; body.text = report; this.report = report }
        }.start()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Share"
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "covernet-diag.txt")
                    putExtra(Intent.EXTRA_TEXT, report)
                }, "Share report"))
            }
        })
        buttons.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("diag", report))
                Toast.makeText(this@DiagActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(buttons)
        root.addView(savedView)
        root.addView(ScrollView(this).apply { addView(body) })
        setContentView(root)
    }

    /** Writes the report to the public Downloads folder via MediaStore (no storage permission needed on API 29+). */
    private fun saveToDownloads(report: String): String {
        val name = "covernet-diag-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "Save FAILED: insert returned null"
            contentResolver.openOutputStream(uri)!!.use { it.write(report.toByteArray()) }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            "Saved to Downloads/$name (${report.length / 1024} KB)"
        } catch (t: Throwable) { "Save FAILED: $t" }
    }

    private fun buildReport(): String {
        val pm = packageManager
        val sb = StringBuilder()
        sb.appendLine("CoverNet diagnostics ${BuildConfig.VERSION_NAME}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT}")
        sb.appendLine("Build: ${Build.DISPLAY}")
        sb.appendLine("OneUI: ${sysprop("ro.build.version.oneui")}  sep: ${sysprop("ro.build.version.sep")}  sem: ${sysprop("ro.build.version.sem")}")
        sb.appendLine()

        // 00. Samsung's own lock-face tile layouts, to see whether the background is theirs or the host's
        sb.appendLine("=== Samsung monotone tile layouts ===")
        for ((pkg, id) in listOf(
            "com.android.settings.intelligence" to 2131494180, "com.android.settings.intelligence" to 2131494182,
            "com.sec.android.daemonapp" to 2131493170, "com.sec.android.daemonapp" to 2131493169,
            "com.samsung.android.app.routines" to 2131494753, "com.samsung.android.lool" to 2131560073)) dumpLayout(pkg, id, sb)
        sb.appendLine()

        // 0. Strings in the widget hosts' code that look like the cover/lock-screen visibility protocol
        sb.appendLine("=== Host code scan: candidate broadcast actions / keys (visibility, subscreen, appwidget) ===")
        val hosts = listOf("com.samsung.android.app.aodservice", "com.android.systemui", "com.sec.android.app.launcher",
            "com.samsung.android.app.cocktailbarservice", "com.samsung.android.honeyboard") +
            pm.getInstalledApplications(0).map { it.packageName }.filter { it.contains("subscreen") || it.contains("coverscreen") || it.contains("flexwindow") }
        for (h in hosts.distinct()) {
            val ai = try { pm.getApplicationInfo(h, 0) } catch (e: Exception) { sb.appendLine("$h: not installed"); continue }
            val files = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
            sb.appendLine("## $h  (${files.size} apk)")
            val hits = sortedSetOf<String>()
            for (f in files) try { scanDexStrings(File(f), hits) } catch (e: Exception) { sb.appendLine("  scan error $f: $e") }
            if (hits.isEmpty()) sb.appendLine("  (no matches)")
            hits.forEach { sb.appendLine("  $it") }
        }
        sb.appendLine()

        // 1. Samsung-defined permissions that look widget/cover/lock related, with protection level
        sb.appendLine("=== Samsung widget/cover/lock-related permissions (defined by installed packages) ===")
        val pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_META_DATA)
        for (pkg in pkgs) {
            for (perm in pkg.permissions ?: continue) {
                val n = perm.name.uppercase()
                if (n.contains("WIDGET") || n.contains("SUBSCREEN") || n.contains("SUB_SCREEN") || n.contains("COVER") ||
                    n.contains("FACE") || n.contains("SERVICEBOX") || n.contains("KEYGUARD") || n.contains("LOCKSCREEN") || n.contains("LOCK_SCREEN")) {
                    sb.appendLine("${perm.name}  [${pkg.packageName}]  ${protection(perm)}")
                }
            }
        }
        sb.appendLine()

        // 2. Face widget (service box) implementers — Samsung's One UI 3-6 lock screen widget mechanism
        sb.appendLine("=== Receivers for REQUEST_SERVICEBOX_REMOTEVIEWS (Samsung face widgets) ===")
        val fw = pm.queryBroadcastReceivers(Intent("com.samsung.android.intent.action.REQUEST_SERVICEBOX_REMOTEVIEWS"), PackageManager.GET_META_DATA)
        if (fw.isEmpty()) sb.appendLine("(none)")
        for (r in fw) sb.appendLine("${r.activityInfo.packageName} / ${r.activityInfo.name}")
        sb.appendLine()

        // 3. Application-level meta-data keys that smell like Samsung cover/lock/widget hooks
        sb.appendLine("=== Application-level meta-data keys (samsung/widget/cover/lock/face) ===")
        for (pkg in pkgs) {
            val md = pkg.applicationInfo?.metaData ?: continue
            for (k in md.keySet()) {
                val kl = k.lowercase()
                if ((kl.contains("samsung") || kl.contains("sec.")) &&
                    (kl.contains("widget") || kl.contains("cover") || kl.contains("lock") || kl.contains("face") || kl.contains("sub") || kl.contains("servicebox") || kl.contains("complication"))) {
                    sb.appendLine("${pkg.packageName}: $k = ${md.get(k)}")
                }
            }
        }
        sb.appendLine()

        // 4. Every app-widget receiver, with full android + Samsung provider XML and all other meta-data keys
        sb.appendLine("=== AppWidget receivers (APPWIDGET_UPDATE) ===")
        val receivers = pm.queryBroadcastReceivers(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE), PackageManager.GET_META_DATA)
            .sortedWith(compareBy({ it.activityInfo.packageName }, { it.activityInfo.name }))
        var lastPkg = ""
        for (r in receivers) {
            val ai = r.activityInfo
            if (ai.packageName != lastPkg) {
                lastPkg = ai.packageName
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(ai.packageName, 0)) } catch (e: Exception) { "?" }
                sb.appendLine()
                sb.appendLine("## ${ai.packageName}  ($label)")
            }
            sb.appendLine("- receiver ${ai.name.removePrefix(ai.packageName)}  label='${ai.loadLabel(pm)}' exported=${ai.exported}")
            val md = ai.metaData
            if (md == null) { sb.appendLine("    (no meta-data)"); continue }
            for (k in md.keySet().sorted()) {
                when (k) {
                    "android.appwidget.provider", "com.samsung.android.appwidget.provider" -> {
                        sb.appendLine("    $k:")
                        dumpXml(ai, k, sb)
                    }
                    else -> {
                        sb.appendLine("    $k = ${md.get(k)}")
                        // try to dump as XML too, in case it's a resource pointing at another descriptor
                        if (md.get(k) is Int) dumpXml(ai, k, sb)
                    }
                }
            }
        }
        return sb.toString()
    }

    private val interesting = Regex("(?i)(visib|subscreen|sub_screen|appwidget\\.action|APPWIDGET_[A-Z_]+|monotone|facewidget|servicebox|widget.*(show|hide|expose|screen_on|screenon))")
    private val noise = Regex("^(L[a-z]|\\(|\\[|<)|/|\\$")   // class descriptors, signatures, inner classes

    /** Pull printable ASCII runs out of every classes*.dex in an APK and keep those matching [interesting]. */
    private fun scanDexStrings(apk: File, out: MutableSet<String>) {
        val zip = java.util.zip.ZipFile(apk)
        zip.use {
            for (e in zip.entries()) {
                if (!e.name.startsWith("classes") || !e.name.endsWith(".dex")) continue
                zip.getInputStream(e).buffered(1 shl 20).use { ins ->
                    val cur = StringBuilder()
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        for (i in 0 until n) {
                            val b = buf[i].toInt() and 0xff
                            if (b in 0x20..0x7e) cur.append(b.toChar())
                            else { flush(cur, out); }
                        }
                    }
                    flush(cur, out)
                }
            }
        }
    }

    private fun flush(cur: StringBuilder, out: MutableSet<String>) {
        if (cur.length in 8..200) {
            val str = cur.toString()
            if (interesting.containsMatchIn(str) && !noise.containsMatchIn(str)) out.add(str)
        }
        cur.setLength(0)
    }

    private fun dumpLayout(pkg: String, id: Int, sb: StringBuilder) {
        try {
            val res = packageManager.getResourcesForApplication(pkg)
            sb.appendLine("## $pkg ${res.getResourceName(id)}")
            val parser = res.getLayout(id)
            var depth = 0
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val attrs = (0 until parser.attributeCount).joinToString(" ") { i ->
                        var v = parser.getAttributeValue(i)
                        if (v.startsWith("@")) v = try { "@" + res.getResourceName(v.substring(1).toInt()) } catch (e: Exception) { v }
                        "${parser.getAttributeName(i)}=\"$v\""
                    }
                    sb.appendLine("  ".repeat(depth + 1) + "<${parser.name} $attrs>")
                    depth++
                } else if (ev == XmlPullParser.END_TAG) depth--
                ev = parser.next()
            }
            parser.close()
        } catch (e: Exception) { sb.appendLine("## $pkg @$id: failed: $e") }
    }

    private fun dumpXml(ai: PackageItemInfo, key: String, sb: StringBuilder) {
        val parser = try { ai.loadXmlMetaData(packageManager, key) } catch (e: Exception) { null }
        if (parser == null) { sb.appendLine("      (not xml)"); return }
        try {
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val attrs = (0 until parser.attributeCount).joinToString(" ") { i ->
                        val ns = parser.getAttributeNamespace(i)
                        val prefix = if (ns.isNullOrEmpty()) "" else if (ns.endsWith("/android")) "android:" else "{$ns}"
                        "$prefix${parser.getAttributeName(i)}=\"${parser.getAttributeValue(i)}\""
                    }
                    sb.appendLine("      <${parser.name} $attrs>")
                }
                ev = parser.next()
            }
        } catch (e: Exception) {
            sb.appendLine("      (parse error: $e)")
        } finally { parser.close() }
    }

    private fun protection(p: PermissionInfo): String {
        val base = when (p.protection) {
            PermissionInfo.PROTECTION_NORMAL -> "normal"
            PermissionInfo.PROTECTION_DANGEROUS -> "dangerous"
            PermissionInfo.PROTECTION_SIGNATURE -> "signature"
            else -> "internal(${p.protection})"
        }
        val flags = p.protectionFlags
        val extra = buildList {
            if (flags and PermissionInfo.PROTECTION_FLAG_PRIVILEGED != 0) add("privileged")
            if (flags and PermissionInfo.PROTECTION_FLAG_PRE23 != 0) add("pre23")
            if (flags and PermissionInfo.PROTECTION_FLAG_APPOP != 0) add("appop")
            if (flags and PermissionInfo.PROTECTION_FLAG_DEVELOPMENT != 0) add("development")
            if (flags and PermissionInfo.PROTECTION_FLAG_PREINSTALLED != 0) add("preinstalled")
        }
        return if (extra.isEmpty()) base else "$base|${extra.joinToString("|")}"
    }

    @Suppress("PrivateApi")
    private fun sysprop(key: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, key) as String
    } catch (e: Exception) { "?" }
}
