package app.astral.client

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

object Perms {
    enum class P { OVERLAY, FILES, NOTIF, USAGE, BATTERY, AUTOSTART }

    val all = listOf(P.OVERLAY, P.FILES, P.NOTIF, P.USAGE, P.BATTERY, P.AUTOSTART)

    fun label(p: P): String = Str.t("perm_" + p.name.lowercase())

    fun granted(c: Context, p: P): Boolean = when (p) {
        P.OVERLAY -> Settings.canDrawOverlays(c)
        P.FILES -> if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        P.NOTIF -> Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        P.USAGE -> usageGranted(c)
        P.BATTERY -> (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)
        P.AUTOSTART -> Cfg.autostartDone
    }

    @Suppress("DEPRECATION")
    fun usageGranted(c: Context): Boolean {
        val a = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            a.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun uri(a: Activity) = Uri.parse("package:${a.packageName}")

    /** Starts the system UI for one permission. Returns false when nothing could be opened. */
    fun request(a: Activity, p: P): Boolean {
        try {
            when (p) {
                P.OVERLAY -> a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri(a)))
                P.NOTIF -> {
                    if (Build.VERSION.SDK_INT < 33) return false
                    if (Cfg.notifAsked && !a.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                        // denied "for good": the system dialog would return instantly and never come back to us
                        a.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
                        )
                    } else {
                        Cfg.notifAsked = true
                        a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                    }
                }
                P.FILES -> {
                    if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri(a)))
                        } catch (_: Exception) {
                            a.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    } else {
                        a.requestPermissions(
                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101
                        )
                    }
                }
                P.USAGE -> {
                    val i = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    if (Build.VERSION.SDK_INT >= 29) i.data = uri(a)
                    a.startActivity(i)
                }
                P.BATTERY -> {
                    try {
                        a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri(a)))
                    } catch (_: Exception) {
                        a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
                P.AUTOSTART -> {
                    // Android has no public autostart API: open the OEM screen when there is one, otherwise app settings.
                    Cfg.autostartDone = true
                    return openAutostart(a)
                }
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private val oem = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity"
    )

    private fun openAutostart(a: Activity): Boolean {
        for ((pkg, cls) in oem) {
            val i = Intent().setComponent(ComponentName(pkg, cls))
            if (a.packageManager.resolveActivity(i, 0) != null) {
                try {
                    a.startActivity(i)
                    return true
                } catch (_: Exception) {
                }
            }
        }
        return try {
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri(a)))
            true
        } catch (_: Exception) {
            false
        }
    }
}

/** Tracks which app is in the foreground (needs usage access) so the overlay only shows over Minecraft. */
object Fg {
    const val MC = "com.mojang.minecraftpe"
    private var cur: String? = MC
    private var last = 0L

    fun reset() {
        cur = MC
        last = System.currentTimeMillis()
    }

    @Suppress("DEPRECATION")
    fun poll(c: Context): String? {
        if (!Perms.usageGranted(c)) return MC // unknown -> always show
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(maxOf(last - 2000, now - 600_000), now)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> cur = e.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (cur == e.packageName) cur = null
            }
        }
        last = now
        return cur
    }
}
