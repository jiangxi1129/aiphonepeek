package io.github.aiphonepeek

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 设置页：聊天页网址、上报地址（留空就不上报）、口令。第一次打开自动出现；之后从
 * 「长按返回键」「通知栏的『设置』按钮」「长按桌面图标 → 设置」「网页打不开时的说明页」进入。
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var chat: EditText
    private lateinit var report: EditText
    private lateinit var token: EditText
    private lateinit var status: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun label(s: String) = TextView(this).apply { text = s; textSize = 14f; setPadding(0, dp(16), 0, dp(4)) }
    private fun hint(s: String) = TextView(this).apply { text = s; textSize = 12f; setTextColor(0xFF777777.toInt()) }
    private fun field(value: String, hintText: String, type: Int) = EditText(this).apply {
        setText(value); hint = hintText; inputType = type; setSingleLine(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name) + " · 设置"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(32), dp(20), dp(24)) }
        col.addView(TextView(this).apply { text = getString(R.string.app_name) + " 设置"; textSize = 22f })
        col.addView(hint("数据只会发到下面「上报地址」，不会发给任何别的地方。"))
        // 11/13 息息：「别人怎么知道要弄这几个？」——第一屏就说清楚这三格从哪来
        col.addView(TextView(this).apply {
            text = "第一次用？下面三格要先准备好：聊天页是你本来就有的网页；上报地址和口令来自你自己跑的收数据小服务（server.py，启动时会打印口令）。步骤见说明书「开始之前」：https://github.com/jiangxi1129/aiphonepeek"
            textSize = 13f; setPadding(0, dp(8), 0, dp(4)); autoLinkMask = android.text.util.Linkify.WEB_URLS
        })

        col.addView(label("聊天页网址（壳里打开的网页）"))
        chat = field(Config.chatUrl(this), "https://chat.example.com/", InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT)
        col.addView(chat)

        col.addView(label("上报地址（你跑的 server.py 对外的网址，不带 /report）"))
        report = field(Config.reportUrl(this), "https://peek.example.com", InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT)
        col.addView(report)
        col.addView(hint("http:// 可以用（局域网），但位置会明文传输；公网请用 https://。"))

        col.addView(label("上报口令（server.py 启动时打印的那串）"))
        token = field(Config.token(this), "server.py 打印的「上报口令」", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        col.addView(token)

        col.addView(Button(this).apply { text = "保存并打开"; setOnClickListener { save(thenFinish = true) } })
        col.addView(Button(this).apply { text = "保存并测试上报地址"; setOnClickListener { save(thenFinish = false); test() } })
        col.addView(Button(this).apply {
            text = "重新走一遍权限引导"
            setOnClickListener {
                // 清掉「已问过」标记，回到网页后会从头再问一遍
                getSharedPreferences(Config.PREFS, MODE_PRIVATE).edit().apply {
                    listOf("notif", "loc", "bgloc", "battery").forEach { remove("asked_$it") }
                    remove("usage_asked_at")
                }.apply()
                if (save(thenFinish = true)) Toast.makeText(this@SettingsActivity, "回到网页后会重新询问权限", Toast.LENGTH_SHORT).show()
            }
        })
        status = TextView(this).apply { textSize = 12f; setPadding(0, dp(16), 0, 0)
            text = "上次补报：" + (getSharedPreferences(Config.PREFS, MODE_PRIVATE).getString("last_sync", "") ?: "").ifEmpty { "还没有" } }
        col.addView(status)
        col.addView(hint("版本 ${BuildConfig.VERSION}"))
        setContentView(ScrollView(this).apply { addView(col) })
    }

    /** 校验并保存。成功返回 true。 */
    private fun save(thenFinish: Boolean): Boolean {
        val c = Config.normalize(chat.text.toString(), false)
        // 上报地址可以留空：留空就是不上报，后台服务当场停掉
        val rawReport = report.text.toString().trim()
        val r = if (rawReport.isEmpty()) "" else Config.normalize(rawReport, true)
        if (c == null) { chat.error = "要以 http:// 或 https:// 开头"; return false }
        if (r == null) { report.error = "要以 http:// 或 https:// 开头，或者留空不上报"; return false }
        Config.save(this, c, r, token.text.toString().trim())
        if (r.isEmpty()) {
            stopService(android.content.Intent(this, TrackerService::class.java))
            Toast.makeText(this, "上报地址空着：已停止上报", Toast.LENGTH_SHORT).show()
        }
        if (thenFinish) { setResult(RESULT_OK); finish() }
        return true
    }

    private fun test() {
        status.text = "测试中…"
        val app = applicationContext
        Thread {
            val err = Net.ping(app)
            runOnUiThread { status.text = if (err == null) "连得上（/health 正常）" else "连不上：$err\n（确认服务已启动、地址和口令对）" }
        }.start()
    }
}
