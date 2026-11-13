package io.github.aiphonepeek

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 按 ACTIVITY_RESUMED / PAUSED / STOPPED 事件算每个 App 的前台时长，可以算任意一天，用来每次打开补报过去 7 天。
 * 不用 queryAndAggregateUsageStats（它会把时长算成两三倍）。
 * 关键：按「页面（Activity）」记，不是按「包」记，见 collectDay 里的说明。
 */
object UsageReader {
    data class AppUsage(val pkg: String, val label: String, val totalSec: Long)
    data class Session(val pkg: String, val start: Long, val end: Long)
    /** 最近一次 collectDay 顺手记下的「几点到几点在用哪个 App」 */
    @Volatile var lastSessions: List<Session> = emptyList()

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isLauncher(pkg: String) =
        pkg.contains("launcher", ignoreCase = true) || pkg == "com.bbk.launcher2" ||
            pkg == "com.miui.home" || pkg == "com.huawei.android.launcher"

    /** daysAgo=0 是今天。加锁：lastSessions 是它顺手写的，两轮交错会把别天的时间线挂过来。 */
    @Synchronized
    fun collectDay(ctx: Context, daysAgo: Int): List<AppUsage> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = ctx.packageManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -daysAgo)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val end = minOf(cal.timeInMillis, System.currentTimeMillis())

        // 为什么按页面记：按「包」记开关的话，同一个 App 里从 A 页跳到 B 页时事件顺序是 A 暂停 → B 恢复 → A 停止，
        // A 的「停止」会把 B 刚开的那段掐掉，页面跳得多的 App（信息流、聊天类）几乎全漏
        // （实测系统「使用统计」显示 54 分钟，按包算只有 5 分钟）。
        // 现在：一个 App 只要还有一个页面在前台就算在用，最后一个页面离开才收账。
        val totals = HashMap<String, Long>()
        val openCls = HashMap<String, HashSet<String>>()   // 包 → 现在在前台的页面
        val since = HashMap<String, Long>()                 // 包 → 这一段从几点开始
        val seen = HashSet<String>()
        val events = usm.queryEvents(start, end) ?: return emptyList()
        val ev = UsageEvents.Event()
        val sess = ArrayList<Session>()
        fun close(pkg: String, ts: Long) {
            val from = since.remove(pkg) ?: return
            if (ts > from) { totals[pkg] = (totals[pkg] ?: 0L) + (ts - from); sess.add(Session(pkg, from, ts)) }
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val pkg = ev.packageName ?: continue
            val cls = ev.className ?: ""
            val ts = ev.timeStamp
            val first = pkg !in seen
            seen.add(pkg)
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val set = openCls.getOrPut(pkg) { HashSet() }
                    if (set.isEmpty()) since[pkg] = if (ts < start) start else ts
                    set.add(cls)
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val set = openCls[pkg]
                    if (set != null && set.remove(cls)) {
                        if (set.isEmpty()) close(pkg, ts)
                    } else if (first && ev.eventType == UsageEvents.Event.ACTIVITY_PAUSED && !isLauncher(pkg) && since[pkg] == null) {
                        // 跨零点：今天第一件事就是「暂停」，说明零点前就开着
                        totals[pkg] = (totals[pkg] ?: 0L) + (ts - start)
                        sess.add(Session(pkg, start, ts))
                    }
                }
            }
        }
        for ((pkg, _) in since.toMap()) close(pkg, end)
        // 同一个 App 中间断开不到 30 秒（锁屏一下、弹个通知）接成一段；不到 30 秒的零碎段不要
        sess.sortBy { it.start }
        val merged = ArrayList<Session>()
        for (x in sess) {
            val last = merged.lastOrNull()
            if (last != null && last.pkg == x.pkg && x.start - last.end < 30_000) merged[merged.size - 1] = Session(last.pkg, last.start, maxOf(last.end, x.end))
            else merged.add(x)
        }
        lastSessions = merged.filter { it.end - it.start >= 30_000 && !isLauncher(it.pkg) }

        val out = ArrayList<AppUsage>()
        for ((pkg, ms) in totals) {
            val sec = ms / 1000
            if (sec < 60) continue
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: PackageManager.NameNotFoundException) { pkg }
            out.add(AppUsage(pkg, label, sec))
        }
        out.sortByDescending { it.totalSec }
        return out
    }

    fun dateOf(daysAgo: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    }

    /** 补报最近 [days] 天，每天一条（含 sessions 时间线），服务端按日期覆盖。返回 成功天数/有数据的天数 的说明。 */
    @Synchronized
    fun reportRecent(ctx: Context, days: Int): String {
        if (!hasAccess(ctx)) return "没有使用情况访问权限"
        var ok = 0; var had = 0; val errs = ArrayList<String>()
        for (d in 0 until days) {
            val apps = collectDay(ctx, d)
            val sessions = lastSessions
            if (apps.isEmpty()) continue   // 空的不报：读出来空多半是系统没给，不当成「这天没用手机」
            had++
            val appsJson = JSONObject()
            for (a in apps) appsJson.put(a.pkg, JSONObject().put("label", a.label).put("total_sec", a.totalSec))
            val body = JSONObject()
                .put("source", "aiphonepeek-${Build.MODEL}")
                .put("date", dateOf(d))
                .put("apps", appsJson)
                .put("sessions", org.json.JSONArray().apply {
                    val labels = apps.associate { it.pkg to it.label }
                    for (x in sessions.takeLast(1500)) put(JSONObject().put("pkg", x.pkg).put("label", labels[x.pkg] ?: x.pkg).put("start_ms", x.start).put("end_ms", x.end))
                })
                .toString()
            val err = Net.postJson(ctx, "/report", body)
            if (err == null) ok++ else errs += "${dateOf(d)} $err"
        }
        return "使用记录 $ok/$had 天" + if (errs.isEmpty()) "" else "（${errs.first()}）"
    }
}
