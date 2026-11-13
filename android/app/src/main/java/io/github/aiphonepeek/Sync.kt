package io.github.aiphonepeek

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** 补报一轮：使用记录 + 位置。同一时间只跑一轮；使用记录两分钟内不重复，位置一分钟内不重复。 */
object Sync {
    private val running = AtomicBoolean(false)
    @Volatile private var lastAt = 0L
    @Volatile private var lastLocAt = 0L
    @Volatile var lastResult: String = "还没跑过"
        private set

    fun isRunning() = running.get()

    fun runAsync(ctx: Context, trigger: String, days: Int = 7, force: Boolean = false) {
        val app = ctx.applicationContext
        if (!Config.reportEnabled(app)) return
        val now = System.currentTimeMillis()
        // 位置每次点开都要（超过 1 分钟没报就报），不然出门那几分钟还显示在家
        val doUsage = force || now - lastAt >= 2 * 60_000
        val doLoc = force || now - lastLocAt >= 60_000
        if (!doUsage && !doLoc) return
        if (!running.compareAndSet(false, true)) return
        if (doUsage) lastAt = now
        if (doLoc) lastLocAt = now
        Thread {
            try {
                val u = if (doUsage) (try { UsageReader.reportRecent(app, days) } catch (e: Exception) { "使用记录出错：${e.message}" }) else "使用记录这次不补"
                val l = if (doLoc) (try { Locator.fetchAndSend(app, trigger) } catch (e: Exception) { "位置出错：${e.message}" }) else "位置这次不报"
                lastResult = "$u；$l"
                app.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).edit()
                    .putString("last_sync", "${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())} $lastResult").apply()
            } finally { running.set(false) }
        }.start()
    }
}
