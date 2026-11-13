package io.github.aiphonepeek

import android.content.Context
import android.net.Uri

/** 用户在设置页填的三样东西，存在 SharedPreferences。数据只会发到这里填的地址。 */
object Config {
    const val PREFS = "aiphonepeek"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun chatUrl(ctx: Context): String = p(ctx).getString("chat_url", "") ?: ""
    fun reportUrl(ctx: Context): String = p(ctx).getString("report_url", "") ?: ""
    fun token(ctx: Context): String = p(ctx).getString("token", "") ?: ""

    /** 聊天页填了就能用；上报地址留空 = 不上报（后台服务也会停）。 */
    fun isConfigured(ctx: Context) = chatUrl(ctx).isNotEmpty()
    fun reportEnabled(ctx: Context) = reportUrl(ctx).isNotEmpty()

    fun save(ctx: Context, chat: String, report: String, token: String) {
        p(ctx).edit().putString("chat_url", chat).putString("report_url", report).putString("token", token).apply()
    }

    /** 整理用户输入：去空白；必须是 http(s):// 开头且有主机名；上报地址去掉末尾的 /。不合格返回 null。 */
    fun normalize(raw: String, stripSlash: Boolean): String? {
        val s = raw.trim()
        val u = try { Uri.parse(s) } catch (_: Exception) { return null }
        if (u.scheme != "http" && u.scheme != "https") return null
        if (u.host.isNullOrEmpty()) return null
        return if (stripSlash) s.trimEnd('/') else s
    }

    /** 聊天页的 origin，给 WebMessageListener 当白名单用：scheme://host[:port]。 */
    fun chatOriginRule(ctx: Context): String? {
        val c = Uri.parse(chatUrl(ctx))
        val s = c.scheme?.lowercase() ?: return null
        val h = c.host ?: return null
        return if (c.port != -1) "$s://$h:${c.port}" else "$s://$h"
    }

    private fun port(u: Uri): Int = if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80

    /**
     * 这个地址算不算「聊天页自己」：协议、主机名、端口三样都一样（同源）。
     * 不是的话外链交给浏览器，也不给它麦克风和定位。
     * 只比主机名不够：同主机的 http:// 链接在公共 Wi-Fi 上能被换掉内容。
     */
    fun isChatHost(ctx: Context, u: Uri?): Boolean {
        val h = u?.host ?: return false
        val s = u.scheme?.lowercase() ?: return false
        if (s != "http" && s != "https") return false
        val c = Uri.parse(chatUrl(ctx))
        if (s != c.scheme?.lowercase()) return false
        if (!h.equals(c.host, ignoreCase = true)) return false
        return port(u) == port(c)
    }
}
