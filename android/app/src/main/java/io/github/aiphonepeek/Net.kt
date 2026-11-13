package io.github.aiphonepeek

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/** 往用户填的上报地址发 JSON；填了口令就带 Authorization: Bearer。 */
object Net {
    private const val TAG = "PeekNet"

    private fun open(ctx: Context, path: String, method: String): HttpURLConnection {
        val conn = URL(Config.reportUrl(ctx) + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        val t = Config.token(ctx)
        if (t.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $t")
        return conn
    }

    /** 成功返回 null，失败返回原因。阻塞，别在主线程调。 */
    fun postJson(ctx: Context, path: String, body: String): String? = try {
        if (Config.reportUrl(ctx).isEmpty()) "没填上报地址" else {
            val conn = open(ctx, path, "POST")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) null else "HTTP $code"
        }
    } catch (e: Exception) {
        Log.w(TAG, "POST $path failed: ${e.message}")
        "${e.javaClass.simpleName}: ${e.message?.take(80) ?: ""}"
    }

    /** 设置页「测试」用：先 GET /health 看连不连得上，再 GET /places 带口令，看口令对不对（/health 不查口令）。成功返回 null。 */
    fun ping(ctx: Context): String? = try {
        val h = open(ctx, "/health", "GET"); val hc = h.responseCode; h.disconnect()
        if (hc !in 200..299) "HTTP $hc" else {
            val p = open(ctx, "/places", "GET"); val pc = p.responseCode; p.disconnect()
            if (pc in 200..299) null else if (pc == 401 || pc == 403) "地址连得上，但口令不对（HTTP $pc）" else "地址连得上，查口令时 HTTP $pc"
        }
    } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message?.take(80) ?: ""}" }
}
