package io.github.aiphonepeek

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 权限清单：一页列全要的权限，每项一行（名字、为什么要、已开/未开、去打开）。
 * onResume 刷新状态；全开了显示「都好了」。第一次启动、或缺使用情况访问 / 位置时 MainActivity 会自动打开这页。
 */
class PermissionsActivity : AppCompatActivity() {
    companion object {
        const val PREF_INTRO_DONE = "perm_intro_done"

        private fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        fun notifOk(ctx: Context) = Build.VERSION.SDK_INT < 33 || has(ctx, Manifest.permission.POST_NOTIFICATIONS)
        fun batteryOk(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        /** 必需项：使用情况访问、位置（前台）。 */
        fun missingRequired(ctx: Context) = !UsageReader.hasAccess(ctx) || !Locator.hasPermission(ctx)
        fun allOk(ctx: Context) = !missingRequired(ctx) && Locator.hasBackground(ctx) && notifOk(ctx) && batteryOk(ctx)
    }

    private class Row(val name: String, val why: String, val ok: () -> Boolean, val partial: () -> String?, val go: () -> Unit) {
        lateinit var status: TextView
        lateinit var btn: Button
    }

    private val rows = ArrayList<Row>()
    private lateinit var header: TextView
    private lateinit var backBtn: Button
    private var askedAt = 0L
    private var lastState: String? = null

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        // 瞬间就回来了且没给：系统不再弹窗（之前拒绝过），带用户去本 App 的系统设置页手动开
        if (res.values.any { !it } && System.currentTimeMillis() - askedAt < 600) {
            Toast.makeText(this, "系统没弹窗，去「权限」里手动打开", Toast.LENGTH_LONG).show()
            openAppDetails()
        }
    }

    private fun ask(vararg perms: String) { askedAt = System.currentTimeMillis(); askPerms.launch(arrayOf(*perms)) }

    private fun openAppDetails() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name) + " · 权限清单"
        getSharedPreferences(Config.PREFS, MODE_PRIVATE).edit().putBoolean(PREF_INTRO_DONE, true).apply()

        rows += Row("使用情况访问 · 必需", "用来补报最近 7 天手机用了什么。点「去打开」会跳到系统那页，把开关打开再按返回。",
            { UsageReader.hasAccess(this) }, { null }) {
            try {
                // 带上包名，新一点的系统会直接定位到本 App
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Exception) { openAppDetails() }
            }
        }

        rows += Row("位置（始终允许）· 必需", "记你去过哪。先开位置，再选「始终允许」，不然退到后台就记不了轨迹。",
            { Locator.hasPermission(this) && Locator.hasBackground(this) },
            { if (Locator.hasPermission(this) && !Locator.hasBackground(this)) "只开了一半，还差「始终允许」" else null }) {
            if (!Locator.hasPermission(this)) ask(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            else ask(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= 33) rows += Row("通知", "常驻通知要用，不开的话系统可能把后台记录藏起来。",
            { notifOk(this) }, { null }) { ask(Manifest.permission.POST_NOTIFICATIONS) }

        rows += Row("忽略电池优化", "别让手机省电把后台记录杀掉。弹出来选「允许」。",
            { batteryOk(this) }, { null }) {
            try {
                @SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(i)
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { openAppDetails() }
            }
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(32), dp(20), dp(24)) }
        col.addView(TextView(this).apply { text = "权限清单"; textSize = 22f })
        header = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        col.addView(header)
        for (r in rows) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, dp(6)) }
            box.addView(TextView(this).apply { text = r.name; textSize = 17f; setTypeface(typeface, Typeface.BOLD) })
            box.addView(TextView(this).apply { text = r.why; textSize = 13f; setTextColor(0xFF777777.toInt()) })
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            r.status = TextView(this).apply { textSize = 14f }
            line.addView(r.status, LinearLayout.LayoutParams(0, -2, 1f))
            r.btn = Button(this).apply { text = "去打开"; setOnClickListener { try { r.go() } catch (_: Exception) { openAppDetails() } } }
            line.addView(r.btn)
            box.addView(line)
            col.addView(box)
        }
        backBtn = Button(this).apply { setOnClickListener { goBack() } }
        col.addView(backBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        col.addView(TextView(this).apply {
            text = "各品牌还有单独的自启动 / 后台限制，见 README「各厂商的省电 / 自启动开关」。"
            textSize = 12f; setTextColor(0xFF777777.toInt()); setPadding(0, dp(12), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun goBack() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 权限状态变了：位置有了就把后台记录拉起来，并补报一次
        val state = rows.joinToString(",") { it.ok().toString() } + UsageReader.hasAccess(this) + Locator.hasPermission(this)
        if (lastState != null && lastState != state) {
            if (Locator.hasPermission(this)) TrackerService.start(this)
            Sync.runAsync(this, "setup", force = true)
        }
        lastState = state
    }

    private fun refresh() {
        var left = 0
        for (r in rows) {
            val ok = r.ok()
            if (!ok) left++
            r.status.text = if (ok) "✓ 已开" else "✗ 未开" + (r.partial()?.let { "（$it）" } ?: "")
            r.status.setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
            r.btn.isEnabled = !ok
            r.btn.text = if (ok) "已开" else "去打开"
        }
        header.text = if (left == 0) "都好了 🎉" else "还差 $left 项" + if (missingRequired(this)) "（使用情况访问和位置是必需的）" else ""
        backBtn.text = if (left == 0) "回到聊天页" else "先不弄了，回到聊天页"
        if (!Config.hasChat(this)) backBtn.text = backBtn.text.toString().replace("聊天页", "主页")
    }
}
