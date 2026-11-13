package io.github.aiphonepeek

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 补报一轮：使用记录 + 位置。同一时间只跑一轮；使用记录两分钟内不重复，位置一分钟内不重复。
 * 两样各记各的「上次结果」（last_usage / last_loc）：自动那几轮只报了位置时，不会把上次使用记录的结果盖成「这次不补」。
 */
object Sync {
    private val running = AtomicBoolean(false)
    @Volatile private var lastAt = 0L
    @Volatile private var lastLocAt = 0L
    @Volatile var lastResult: String = "还没跑过"
        private set
    /** 正在跑哪一步（给主页状态卡显示），空 = 没在跑 */
    @Volatile var stage: String = ""
        private set
    @Volatile var startedAt = 0L
        private set

    fun isRunning() = running.get()

    private fun hm() = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())

    /** 返回 true = 这一轮开跑了；false = 没开（上报没开、刚跑过、或者上一轮还在跑）。 */
    fun runAsync(ctx: Context, trigger: String, days: Int = 7, force: Boolean = false): Boolean {
        val app = ctx.applicationContext
        if (!Config.reportEnabled(app)) return false
        val now = System.currentTimeMillis()
        // 位置每次点开都要（超过 1 分钟没报就报），不然出门那几分钟还显示在家
        val doUsage = force || now - lastAt >= 2 * 60_000
        val doLoc = force || now - lastLocAt >= 60_000
        if (!doUsage && !doLoc) return false
        if (!running.compareAndSet(false, true)) return false
        if (doUsage) lastAt = now
        if (doLoc) lastLocAt = now
        startedAt = now
        Thread {
            val prefs = app.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
            try {
                var u: String? = null
                var l: String? = null
                if (doUsage) {
                    stage = "正在读使用记录并上报"
                    u = try { UsageReader.reportRecent(app, days) } catch (e: Exception) { "使用记录出错：${e.message}" }
                    prefs.edit().putString("last_usage", "${hm()} $u").apply()
                }
                if (doLoc) {
                    stage = "正在取位置（室内可能要等十几秒）"
                    l = try { Locator.fetchAndSend(app, trigger) } catch (e: Exception) { "位置出错：${e.message}" }
                    prefs.edit().putString("last_loc", "${hm()} $l").apply()
                }
                lastResult = listOfNotNull(u, l).joinToString("；")
                prefs.edit().putString("last_sync", "${hm()} $lastResult").apply()
            } finally { stage = ""; running.set(false) }
        }.start()
        return true
    }

    /** 状态卡和设置页用：两样各一行 */
    fun summary(ctx: Context): String {
        val p = ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
        val u = p.getString("last_usage", "") ?: ""
        val l = p.getString("last_loc", "") ?: ""
        // 小蒜 r1 P3：从 1.1.0 升上来、新的两项还没写过时，先显示旧的那一行
        if (u.isEmpty() && l.isEmpty()) (p.getString("last_sync", "") ?: "").let { if (it.isNotEmpty()) return "上次补报：$it" }
        return "使用记录：${u.ifEmpty { "还没补过" }}\n位置：${l.ifEmpty { "还没报过" }}"
    }
}
