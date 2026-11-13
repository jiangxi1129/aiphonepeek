package io.github.aiphonepeek

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * 壳：一个全屏 WebView，打开你在设置页填的聊天页网址（页面在你自己的服务器上，前端怎么改都不用重装壳）。
 * 比浏览器多出来的本事：每次点开补报 7 天手机使用记录 + 记一次位置；后台每 15 分钟记轨迹。
 * 进设置页：长按返回键 / 通知栏「设置」/ 长按桌面图标 / 网页打不开时的说明页。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var panel: LinearLayout
    private lateinit var panelText: TextView
    private var loadedUrl = ""
    private val errs = ArrayDeque<String>()   // 页面最近的报错，白屏时摆出来方便排查（只在本机显示，不上报）
    private fun note(s: String) { synchronized(errs) { errs.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + s.take(300)); while (errs.size > 12) errs.removeFirst() } }
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var webPerm: PermissionRequest? = null
    private var busy = false   // 正在等某个权限/设置页回来，别叠着弹
    private var settingsOpen = false
    @Volatile private var mainErr = false
    private val prefs by lazy { getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE) }
    private val appName by lazy { getString(R.string.app_name) }

    // 网页的上传框常带 multiple，选图器回的是 ClipData；系统自带的 parseResult 只认单个 getData()，多选会丢，所以自己解析
    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        val uris: Array<Uri>? = if (r.resultCode != RESULT_OK || data == null) null else {
            val clip = data.clipData
            when {
                clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
        }
        fileCb?.onReceiveValue(uris); fileCb = null
    }
    private val askWebPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val req = webPerm; webPerm = null
        if (req != null) {
            val ok = req.resources.filter { (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && res[Manifest.permission.RECORD_AUDIO] != false && has(Manifest.permission.RECORD_AUDIO)) ||
                (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && has(Manifest.permission.CAMERA)) }
            if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
        }
    }
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { busy = false; afterPermChange() }
    private val openSysSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { busy = false; afterPermChange() }
    private val settingsPage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        settingsOpen = false
        if (!Config.isConfigured(this)) finish()   // 第一次没填就退出，不循环弹
    }

    private fun startUrl() = Config.chatUrl(this)
    private fun showSettings() { if (!settingsOpen) { settingsOpen = true; settingsPage.launch(Intent(this, SettingsActivity::class.java)) } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        // 页面空白 / 打不开时盖一层说明：最近的报错 + 重新加载 / 清缓存 / 打开设置
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F5F5F5")); setPadding(48, 160, 48, 48); visibility = View.GONE
        }
        panelText = TextView(this).apply { setTextColor(Color.parseColor("#333333")); textSize = 14f }
        panel.addView(panelText)
        panel.addView(Button(this).apply { text = "重新加载"; setOnClickListener { panel.visibility = View.GONE; web.reload() } })
        panel.addView(Button(this).apply { text = "清掉网页缓存再打开（不会退出登录）"; setOnClickListener { panel.visibility = View.GONE; clearWebCacheAndReload() } })
        panel.addView(Button(this).apply { text = "打开设置（改网址 / 口令）"; setOnClickListener { showSettings() } })
        // 聊天页网址空着时（只想上报、不想登录聊天页）：显示一张本地状态卡，代替网页
        statusView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#FAFAFA")); setPadding(48, 160, 48, 48); visibility = View.GONE
        }
        statusText = TextView(this).apply { setTextColor(Color.parseColor("#333333")); textSize = 15f }
        statusView.addView(statusText)
        // 实测：点了以后卡片一直写「正在补报…」不动、上一轮还在跑时这一下被静默吞掉。现在开没开跑都说一声，跑的时候每秒刷新一次，跑完自己停
        statusView.addView(Button(this).apply { text = "立刻补报一次"; setOnClickListener {
            if (Sync.isRunning()) Toast.makeText(this@MainActivity, "上一轮还在补报，等它跑完", Toast.LENGTH_SHORT).show()
            else if (!Sync.runAsync(this@MainActivity, "manual", force = true)) Toast.makeText(this@MainActivity, "没开始：先在设置里填上报地址", Toast.LENGTH_SHORT).show()
            watchSync()
        } })
        statusView.addView(Button(this).apply { text = "权限清单"; setOnClickListener { startActivity(Intent(this@MainActivity, PermissionsActivity::class.java)) } })
        statusView.addView(Button(this).apply { text = "设置（填聊天页 / 上报地址 / 口令）"; setOnClickListener { showSettings() } })
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(statusView, FrameLayout.LayoutParams(-1, -1).apply { gravity = Gravity.TOP })
        root.addView(panel, FrameLayout.LayoutParams(-1, -1).apply { gravity = Gravity.TOP })
        setContentView(root)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            setGeolocationEnabled(true)
            userAgentString = "$userAgentString AiPhonePeek/${BuildConfig.VERSION}"
        }
        // 聊天里的文件卡（安装包、PDF…）点了要能下载
        web.setDownloadListener { url, ua, disposition, mime, _ ->
            try {
                val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
                val req = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mime); addRequestHeader("User-Agent", ua)
                    if (Config.isChatHost(this@MainActivity, Uri.parse(url))) CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    setTitle(name)
                    setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
                }
                (getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
                android.widget.Toast.makeText(this, "在下载 $name，下完点通知栏打开", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (Config.isChatHost(this@MainActivity, u)) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}   // 外链交给系统浏览器
                return true
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { note("网页进程崩了 crash=${detail.didCrash()}"); recreate(); return true }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) { mainErr = true; note("打不开 ${err.errorCode} ${err.description}"); showPanel("页面打不开") }
            }
            override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, resp: WebResourceResponse) {
                if (req.isForMainFrame && resp.statusCode >= 500) { note("HTTP ${resp.statusCode} ${req.url}"); showPanel("服务器回了 ${resp.statusCode}") }
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { mainErr = false; note("开始加载 $url") }
            override fun onPageFinished(view: WebView, url: String) {
                note("加载完 $url")
                if (!mainErr) panel.visibility = View.GONE
                view.postDelayed({ blankCheck(view) }, 10_000)   // 加载完 10 秒还是空白，就把说明摆出来
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) note("JS ERROR: ${m.message()} @${m.sourceId().substringAfterLast('/')}:${m.lineNumber()}")
                return false
            }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                // 自己拼选择器：accept 里有好几种（image/*,.pdf,.txt）时系统那个只认第一种
                val mimes = p.acceptTypes.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.map {
                    when { it.contains("/") -> it; it == ".pdf" -> "application/pdf"; it == ".txt" || it == ".md" -> "text/*"; else -> "*/*" }
                }.distinct()
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (mimes.size == 1) mimes[0] else "*/*"
                    if (mimes.size > 1 && "*/*" !in mimes) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                    if (p.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try { pickFile.launch(Intent.createChooser(i, "选文件")); true }
                catch (_: Exception) { fileCb = null; false }
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (!Config.isChatHost(this@MainActivity, request.origin)) { request.deny(); return@runOnUiThread }
                    val need = ArrayList<String>()
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) need += Manifest.permission.RECORD_AUDIO
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) need += Manifest.permission.CAMERA
                    if (need.isEmpty()) { request.deny(); return@runOnUiThread }
                    webPerm?.deny(); webPerm = request
                    askWebPerm.launch(need.toTypedArray())
                }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                val u = Uri.parse(origin)
                if (!Config.isChatHost(this@MainActivity, u) || !Locator.hasPermission(this@MainActivity)) { cb.invoke(origin, false, false); return }
                if (u.scheme.equals("https", ignoreCase = true)) { cb.invoke(origin, true, false); return }
                // http 明文页面在公共网络上能被换掉内容，换掉的脚本照样同源，所以每次都问一下，不自动给
                AlertDialog.Builder(this@MainActivity).setTitle("给这个页面定位？")
                    .setMessage("$origin 是 http 明文连接，在公共 Wi-Fi 上内容可能被人改过。只在自己家局域网里才建议允许。")
                    .setCancelable(false)
                    .setPositiveButton("这次允许") { _, _ -> cb.invoke(origin, true, false) }
                    .setNegativeButton("不给") { _, _ -> cb.invoke(origin, false, false) }
                    .show()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else moveTaskToBack(true) }
        })
    }

    /** 长按返回键 → 设置页。（手势导航没有返回键的手机用通知栏或桌面图标长按。） */
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { showSettings(); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    /** 看页面是不是白的；白的就摆说明，之后每 3 秒复查，有内容了自己收起。 */
    private fun blankCheck(view: WebView) {
        view.evaluateJavascript("(function(){var b=document.body;return b?(b.innerText||'').trim().length:-1})()") { raw ->
            val txt = raw?.toIntOrNull() ?: -1
            if (txt <= 0 && Config.isChatHost(this, Uri.parse(view.url ?: ""))) {
                note("页面是空白的（innerText 长度 $txt）")
                if (panel.visibility != View.VISIBLE) showPanel("页面是空白的")
                view.postDelayed({ blankCheck(view) }, 3_000)
            } else if (panel.visibility == View.VISIBLE && !mainErr) {
                panel.visibility = View.GONE
            }
        }
    }

    private fun showPanel(why: String) = runOnUiThread {
        val list = synchronized(errs) { errs.joinToString("\n") }
        panelText.text = "$why（$appName ${BuildConfig.VERSION}）\n\n$list\n"
        panel.visibility = View.VISIBLE
    }

    /** 清掉网页的离线缓存（service worker 和它的缓存），cookie 不动，所以不用重新登录。 */
    private fun clearWebCacheAndReload() {
        web.clearCache(true)
        web.evaluateJavascript("(async function(){try{var rs=await navigator.serviceWorker.getRegistrations();for(var r of rs)await r.unregister();var ks=await caches.keys();for(var k of ks)await caches.delete(k)}catch(e){}return 'ok'})()") {
            web.postDelayed({ web.loadUrl(startUrl()) }, 800)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (!Config.isConfigured(this)) { showSettings(); return }
        web.onResume()
        if (!Config.hasChat(this)) {
            // 小葱 r4 P3：之前网页的错误面板别压在状态卡上
            panel.visibility = View.GONE
            statusView.visibility = View.VISIBLE
            if (loadedUrl.isNotEmpty()) { loadedUrl = ""; web.loadUrl("about:blank") }
            refreshStatus()
        } else {
            statusView.visibility = View.GONE
            // 设置页改了网址就重新加载；第一次也走这里。接口白名单跟着网址换
            if (loadedUrl != startUrl()) { loadedUrl = startUrl(); installBridge(); web.loadUrl(loadedUrl) }
        }
        Sync.runAsync(this, "open")
        watchSync()
        if (Locator.hasPermission(this)) TrackerService.start(this)
        if (!busy) nextSetupStep()
    }

    override fun onPause() { if (::statusText.isInitialized) statusText.removeCallbacks(syncTick); web.onPause(); CookieManager.getInstance().flush(); super.onPause() }

    private fun afterPermChange() {
        if (Locator.hasPermission(this)) TrackerService.start(this)
        Sync.runAsync(this, "setup", force = true)
        nextSetupStep()
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun asked(k: String) = prefs.getBoolean("asked_$k", false)
    private fun markAsked(k: String) = prefs.edit().putBoolean("asked_$k", true).apply()

    // 小蒜 r1 P2：原来跟 120 秒就停，慢的那轮会一直停在「正在…」。现在只要还在跑就一直跟（头两分钟每秒，之后每 5 秒），跑完再刷一次停下
    // 小蒜 r1 P3：只在状态卡看得见时跟，onPause 摘掉，别让离开的页面在后台空转
    private val syncTick = object : Runnable {
        override fun run() {
            refreshStatus()
            if (Sync.isRunning()) statusText.postDelayed(this, if (System.currentTimeMillis() - Sync.startedAt < 120_000) 1000 else 5000)
        }
    }
    private fun watchSync() { if (!::statusText.isInitialized || statusView.visibility != View.VISIBLE) return; statusText.removeCallbacks(syncTick); statusText.postDelayed(syncTick, 300) }

    private lateinit var statusView: LinearLayout
    private lateinit var statusText: TextView
    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val perm = if (PermissionsActivity.allOk(this)) "权限都开好了" else if (PermissionsActivity.missingRequired(this)) "还有必需的权限没开（使用情况访问 / 位置），点「权限清单」" else "必需的都开了，还有几项建议开"
        val running = if (Sync.isRunning() && Sync.stage.isNotEmpty()) "\n→ ${Sync.stage}，已 ${(System.currentTimeMillis() - Sync.startedAt) / 1000} 秒" else ""
        statusText.text = "聊天页没填，这里只管上报。\n\n${Sync.summary(this)}$running\n\n$perm\n\n（一天里用了不到 1 分钟的 App、不到 30 秒的零碎片段不记。）"
    }

    // 11/13：原来一项一项弹对话框要权限，用户嫌烦还得自己找系统页面。改成一页权限清单：第一次打开、或缺必需的（使用情况访问、位置）时自动打开，每次启动最多弹一次
    private var permsShownThisLaunch = false
    private fun nextSetupStep() {
        if (busy || isFinishing || settingsOpen || permsShownThisLaunch) return
        // 小葱 r4 P2：上报关着时那两项权限没用处，别每次启动都催
        val missing = Config.reportEnabled(this) && PermissionsActivity.missingRequired(this)
        if (missing || !prefs.getBoolean("perms_seen", false)) {
            permsShownThisLaunch = true
            prefs.edit().putBoolean("perms_seen", true).apply()
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    /** 旧的逐项引导（11/13 起不用了，留着备查）。 */
    @Suppress("unused")
    private fun oldSetupStep() {
        if (busy || isFinishing || settingsOpen) return
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS) && !asked("notif")) {
            markAsked("notif"); busy = true; askPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)); return
        }
        if (!Locator.hasPermission(this) && !asked("loc")) {
            markAsked("loc")
            explain("记位置", "接下来要位置权限。位置只会发到你在设置里填的上报地址，你随时能在手机设置里关掉。") {
                askPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }; return
        }
        if (Locator.hasPermission(this) && !Locator.hasBackground(this) && !asked("bgloc")) {
            markAsked("bgloc")
            explain("后台也记位置", "下一页请选「始终允许」，不然 App 一退到后台就记不了轨迹。") {
                askPerms.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }; return
        }
        if (!UsageReader.hasAccess(this) && System.currentTimeMillis() - prefs.getLong("usage_asked_at", 0) > 24 * 3600_000L) {
            prefs.edit().putLong("usage_asked_at", System.currentTimeMillis()).apply()
            explain("手机使用记录", "下一页找到「$appName」，把「允许访问使用情况」打开。这样每次点开都会把最近 7 天的使用记录补报给你的服务。") {
                openSysSettings.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }; return
        }
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !asked("battery")) {
            markAsked("battery")
            explain("别让手机把它关掉", "下一页选「允许」，后台记录就不容易被省电杀掉。（各品牌还有单独的自启动 / 后台限制，见 README。）") {
                @SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                openSysSettings.launch(i)
            }; return
        }
    }

    private fun explain(title: String, msg: String, go: () -> Unit) {
        busy = true
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setCancelable(false)
            .setPositiveButton("好") { _, _ -> try { go() } catch (_: Exception) { busy = false; nextSetupStep() } }
            .setNegativeButton("先不") { _, _ -> busy = false; nextSetupStep() }
            .show()
    }

    private var bridgeInstalled = false

    /**
     * 给前端用的小口子 window.AiPhonePeek：postMessage("status") 回权限和上次补报，"syncNow" 立刻补报一次，"version" 回版本。
     * 用 WebMessageListener 而不是 addJavascriptInterface：后者会注入所有 frame，聊天页里嵌的外站 iframe 也能调。
     * 这里只放聊天页 origin，且只认主框架。系统 WebView 太老不支持就不提供接口。
     */
    private fun installBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { note("WebView 太老，不提供 AiPhonePeek 接口"); return }
        if (bridgeInstalled) { WebViewCompat.removeWebMessageListener(web, "AiPhonePeek"); bridgeInstalled = false }
        val rule = Config.chatOriginRule(this) ?: return
        try {
            WebViewCompat.addWebMessageListener(web, "AiPhonePeek", setOf(rule)) { _, msg, sourceOrigin, isMainFrame, reply ->
                if (!isMainFrame || !Config.isChatHost(this, sourceOrigin)) return@addWebMessageListener
                when (msg.data) {
                    "version" -> reply.postMessage(BuildConfig.VERSION)
                    "status" -> reply.postMessage(statusJson())
                    "syncNow" -> Sync.runAsync(this, "manual", force = true)
                }
            }
            bridgeInstalled = true
        } catch (e: Exception) { note("AiPhonePeek 接口没装上：${e.message}") }
    }

    private fun statusJson(): String = JSONObject()
        .put("version", BuildConfig.VERSION)
        .put("usageAccess", UsageReader.hasAccess(this))
        .put("location", Locator.hasPermission(this))
        .put("backgroundLocation", Locator.hasBackground(this))
        .put("batteryWhitelisted", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
        .put("lastSync", prefs.getString("last_sync", "") ?: "")
        .toString()
}
