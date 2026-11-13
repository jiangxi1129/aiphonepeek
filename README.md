# aiphonepeek

**一个安卓 WebView 壳：把你自己的网页聊天前端包成 App，每次打开就把过去 7 天的手机使用记录（和位置）补报给你自己的服务，让你的 AI 能看到「她/他现在在哪、今天手机用了什么」。**

给谁用：**有自己网页聊天前端的人**——自建页面、SillyTavern、LobeChat、Open WebUI 之类，能拿到一个网址的都行。只用官方 App（没有网页入口）的用不了，壳里装的就是你的网页。

*English, short:* an Android WebView shell for **people who run their own web chat UI** (SillyTavern, LobeChat, Open WebUI, a home-made page…). Every time you open it, it back-fills the last 7 days of Android UsageStats (per-day totals + a sessions timeline) and a location fix to **a server you host**, which exposes `GET /footprint` (plain text for an LLM) and an optional MCP tool. It does not fight OEM battery killers — it is designed so that being killed doesn't matter. Data goes only to the URL you type in. MIT licensed. Won't work if you only use an official chat app.

---

## 为什么不怕后台被杀

安卓厂商（vivo、小米、华为、OPPO……，可以去 [dontkillmyapp.com](https://dontkillmyapp.com) 看各家打分）爱杀后台。市面上「让 AI 看到你手机使用情况」的方案——Tasker、Termux + 无线 ADB、手机本地 MCP App——都押在「手机上有个东西一直活着」，一被杀就没数据。

这里的思路是：**别跟后台保活拼。** 把读使用记录这件事塞进你每天打开几十次的那个 App 里——你自己的聊天页，用一个安卓壳（WebView）包起来：

- **每次打开**，就把过去 7 天的使用记录（系统 UsageStats 事件）**按天整份补报一遍**；
- 后台恰好活着时，顺手每 15 分钟记一次位置和当天使用；
- **被杀了也没关系**，下次打开全补齐（使用记录是系统自己存的，我们只是读；唯一补不回来的是被杀期间的位置轨迹）。

### 一个容易踩的坑：时长要按「页面」算，不能按「App」算

UsageStats 给的是 Activity 级别的 `RESUMED / PAUSED / STOPPED` 事件。如果按「包」记开关，同一个 App 里从 A 页跳到 B 页时，事件顺序是 *A 暂停 → B 恢复 → A 停止*，A 的「停止」会把 B 刚开的那段掐掉——**页面翻得多的 App（信息流、聊天类）会漏掉一大半**（实测系统自带统计 54 分钟，按包算只有 5 分钟）。

`UsageReader.kt` 的做法：对每个包维护「当前在前台的 Activity 集合」，**只要还有一个 Activity 在前台就算在用，最后一个离开才收账**。一天里总共用了不到 1 分钟的 App 不记、单段不到 30 秒的零碎片段不进时间线（锁屏一下、划过去又划回来这种不算在用）；同一个 App 中断不到 30 秒的合并成一段，上报时同时带上 `sessions` 时间线（几点到几点在用哪个 App）。也不用 `queryAndAggregateUsageStats`（它会把时长算成两三倍）。

---

## 开始之前：你要准备三样东西

App 第一次打开会让你填三格。它们都得你自己先准备好：

| App 里那一格 | 它是什么 | 从哪来 |
| --- | --- | --- |
| 聊天页网址 | 你平时跟 AI 聊天的那个网页 | 你本来就有（自建页面、SillyTavern、LobeChat、Open WebUI…的网址） |
| 上报地址 | 收手机数据的一个小服务的网址 | 你自己跑 `server/server.py`，再让手机连得上它（下面第 2 步） |
| 上报口令 | 防止别人往你的服务里乱写、读走你的位置 | `server.py` 第一次启动时自动生成并打印出来 |

最少的上手步骤（一台装了 Python 3 的电脑或服务器就行）：

```bash
# 1. 拿到代码，跑起收数据的服务；它会打印「上报口令：xxxx」，记下来
git clone https://github.com/jiangxi1129/aiphonepeek && cd aiphonepeek
python3 server/server.py --data ~/aiphonepeek-data

# 2. 让手机连得上它，任选一种：
#    a) 有自己的域名 / 反代（Caddy、Nginx…）：把 127.0.0.1:8787 反代成一个 https 网址，那个网址就是「上报地址」
#    b) 先试试：另开一个终端跑 Cloudflare 的临时隧道（要先装 cloudflared），
#       它会打印一个 https://xxxx.trycloudflare.com，这就是「上报地址」（每次重开都会变，长期用请选 a）
cloudflared tunnel --url http://127.0.0.1:8787

#    c) 只是在电脑上的安卓模拟器里试：上报地址直接填 http://10.0.2.2:8787（模拟器里的 10.0.2.2 就是这台电脑）
# 3. 手机装 APK（Releases 页下载），打开后填上面三样，点「保存并测试上报地址」显示「连得上，口令也对」就好了
# 4. 让你的 AI 来读：GET {上报地址}/footprint（带口令），或者用 mcp/ 里的 MCP 工具
```

---

## 目录

```
android/   安卓壳（Kotlin，包名 io.github.aiphonepeek）
server/    收数据的小服务（server.py，单文件、只用标准库）
mcp/       可选：把 /footprint 包成 MCP 工具（peek_mcp.py）
.github/   打 tag 自动出 release APK
```

## 怎么装

### 1. 先把服务跑起来（见下文「服务」）

壳要往一个你自己的地址报数据，所以先有服务：局域网/反代到公网都行，最省事的做法见上面「开始之前」。口令不手动指定会自动生成并打印出来，公网请务必用 https。

### 2. 下载 APK

到本仓库的 Releases 下载 APK 安装；或者自己编译（见下文「自己编译」）。
Release 包如果文件名带 `-debugsigned`，说明仓库作者没配签名，是 debug 签名，能用，但以后换签名需要卸载重装。

### 3. 填两个地址

第一次打开会出现设置页：

| 项 | 例子 | 说明 |
| --- | --- | --- |
| 聊天页网址 | `https://chat.example.com/` | 壳里全屏打开的网页。只有同源（协议、主机名、端口都一样）的页面留在壳里，其它外链交给系统浏览器；也只有它能用麦克风/摄像头/定位和 `AiPhonePeek` 接口。建议用 https；填 `http://` 时（比如家里局域网），每次网页要定位都会弹窗问你 |
| 上报地址 | `https://peek.example.com` | 收数据的服务，**不带** `/report`。壳会 POST 到 `{上报地址}/report` 和 `{上报地址}/location`。留空 = 不上报，后台服务会停 |
| 上报口令 | `server.py 打印的那串` | 在请求头带 `Authorization: Bearer <口令>`；服务端没指定时第一次启动自动生成，存在数据目录的 `token` 文件里 |

「保存并测试上报地址」会先请求 `{上报地址}/health`（看连不连得上），再带口令请求 `/places`（看口令对不对），两样分开告诉你。

**之后怎么再打开设置页**（任选）：
- **长按返回键**（有实体/三键导航的手机）；
- **下拉通知栏，点常驻通知上的「设置」按钮**（手势导航的手机用这个）；
- **长按桌面图标 → 「设置」**；
- 网页打不开 / 空白时，自动出现的说明页上有「打开设置」按钮。

### 换成你自己的名字和图标

不用装 Android Studio，在 GitHub 网页上就能出一个自己的版本：

1. 右上角 **Fork** 一份到你自己的账号。
2. 进你那份仓库的 **Actions** 页，左边点 **build-release-apk**，右边 **Run workflow**，填两个框：
   - App 名字：桌面图标下面显示的字；
   - 图标图片的网址：一张方形的 PNG/JPG（不方的会居中裁成正方形）。
3. 跑完（两三分钟）点进这次运行，最下面 **Artifacts** 里下载 apk。

想每次发版都用同一个图标，就把图片命名成 `android/custom-icon.png` 放进仓库，名字写进 `android/gradle.properties` 的 `appName`，以后推 `v*` 标签自动出的包都是你的样子。

本地编译也一样：`-PappName="我的壳"`，图标换 `android/custom-icon.png` 再按上面 Actions 里的做法生成，或者直接改 `android/app/src/main/res/`。

> 包名没变（都是 `io.github.aiphonepeek`），所以一台手机上只能装一个版本；换名字换图标后直接覆盖安装即可（签名要一致）。

### 4. 开权限

填完设置页回到主页，会自动打开一页「权限清单」（也可以在设置页点「权限清单」直接进去）。必需的两项排在最前面，每项一行，写着为什么要、开没开，旁边一个「去打开」按钮，点了直接跳到系统里那一页，开完回来自动打勾：

1. **使用情况访问**（必需）——没有它就没有使用记录。这一项安卓规定只能你自己在系统设置里打开，App 弹不出来，所以按钮会直接带你过去；
2. **位置**（必需）→ 再选「**始终允许**」，后台才记得了轨迹；
3. 通知（Android 13+，常驻通知要用）；
4. 忽略电池优化（选「允许」）。

缺必需项时每次打开 App 都会提醒一次；之后随时可以在设置页点「权限清单」回来看。

**聊天页可以先空着**：只想试上报的话，设置页只填上报地址和口令就行，主界面会显示一张状态卡：使用记录和位置各自上次报了什么、权限开没开，还有「立刻补报一次」按钮。点了以后卡上会写正在做哪一步、已经几秒（取位置在室内可能要等十几秒）。

### 5. 各厂商的省电 / 自启动开关

只要你**每天会打开几次**，不开这些也能工作（靠的是「每次打开补报」）。想让后台 15 分钟位置也尽量不断，再按厂商设一下。**以下是常见路径，各版本系统的菜单名可能不同，找不到就在设置里搜「电池」「自启动」「后台」。**

- **vivo / iQOO（OriginOS / Funtouch OS）**：设置 → 电池 → 后台耗电管理 → 找到本 App → 选「允许后台高耗电」；设置 → 应用与权限 → 权限管理 → 自启动 → 打开本 App。最近任务里把本 App 卡片下拉「加锁」。
- **小米 / Redmi（MIUI / HyperOS）**：设置 → 应用设置 → 应用管理 → 本 App → 省电策略 → 选「无限制」；同页「自启动」打开；最近任务里长按本 App 卡片 → 锁定。
- **华为 / 荣耀（EMUI / HarmonyOS / MagicOS）**：设置 → 应用和服务 → 应用启动管理 → 找到本 App，关掉「自动管理」，手动打开「自启动」「关联启动」「后台活动」三项；设置 → 电池 → 本 App（或「耗电保护」）里允许后台高耗电。
- **OPPO / 一加 / realme（ColorOS / OxygenOS）**：设置 → 电池 → 本 App → 允许后台运行 / 允许自启动；设置 → 应用管理 → 应用列表 → 本 App → 耗电管理 → 允许后台运行；最近任务里下拉卡片加锁。

> 开机自启：壳会监听开机广播，但 **Android 14+ 不允许开机广播直接启动位置类前台服务**，这一步可能被系统拒绝；那就等你下一次点开 App 时接上。

## 服务（server/）

单文件，Python 3.8+，**无第三方依赖**：

```bash
PEEK_TOKEN=换成你自己的一长串 python3 server/server.py --host 127.0.0.1 --port 8787 --data ~/aiphonepeek-data
```

数据目录建议放在仓库外面。放在仓库里也行，根目录的 `.gitignore` 已经忽略了 `data/`，但 fork 之后自己改忽略规则时小心别把位置记录提交上去。

| 参数 / 环境变量 | 说明 |
| --- | --- |
| `--host` / `PEEK_HOST` | 监听地址，默认 `127.0.0.1`。要给手机访问就放到反代（Caddy、Nginx、Cloudflare Tunnel…）后面并上 https |
| `--port` / `PEEK_PORT` | 默认 8787 |
| `--data` / `PEEK_DATA` | 数据目录，默认 `./data`（建议放仓库外）：`usage.json`、`locations.jsonl`、`named_places.json`、`regeo_cache.json` |
| `--token` / `PEEK_TOKEN` | **口令**。不给就第一次启动时自动生成一个，存进数据目录的 `token` 文件、以后一直用它（想换就删掉这个文件）。除 `/health` 外都要 `Authorization: Bearer <口令>`。只在本机自己试用可以加 `--no-token`，它只能配本机地址；前面挂了反代或隧道就别用 |
| `--amap-key` / `AMAP_KEY` | **可选**高德 Web 服务 key。配了会把 WGS-84 坐标换成 GCJ-02 再反查地名，**这意味着坐标会发给高德**（结果按坐标缓存，十米内问过的点不再问）；没配就显示坐标，不联网 |
| `--who` / `PEEK_WHO` | 给 AI 读的文字里怎么称呼机主，默认「用户」 |

接口：

```bash
# 壳上报（每个请求都带口令）
T="Authorization: Bearer $PEEK_TOKEN"
curl -X POST http://127.0.0.1:8787/report   -H "$T" -H 'Content-Type: application/json' \
  -d '{"source":"test","date":"2026-01-01","apps":{"com.example":{"label":"Example","total_sec":1800}},"sessions":[{"pkg":"com.example","label":"Example","start_ms":1767225600000,"end_ms":1767227400000}]}'
curl -X POST http://127.0.0.1:8787/location -H "$T" -H 'Content-Type: application/json' \
  -d '{"latitude":31.23,"longitude":121.47,"accuracy_m":20,"timestamp_ms":1767225600000,"battery_level":80,"charging":false,"trigger":"open"}'

# AI 来查：现在在哪、今天待过哪、手机用了什么、最近的切换经过；返回 JSON，其中 text 字段是给 AI 读的一段话
curl -H "$T" 'http://127.0.0.1:8787/footprint?date=2026-01-01'

# 给地方起名（名字 150 米内生效；改名/删名用 place_id，name 留空即删）
curl -X POST http://127.0.0.1:8787/places/name -H "$T" -H 'Content-Type: application/json' -d '{"lat":31.23,"lng":121.47,"name":"家"}'
```

- `/report` 按「天」整份覆盖，所以壳可以放心反复补报。`date` 必填，`apps` 不能是空的，空请求不会把哪天清掉。
- 位置轨迹保留 30 天，使用记录保留 90 天，启动时和读取时都按这个截止，过期的读不到；「今天」按服务器本机时区算。
- 停留：连续几笔都在 150 米内、待够 10 分钟才算一处；最后一段总会给（那是「现在」）。
- 没有写数据库、没有后台线程，数据都是普通 JSON / JSONL，随时可以自己看、自己删。

## MCP（可选，mcp/）

把 `/footprint` 包成一个只读 MCP 工具 `phone_footprint(date="")`，让 Claude Desktop 或其它 MCP 客户端直接问「她现在在哪、今天手机用了什么」。需要 Python 3.10+：

```bash
cd mcp && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
```

Claude Desktop 的 `claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "aiphonepeek": {
      "command": "/绝对路径/mcp/.venv/bin/python",
      "args": ["/绝对路径/mcp/peek_mcp.py"],
      "env": { "PEEK_URL": "http://127.0.0.1:8787", "PEEK_TOKEN": "和服务端一致，没设就删掉这行" }
    }
  }
}
```

（依赖钉在 `mcp<2`，因为 2.x 把 `FastMCP` 改名了。）

## 自己编译

需要 JDK 17、Android SDK（platform 35）、Gradle 8.7：

```bash
cd android
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/Android/sdk
gradle assembleDebug                 # → app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease -PuseMirror=false   # 海外直连官方源；默认走阿里云镜像（国内快）
```

- 应用名：`-PappName="..."`；版本：`-PversionName=1.0.1 -PversionCode=2`。
- 发布签名：设环境变量 `KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD`，或写 `android/keystore.properties`（`storeFile= storePassword= keyAlias= keyPassword=`，已在 `.gitignore`）。都没有就用 debug 签名。**仓库里没有、也不要提交任何签名钥匙。**
- GitHub Actions：推 `v*` 标签自动出包挂 Release；在仓库 secrets 配 `KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD` 就用你的钥匙签名，没配就出 debug 签名包（文件名带 `-debugsigned`）。见 `.github/workflows/build.yml` 开头注释。

## 隐私

- 壳的数据**只发到你在设置页填的「上报地址」**，没有任何第三方统计、没有内置的远程地址。
- 服务端默认不联网；只有你配了 `AMAP_KEY`，才会把坐标发给高德换地名。
- 发出去的东西：每天各 App 的前台时长和时间线（含 App 名与包名）、位置坐标 + 精度、电量与是否在充电、手机型号。聊天页本身的内容壳不读也不发。
- 上报地址、口令存在本 App 的 SharedPreferences（未加密，`allowBackup=false` 不会进系统备份）。
- 允许 `http://` 地址是为了局域网自建方便；用 `http://` 时位置和口令是明文，公网请只用 https。
- 想停：关掉手机里的位置权限 / 使用情况访问，或卸载，或在设置页清空上报地址。服务端的数据就是你本机目录里的几个文件。
- 壳给网页留了一个口子 `window.AiPhonePeek`，只注入聊天页那个 origin 的主框架，页面里嵌的外站 iframe 拿不到（系统 WebView 太老不支持时就不提供）。用法：

  ```js
  AiPhonePeek.onmessage = e => console.log(e.data)   // status 回一段 JSON，version 回版本号
  AiPhonePeek.postMessage("status")    // 权限、上次补报
  AiPhonePeek.postMessage("syncNow")   // 立刻补报一次
  ```

## 已知限制

- 位置轨迹在后台被杀的那段是缺的（使用记录能补）。
- 使用记录依赖系统保留事件，大多数系统只保留几天；7 天是上限，部分系统更短。
- 页面白屏时的说明页只显示在本机，不会上报。
- 没在 Google Play 上架的打算：`QUERY_ALL_PACKAGES`、后台位置这类权限上架会被审核卡住，这是个自用工具。

## 想加别的功能

这个仓库只做三件事：手机用了什么、人在哪、电量，做到稳就停了。截图、通知、提醒、到家播报这些我们家没加，也不打算加进来。

每家人和 AI 的相处方式不一样，想要什么、不想要什么，该由手机的主人自己定。需要更多功能就 fork 一份，照着自家的约定改。壳是普通的 Android 工程，服务端只用 Python 标准库，加一个上报字段、加一个接口都不难。

## License

MIT，见 [LICENSE](LICENSE)。
