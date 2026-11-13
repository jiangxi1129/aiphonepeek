package io.github.aiphonepeek

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 取一次位置 + 电量，POST 到 {上报地址}/location。
 * 用安卓自带定位：室外 GPS，室内网络。坐标是 WGS-84，需要的话（比如国内地图）由服务端换算。
 */
object Locator {
    private const val FRESH_MS = 60_000L   // 缓存的定位超过 1 分钟就重新取，否则出门几分钟还在用家里那次

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 阻塞。返回说明文字。 */
    fun fetchAndSend(ctx: Context, trigger: String): String {
        if (!hasPermission(ctx)) return "没有位置权限"
        val loc = fetch(ctx) ?: return "没拿到位置"
        return send(ctx, loc, trigger)
    }

    fun send(ctx: Context, loc: Location, trigger: String): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batt = try { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 } catch (_: Exception) { -1 }
        val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val body = JSONObject()
            .put("latitude", loc.latitude)
            .put("longitude", loc.longitude)
            .put("accuracy_m", loc.accuracy.toDouble())
            .put("timestamp_ms", loc.time)
            .put("provider", loc.provider ?: JSONObject.NULL)
            .put("battery_level", if (batt in 0..100) batt else JSONObject.NULL)
            .put("charging", charging)
            .put("trigger", trigger)
            .put("source", "aiphonepeek-${Build.MODEL}")
            .toString()
        val err = Net.postJson(ctx, "/location", body)
        return if (err == null) "位置已报（±${loc.accuracy.toInt()} 米）" else "位置没报上：$err"
    }

    private fun fetch(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val now = System.currentTimeMillis()
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val last = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
            if (last != null && now - last.time < FRESH_MS && (best == null || last.accuracy < best.accuracy)) best = last
        }
        if (best != null) return best
        // 先网络（室内快），拿不到再等 GPS
        return single(lm, LocationManager.NETWORK_PROVIDER, 10_000) ?: single(lm, LocationManager.GPS_PROVIDER, 20_000)
    }

    private fun single(lm: LocationManager, provider: String, timeoutMs: Long): Location? {
        if (!try { lm.isProviderEnabled(provider) } catch (_: Exception) { false }) return null
        val slot = arrayOfNulls<Location>(1)
        val latch = CountDownLatch(1)
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) { slot[0] = location; latch.countDown() }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("old api") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
        }
        try { lm.requestLocationUpdates(provider, 0L, 0f, l, Looper.getMainLooper()) } catch (_: Exception) { return null }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        try { lm.removeUpdates(l) } catch (_: Exception) {}
        return slot[0]
    }
}
