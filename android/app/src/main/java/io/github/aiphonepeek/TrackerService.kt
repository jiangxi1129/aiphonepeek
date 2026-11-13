package io.github.aiphonepeek

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 后台记轨迹：每 15 分钟记一次位置，顺手补报今天的使用记录。
 * 安卓规定后台一直拿位置的 App 必须挂一条常驻通知，去不掉。通知上有「设置」按钮。
 * 被系统杀掉的那段就记不上，等你下次点开 App 再接上（使用记录会按天整份补齐，位置轨迹那段是缺的）。
 */
class TrackerService : Service() {
    companion object {
        const val INTERVAL_MS = 15L * 60_000
        private const val CH = "tracker"
        fun start(ctx: Context) {
            if (!Locator.hasPermission(ctx) || !Config.reportEnabled(ctx)) return
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, TrackerService::class.java)) } catch (_: Exception) {}
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastSent = 0L
    private val tick = object : Runnable {
        override fun run() {
            Thread {
                if (!Config.reportEnabled(applicationContext)) { handler.post { stopSelf() }; return@Thread }
                try { UsageReader.reportRecent(applicationContext, 1) } catch (_: Exception) {}
                if (System.currentTimeMillis() - lastSent > INTERVAL_MS - 60_000) {
                    try { Locator.fetchAndSend(applicationContext, "track"); lastSent = System.currentTimeMillis() } catch (_: Exception) {}
                }
            }.start()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }
    // 系统主动推来的位置（走路、坐车时更勤），隔够 15 分钟才发一次
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (System.currentTimeMillis() - lastSent < INTERVAL_MS - 60_000) return
            lastSent = System.currentTimeMillis()
            Thread { try { Locator.send(applicationContext, location, "track") } catch (_: Exception) {} }.start()
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("old api") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
    }
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Locator.hasPermission(this) || !Config.reportEnabled(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(1, notification())
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (!started) {
            started = true
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                try { if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, INTERVAL_MS, 50f, listener, Looper.getMainLooper()) } catch (_: Exception) {}
            }
            handler.post(tick)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "后台记录", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val settings = PendingIntent.getActivity(this, 1, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name) + " 正在运行")
            .setContentText("在记手机使用情况和位置，只发到你填的地址")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "设置", settings).build())
            .setOngoing(true)
            .build()
    }
}
