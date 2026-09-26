package com.yusheng.quota.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.yusheng.quota.BuildConfig
import com.yusheng.quota.R
import com.yusheng.quota.data.normalizeLoginUrl
import com.yusheng.quota.net.MimoEndpoints
import com.yusheng.quota.net.PageFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** logcat 统一用这个 tag 过滤：`adb logcat -s QuotaLogin` */
private const val TAG = "QuotaLogin"

/**
 * 加载看门狗：这么多毫秒内 WebView 一次回调都没有，就判定「既没开始加载也没报错」。
 *
 * 存在的理由：站点不可达（DNS 被墙、连接被静默丢弃）时 `WebViewClient` 的**任何**回调都不触发，
 * 页面就是一片空白、一行提示都没有 —— 用户看到的就是「打不开，也没说为什么」。
 */
private const val LOAD_WATCHDOG_MS = 10_000L

/**
 * 卡死看门狗：`onPageStarted` 已经回调过（所以「没响应」那条判断不成立），
 * 但到了这个时间点还没等到 `onPageFinished` —— 正文一直没下完。
 *
 * 表现和「站点不可达」一模一样（白屏、零提示、零回调），原因却完全不同：
 * 一个是压根没连上，一个是连上了卡在半路。只靠「有没有回调」区分不了这两者。
 */
private const val STALL_WATCHDOG_MS = 16_000L

/** 控制台消息 / 页面错误各留几条 —— 白屏只需要头几条，多了没用还挤占复制出来的长度 */
private const val MAX_DIAG_LINES = 3

/**
 * 页面状态的只读探针，返回一个 JSON 字符串（见 decodeProbe）。
 *
 * 为什么不能只看 `innerText` 长度：`document.body` 为空时它也返回 0，于是
 * 「HTML 根本没下来」和「HTML 下来了但脚本没渲染」会得出同一个结论 —— 而这俩的
 * 修法完全不同。这里把能区分的字段一次全取回来：
 *  - `ready`：`interactive` 卡住 = 正文还在下；`complete` = 下完了
 *  - `js` / `js1`：页面自己注入的 `<script src>`。这个站点（rspack 打包）的壳里
 *    只有一个内联运行时，入口 chunk 是运行时**后来插入**的 —— 所以 `js=0`
 *    就等于「运行时没跑到插入那一步」，`js>=1` 但 `root=0` 就等于「chunk 没加载成」
 *  - `res` / `badN` / `bad`：`performance` 里记录到的子资源请求数与失败的（HTTP >= 400）。
 *    主框架之外的失败在 `onReceivedError` 里是被 `isForMainFrame` 过滤掉的盲区
 *  - `errJs`：页面里 `window.onerror` / `unhandledrejection` 钩子记下的头几条。
 *    `ChunkLoadError` 正是以 **未处理的 Promise 拒绝** 形式冒出来的，
 *    `onConsoleMessage` 不保证收得到，所以单独钩一条
 */
private const val PROBE_JS = """
    (function () {
      var o = {};
      function cut(x, n) { x = String(x == null ? '' : x); return x.length > n ? x.slice(0, n) : x; }
      try {
        var d = document, b = d.body, r = d.getElementById('root');
        o.ready = cut(d.readyState, 12);
        o.text = b ? (b.innerText || '').trim().length : -1;
        // 正文**内容**（不是长度）：页面只画出 90 个字时，那 90 个字就是全部线索
        o.txt = cut(b ? (b.innerText || '').replace(/\s+/g, ' ').trim() : '', 140);
        o.html = b ? b.innerHTML.length : -1;
        o.root = r ? r.childElementCount : -1;
        o.rootHtml = r ? r.innerHTML.length : -1;
        var sc = d.querySelectorAll('script[src]');
        o.js = sc.length;
        o.js1 = sc.length ? cut(sc[sc.length - 1].src, 90) : '';
        o.pre = d.querySelectorAll('link[rel=preload]').length;
        // 风控验证码之类的组件常挂在 iframe 里：有没有 iframe、首个指向哪，一眼定位
        var ifr = d.querySelectorAll('iframe');
        o.ifr = ifr.length;
        o.ifr1 = ifr.length ? cut(ifr[0].src || '(no src)', 90) : '';
        var rs = performance.getEntriesByType('resource') || [];
        o.res = rs.length;
        // 页面实际渲出来的背景色：判断「黑屏是渲黑了还是没画出来」的直接证据
        try { o.bg = cut(getComputedStyle(d.documentElement).backgroundColor, 24); } catch (e) {}
        var bad = [];
        for (var i = 0; i < rs.length; i++) {
          var e = rs[i];
          if (e.responseStatus && e.responseStatus >= 400) bad.push(e.responseStatus + ' ' + cut(e.name, 70));
        }
        o.badN = bad.length;
        o.bad = bad.slice(0, 3).join(' ; ');
      } catch (e) { o.err = cut(e && e.message || e, 90); }
      if (window.__qbErr && window.__qbErr.length) o.errJs = window.__qbErr.slice(0, 3).join(' ; ');
      return JSON.stringify(o);
    })()
"""

/**
 * 尽早挂上错误钩子（幂等，重复注入无副作用）。
 *
 * 钩的是 `error` 的**捕获阶段**：资源（script/link）加载失败时，事件的目标就是那个元素，
 * 不会冒泡到 `window`，只有捕获阶段收得到 —— 这正是「入口 chunk 404」最直接的证据。
 * `unhandledrejection` 则用来捞 `ChunkLoadError`。
 */
private const val ERROR_HOOK_JS = """
    (function () {
      if (window.__qbErr) return '1';
      window.__qbErr = [];
      window.addEventListener('error', function (ev) {
        try {
          var t = ev && ev.target;
          if (t && (t.src || t.href)) { window.__qbErr.push('res ' + (t.src || t.href)); }
          else {
            window.__qbErr.push(String((ev && ev.message) || 'error') + ' @' +
              String((ev && ev.filename) || '') + ':' + String(ev && ev.lineno));
          }
        } catch (e) {}
      }, true);
      window.addEventListener('unhandledrejection', function (ev) {
        try {
          var r = ev && ev.reason;
          window.__qbErr.push('promise ' + String((r && r.name ? r.name + ': ' + r.message : r) || ev.reason));
        } catch (e) {}
      });
      return '1';
    })()
"""

/**
 * 现代控制台页面所需的内核下限（Chrome 主版本号）。
 *
 * 这个数字不是拍的：火山方舟控制台的入口 bundle（`ark-new-main/static/js/main.*.js`）里
 * 有 4 处 `??=`、3 处 `||=`（Module Federation 运行时那段）。逻辑赋值运算符要 **Chrome 85+**，
 * 低了就是**语法错误** —— 整个 bundle 一个字符都执行不了，页面停在空壳上。
 * 而挂在一边的第三方挂件（在线咨询之类）多是 ES5 写的，照样能画出来 ——
 * 「白屏但侧边有个咨询按钮」正是这个组合。
 */
private const val MODERN_ENGINE_FLOOR = 85

/** 拿不到真实 UA 时的兜底手机 UA（正常路径用不到，见 mobileUaFrom） */
private const val FALLBACK_MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

/**
 * 由内核**真实** UA 派生手机 UA：只去掉 `wv` 与 `Version/x.y` 标记，**保留真实版本号**。
 *
 * 别再写死版本号了：站点会按 UA 声称的能力决定下发什么产物，而声称得比内核新的时候，
 * 站点就会下发内核根本解析不了的语法。`wv` 标记要去掉（不少站点见到它就拒绝渲染），
 * 版本号必须是真的。
 */
private fun mobileUaFrom(raw: String?): String {
    val base = raw?.takeIf { it.isNotBlank() } ?: return FALLBACK_MOBILE_UA
    return base
        .replace("; wv)", ")")
        .replace(Regex("\\sVersion/\\d+(\\.\\d+)*"), "")
        .replace(Regex("\\swv\\b"), "")
}

/** 从 UA（或 WebView 内核包版本号）里取 Chrome 主版本 */
private fun engineMajorOf(ua: String?): Int? =
    ua?.let { Regex("Chrome/(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
/**
 * 这条控制台报错像不像「内核太老，脚本根本跑不起来」。
 *
 * 语法错误是解析期就挂的（`??=` 之于 Chrome 85 之前），运行时缺 API 则是调用时抛的
 * （`Object.hasOwn` 之类），两者对用户都是同一件事：主脚本没跑，页面是空壳。
 */
private fun isLikelyEngineFailure(msg: String): Boolean {
    val m = msg.lowercase()
    return m.contains("syntaxerror") || m.contains("unexpected token") ||
        m.contains("is not a function") || m.contains("is not defined") ||
        m.contains("undefined is not")
}

/**
 * 应用内登录 + 取数。
 *
 * 设计要点（都是被真实站点逼出来的）：
 *  1. 登录后从 Cookie Jar 取 Cookie；**在页面内 fetch 额度接口**，同源且自动带登录态，
 *     同时保存查询接口所在域的 Cookie，供后续按账户查询
 *  2. 支持弹窗式登录（`window.open` / `target=_blank`），登录态不丢
 *  3. 「适应宽度」按页面真实宽度重新计算缩放，桌面版控制台不再显示不全
 *  4. 全屏模式把整块屏幕让给网页；渲染进程被回收时自动重建
 *
 * 返回值：`(cookie, json)`。json 为 null 表示这次没取到额度数据。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginCaptureScreen(
    startUrl: String,
    fetchUrl: String,
    onCancel: () -> Unit,
    onCaptured: (cookie: String?, json: String?) -> Unit,
) {
    // 有些模板/老账户把「控制台深链」当登录页存了，那东西在老内核上根本跑不起来（见
    // normalizeLoginUrl 的说明）。这里统一换成真正的登录页 —— 登录只是要拿 Cookie。
    val initialUrl = remember(startUrl) { normalizeLoginUrl(startUrl) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var cookie by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val captureScope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var immersive by remember { mutableStateOf(false) }
    var webViewKey by remember { mutableIntStateOf(0) }
    val webView = remember { mutableStateOf<WebView?>(null) }
    /** WebView 内核信息：缺失时几乎所有站点都是白屏，必须让用户看到 */
    val providerInfo = remember {
        runCatching {
            WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
        }.getOrNull()
    }
    var consoleError by remember { mutableStateOf("") }
    /** 每发起一次新加载 +1，用来重启看门狗（刷新 / 切桌面版 / 重建 WebView 都算新一轮） */
    var loadGen by remember { mutableIntStateOf(0) }
    /** 本轮加载是否**收到过任何** WebView 回调：全程为 false = 站点没响应 */
    var responded by remember { mutableStateOf(false) }
    var noResponse by remember { mutableStateOf(false) }
    var sslFailed by remember { mutableStateOf(false) }
    /** 页面画出来了但内容几乎是空的 */
    var looksBlank by remember { mutableStateOf(false) }
    /** new WebView(ctx) 失败的原因（系统 WebView 被禁用等） */
    var setupError by remember { mutableStateOf("") }
    var diagCopied by remember { mutableStateOf(false) }
    /** 本轮加载是否已 onPageFinished —— 与 responded 的区别就是「卡在半路」那一种 */
    var finished by remember { mutableStateOf(false) }
    /** 正文一直没下完（见 STALL_WATCHDOG_MS） */
    var stalled by remember { mutableStateOf(false) }
    /** 最近一次页面探针的结果，直接进诊断信息 */
    var probe by remember { mutableStateOf<PageProbe?>(null) }
    /** 主框架之外失败的子资源数（onReceivedError/HttpError 里原先被过滤掉的那部分） */
    var resFail by remember { mutableIntStateOf(0) }
    var resFailDetail by remember { mutableStateOf("") }
    /** 任意级别的控制台消息，头几条 —— 一条都没有本身就说明「页面 JS 一行都没往外说」 */
    var consoleAll by remember { mutableStateOf<List<String>>(emptyList()) }
    /** 探针回来的序号 —— 复制要等的是「这一次」探针，不是上一次那份快照 */
    var probeSeq by remember { mutableIntStateOf(0) }
    var copyWantSeq by remember { mutableIntStateOf(-1) }
    /** 厂商接口自己给出的登录地址（未登录时回 401 + loginUrl） */
    var apiLoginUrl by remember { mutableStateOf<String?>(null) }
    var loginFromApi by remember { mutableStateOf(false) }

    // 小米：一次取「用量 / 详情 / 余额」三个接口（HttpOnly 会话只有在页面里才带得上）
    val mimoUrls = remember(fetchUrl) { MimoEndpoints.endpointsFor(fetchUrl) }
    val fetchUrls = remember(mimoUrls, fetchUrl) {
        mimoUrls ?: listOf(fetchUrl).filter { it.isNotBlank() }
    }

    /**
     * MiMo 的模板登录地址是控制台深链：一个重 SPA（主 chunk 458KB、大量可选链语法，
     * Chrome 80 以下整段脚本不执行；低内存设备还可能渲染进程崩溃循环），用户看到的
     * 就是「黑屏打不开」。而登录要的只是 Cookie —— 真正的登录页由接口 401 回包的
     * loginUrl 给出（见下方 LaunchedEffect）。解析期间不加载深链，失败再回退
     * （控制台加载后也会自己跳 SSO）。
     */
    var waitingLoginUrl by remember { mutableStateOf(mimoUrls != null) }
    /** 登录完成后的自动抓取只做一次（见 onPageFinished） */
    var autoCaptured by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    /**
     * 软键盘是否弹起。
     *
     * edge-to-edge 下窗口不会为键盘缩放，而验证码这类**必须输入**的页面，
     * 键盘一压就只剩一条缝：正文被挤到看不见，看起来就是「黑屏 / 显示不全」。
     * 这里在键盘弹起时把底部面板收起来，把高度全让给网页。
     */
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    // 内核真实 UA —— 我们发给站点的 UA 由它派生，内核版本也从它判断
    val defaultUa = remember {
        runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull()
    }
    val engineMajor = remember(defaultUa, providerInfo) {
        engineMajorOf(defaultUa) ?: engineMajorOf(providerInfo)
    }
    val mobileUa = remember(defaultUa) { mobileUaFrom(defaultUa) }
    val engineTooOld = engineMajor != null && engineMajor < MODERN_ENGINE_FLOOR

    LaunchedEffect(initialUrl, startUrl, providerInfo, engineMajor) {
        Log.i(
            TAG,
            "engineChrome=$engineMajor floor=$MODERN_ENGINE_FLOOR provider=$providerInfo " +
                "start=${startUrl} effective=${initialUrl}",
        )
    }

    /**
     * 登录页不靠猜：**厂商接口自己会说**。
     *
     * 未登录访问额度接口时，控制台普遍回 `{"code":401,"loginUrl":"…"}`（小米/火山都是），
     * 这个地址就是权威登录页，且通常是**普通表单页**（ES5 级别），老内核也跑得动 ——
     * 而控制台深链要先跑通 SPA 才谈得上登录。拿到后直接把 WebView 切过去。
     */
    LaunchedEffect(fetchUrls) {
        val primary = fetchUrls.firstOrNull() ?: return@LaunchedEffect
        val held = waitingLoginUrl
        val resolved = resolveLoginUrlFromApi(primary)
        if (held) waitingLoginUrl = false
        if (!resolved.isNullOrBlank() && resolved != currentUrl) {
            apiLoginUrl = resolved
            loginFromApi = true
            Log.i(TAG, "login url resolved from api: ${shortUrl(resolved)}")
            // 万一 WebView 还没建好（理论上的竞态），factory 会拿 currentUrl 做首次加载
            currentUrl = resolved
            webView.value?.loadUrl(resolved)
        } else if (held) {
            // 解析失败（接口不可达等）：回退到模板深链，别让 WebView 一直停在空白上
            Log.w(TAG, "login url resolve failed, falling back to ${shortUrl(currentUrl)}")
            webView.value?.loadUrl(currentUrl.ifBlank { startUrl })
        }
    }

    val loggedInText = stringResource(R.string.login_state_logged_in)
    val notLoggedInText = stringResource(R.string.login_state_unknown)
    val fetchFailedText = stringResource(R.string.login_fetch_failed)
    val resolvingText = stringResource(R.string.login_resolving)
    val openBrowserText = stringResource(R.string.action_open_browser)
    val googleBlockedText = stringResource(R.string.login_google_blocked)
    val fitText = stringResource(R.string.login_fit_width)
    val blankHint = stringResource(R.string.login_blank_hint)
    val webViewMissing = stringResource(R.string.login_webview_missing)
    val sslErrorText = stringResource(R.string.login_ssl_error)
    val noResponseText = stringResource(R.string.login_no_response, (LOAD_WATCHDOG_MS / 1000).toInt())
    val diagCopyText = stringResource(R.string.login_diag_copy)
    val diagCopiedText = stringResource(R.string.login_diag_copied)
    val engineOldText = engineMajor?.let { stringResource(R.string.login_engine_old, it, MODERN_ENGINE_FLOOR) }
    val progressAlpha by animateFloatAsState(if (progress in 1..99) 1f else 0f, label = "loginProgress")

    /**
     * 读一次页面状态（只读，不改页面）。结果进 `probe`，同时更新「空白」判定。
     *
     * 放在看门狗之前声明：卡死那一段也要顺手探一次，那一刻的 DOM 才是要看的。
     */
    fun probeDom(wv: WebView) {
        wv.evaluateJavascript(PROBE_JS) { raw ->
            val p = decodeProbe(raw)
            if (p != null) {
                probe = p
                probeSeq += 1
                looksBlank = p.blank
                Log.i(TAG, "probe ${p.line()}")
            }
        }
    }

    // 站点不可达时 WebViewClient 一个回调都不给，页面就是纯白且无提示；
    // 这里到点检查「这一轮有没有任何回调」，没有就明确告诉用户是网络不通。
    // 注意：这个 effect 里**只读不写**状态（responded 由 startNewLoad 复位），
    // 否则复位会晚于 onPageStarted，把已经加载成功的页面误判成超时。
    LaunchedEffect(webViewKey, loadGen) {
        if (setupError.isNotBlank() || providerInfo == null) return@LaunchedEffect
        val gen = loadGen
        delay(LOAD_WATCHDOG_MS)
        if (loadGen == gen && !responded) {
            noResponse = true
            Log.w(TAG, "watchdog: no callback in ${LOAD_WATCHDOG_MS}ms, url=$currentUrl")
        }
        // 第二段：已经回调过（所以不是「不可达」），但正文一直没下完。
        // 这正是「连上了、卡在半路」那一种 —— 表现和不可达一模一样，只有分开计时才认得出来。
        delay(STALL_WATCHDOG_MS - LOAD_WATCHDOG_MS)
        if (loadGen == gen && responded && !finished) {
            stalled = true
            Log.w(TAG, "watchdog: still loading after ${STALL_WATCHDOG_MS}ms, url=$currentUrl")
            webView.value?.let { probeDom(it) }
        }
    }

    fun openInBrowser(rawUrl: String?) {
        val target = rawUrl?.takeIf { it.isNotBlank() && it != "about:blank" } ?: startUrl
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(Intent.createChooser(intent, openBrowserText))
        } catch (_: ActivityNotFoundException) {
            status = openBrowserText
        } catch (_: Exception) {
            status = openBrowserText
        }
    }

    /**
     * 统一的导航拦截：明文 http 跳转升级、非网页 scheme 交给系统浏览器。
     *
     * 小米 SSO 登录成功后，sts 会 302 到 followup 参数里的 http:// 明文地址（接口回包
     * 实测如此），而应用禁止明文加载 —— 直接放行就是 ERR_CLEARTEXT_NOT_PERMITTED，
     * 登录明明成功却停在错误页上，后续「抓取额度」跑在错误页上下文里必然失败。
     */
    fun handleUrlOverride(view: WebView?, url: String): Boolean {
        val upgraded = upgradeCleartextForFetch(url, fetchUrl)
        if (upgraded != null) {
            Log.i(TAG, "cleartext upgraded to https: ${shortUrl(url)}")
            runCatching { view?.loadUrl(upgraded) }
            return true
        }
        if (url.startsWith("http") || url.startsWith("about:")) return false
        openInBrowser(url)
        return true
    }

    /**
     * 按页面真实宽度重新计算缩放：桌面版控制台（固定 1000px+ 宽）在手机上会被裁掉右边，
     * 这里读 `scrollWidth` 反推缩放比例，把整页压进屏幕。
     */
    fun fitToWidth(wv: WebView) {
        val viewWidth = wv.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        val density = ctx.resources.displayMetrics.density
        wv.evaluateJavascript(
            "(function(){var d=document.documentElement,b=document.body;" +
                "return String(Math.max(d?d.scrollWidth:0,b?b.scrollWidth:0,0));})()"
        ) { raw ->
            val pageWidth = raw?.trim('"')?.toFloatOrNull() ?: 0f
            if (pageWidth <= 0f) return@evaluateJavascript
            val visibleCss = viewWidth / density
            // 原来用 setInitialScale + reload，两处都不对：
            //  1. 页面自带 <meta viewport> 时 setInitialScale 会被忽略，而桌面版控制台正好都有
            //     —— 也就是对最需要适配的页面它是空操作
            //  2. reload 会把用户正在填的表单清空，而紧接着的 onPageFinished 又会把提示抹掉
            // 改成直接改 documentElement 的 CSS zoom：不挑 viewport，也不用重载页面
            val scale = (visibleCss / pageWidth).coerceIn(0.25f, 1.5f)
            wv.evaluateJavascript(
                "(function(){document.documentElement.style.zoom='$scale';" +
                    "return String(document.documentElement.scrollWidth);})()"
            ) { _ -> }
            status = "$fitText ${(scale * 100).toInt()}%"
        }
    }

    /**
     * 页面横向溢出时**自动**按宽度缩放。
     *
     * 用的是与「适应宽度」同一套 CSS zoom（不重载页面），所以不会清空用户已填的内容 ——
     * 这一步专门照顾需要多步输入的站点：账号密码过了之后，验证码那一步的页面
     * 常常比屏幕宽一截，表现出来就是「黑屏 / 显示不全」。
     * 每个地址只自动做一次，避免「缩放 → 又判定溢出」来回抖。
     */
    val autoFitDone = remember { mutableSetOf<String>() }
    fun autoFitIfOverflow(wv: WebView) {
        val viewWidth = wv.width.takeIf { it > 0 } ?: return
        wv.evaluateJavascript(
            "(function(){var d=document.documentElement,b=document.body;" +
                "var w=Math.max(d?d.scrollWidth:0,b?b.scrollWidth:0,0);" +
                "return JSON.stringify([w, window.innerWidth||0]);})()"
        ) { raw ->
            val arr = runCatching {
                org.json.JSONArray(
                    org.json.JSONTokener(raw ?: "").nextValue().toString()
                )
            }.getOrNull() ?: return@evaluateJavascript
            val pageCss = arr.optDouble(0, 0.0).toFloat()
            val winCss = arr.optDouble(1, 0.0).toFloat()
            if (pageCss <= 0f || winCss <= 0f || pageCss <= winCss * 1.08f) return@evaluateJavascript
            val url = wv.url ?: return@evaluateJavascript
            if (!autoFitDone.add(url)) return@evaluateJavascript
            val density = ctx.resources.displayMetrics.density
            val scale = ((viewWidth / density) / pageCss).coerceIn(0.25f, 1.0f)
            wv.evaluateJavascript("(function(){document.documentElement.style.zoom='$scale';})()") { }
            Log.i(TAG, "auto-fit ${(scale * 100).toInt()}% page=${pageCss.toInt()} for ${shortUrl(url)}")
        }
    }

    /**
     * 发起一轮新加载（刷新 / 切桌面版）。复位上一轮的判定，并让看门狗重新计时。
     * `responded = false` 必须在 loadUrl/reload **之前**同步做掉，见上面 effect 的注释。
     */
    fun startNewLoad(wv: WebView, reload: Boolean) {
        responded = false
        noResponse = false
        sslFailed = false
        looksBlank = false
        finished = false
        stalled = false
        probe = null
        resFail = 0
        resFailDetail = ""
        consoleAll = emptyList()
        copyWantSeq = -1
        status = ""
        if (reload) wv.reload() else wv.loadUrl(currentUrl.ifBlank { startUrl })
        loadGen += 1
        Log.i(TAG, "load start (reload=$reload) url=${currentUrl.ifBlank { startUrl }}")
    }

    /**
     * 记一条**主框架之外**的加载失败。
     *
     * 原先这类失败被 `isForMainFrame` 过滤掉了 —— 那正好是白屏的盲区：
     * 外壳 HTML 好好地下来了，入口 chunk 却挂了，页面一样是空的，而这条回调一声不响。
     */
    fun noteResourceFailure(detail: String) {
        resFail += 1
        if (resFailDetail.isBlank()) resFailDetail = detail.take(70)
    }

    /** 诊断信息：让用户直接复制出来贴进 issue，字段名保持英文，不随语言变 */
    fun diagnostics(): String = buildString {
        append("app=").append(BuildConfig.VERSION_NAME)
        append("\nandroid=").append(android.os.Build.VERSION.SDK_INT)
        append("\nwebview=").append(providerInfo ?: "missing")
        append("\nengine_chrome=").append(engineMajor?.toString() ?: "?")
        append("\nengine_floor=").append(MODERN_ENGINE_FLOOR)
        append("\nua_sent=").append(mobileUa)
        append("\nurl=").append(currentUrl)
        append("\nstatus=").append(status.ifBlank { "-" })
        append("\nprogress=").append(progress)
        // 键盘状态与 WebView 实际可视高度：验证码页「黑屏/显示不全」时，这两个数最能说明问题
        append("\nime=").append(if (imeVisible) "yes" else "no")
        append("\nchrome=").append(
            when {
                immersive -> "immersive"
                imeVisible -> "ime-compact"
                else -> "full"
            }
        )
        webView.value?.let { wv -> if (wv.height > 0) append("\nview_h=").append(wv.height).append("px") }
        append("\nstarted=").append(if (responded) "yes" else "no")
        append("\nfinished=").append(if (finished) "yes" else "no")
        append("\nblank=").append(if (looksBlank) "yes" else "no")
        append("\nssl_error=").append(if (sslFailed) "yes" else "no")
        append("\nno_response=").append(if (noResponse) "yes" else "no")
        append("\nstalled=").append(if (stalled) "yes" else "no")
        append("\nres_failed=").append(resFail)
        if (resFailDetail.isNotBlank()) append(" first=").append(resFailDetail)
        append("\nsetup_error=").append(setupError.ifBlank { "-" })
        // 这一段就是「白屏到底是哪一种」的判据，见 PROBE_JS 的注释
        append("\ndom=").append(probe?.line() ?: "-")
        append("\nconsole=").append(consoleError.ifBlank { "-" })
        append("\nconsole_all=").append(if (consoleAll.isEmpty()) "-" else consoleAll.joinToString(" | "))
    }

    fun copyNow() {
        val text = diagnostics()
        Log.i(TAG, text.replace('\n', ' '))
        clipboard.setText(AnnotatedString(text))
        diagCopied = true
    }

    fun copyDiagnostics() {
        // 点这行的语义是「重新检测一次并复制」：用户是看着白屏点的，
        // 要的正是这一刻的 DOM，而不是几秒前 onPageFinished 时那份
        val wv = webView.value
        if (wv == null) {
            copyNow()
            return
        }
        copyWantSeq = probeSeq + 1
        probeDom(wv)
    }

    // 「已复制」提示两秒后自动消失，不影响下一轮操作
    LaunchedEffect(diagCopied) {
        if (diagCopied) {
            delay(2000)
            diagCopied = false
        }
    }

    // 探针回来了才复制：点一下 = 重读页面状态 + 复制，避免贴出去的还是旧快照
    LaunchedEffect(probeSeq) {
        if (copyWantSeq in 0..probeSeq) {
            copyWantSeq = -1
            copyNow()
        }
    }

    // 返回键先走网页历史，退无可退再关闭登录页
    BackHandler {
        val wv = webView.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onCancel()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (!immersive) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        currentUrl,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 适应宽度：桌面版控制台一键缩到屏幕内
                IconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = { webView.value?.let { fitToWidth(it) } },
                ) {
                    Icon(Icons.Default.ZoomOutMap, contentDescription = fitText, modifier = Modifier.size(20.dp))
                }
                IconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = { openInBrowser(webView.value?.url ?: currentUrl) },
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = openBrowserText, modifier = Modifier.size(20.dp))
                }
                IconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = { webView.value?.let { startNewLoad(it, reload = true) } },
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh), modifier = Modifier.size(20.dp))
                }
                IconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = { immersive = true },
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = stringResource(R.string.login_fullscreen), modifier = Modifier.size(20.dp))
                }
            }
        }

        // 进度条常驻占位，避免加载时高度跳动
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = progressAlpha),
            )
        }

        Box(Modifier.weight(1f)) {
            // 渲染进程被回收后，用 key 触发 AndroidView 重建
            key(webViewKey) {
                AndroidView(
                    factory = { context ->
                        // 系统 WebView 被禁用/损坏时构造函数**直接抛异常**（不是返回 null）。
                        // 不接住就是一次崩溃，用户只会觉得「应用坏了」，还不如给个空 View + 原因。
                        // 这里在组合期，直接写 state 会打断当前组合，所以 post 到下一帧再写。
                        val created = try {
                            WebView(lightWebContext(context))
                        } catch (e: Throwable) {
                            Log.e(TAG, "WebView create failed", e)
                            val placeholder = View(context)
                            placeholder.post {
                                setupError = e.message?.take(160) ?: e.javaClass.simpleName
                            }
                            return@AndroidView placeholder
                        }
                        created.apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.textZoom = 100
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            // 弹窗式登录（window.open / target=_blank）必须开启，否则点了没反应
                            settings.setSupportMultipleWindows(true)
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            // 固定手机 UA：登录页一律按手机版渲染（桌面版对验证码/SSO 只会更糟）
                            settings.userAgentString = mobileUa
                            // 关闭「算法暗色」：它会给不支持暗色的站点整页刷黑，看起来就是黑屏
                            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
                            }
                            applyForceDarkOff(settings)
                            setBackgroundColor(AndroidColor.WHITE)
                            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    runCatching { progress = newProgress }
                                }

                                /** 记录第一条脚本错误：白屏大多是脚本挂了 */
                                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                                    // 回调里抛异常会直接崩掉应用，全部兜住
                                    runCatching {
                                        val text = msg?.message().orEmpty()
                                        if (text.isNotBlank() && consoleAll.size < MAX_DIAG_LINES) {
                                            consoleAll = consoleAll + "${msg?.messageLevel()}: ${text.take(100)}"
                                            Log.i(TAG, "console ${msg?.messageLevel()}: $text")
                                        }
                                        if (consoleError.isBlank() &&
                                            (msg?.messageLevel() == ConsoleMessage.MessageLevel.ERROR || text.contains("error", true))
                                        ) {
                                            consoleError = text.take(120)
                                        }
                                    }
                                    return false
                                }

                                /** 登录页常见的弹窗：复用当前 WebView 打开，登录态才不会丢 */
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?,
                                ): Boolean {
                                    return runCatching {
                                        // view 为空时不能塞进 transport：Google 登录这类弹窗走的就是这条
                                        val target = view ?: return@runCatching false
                                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                                            ?: return@runCatching false
                                        transport.webView = target
                                        resultMsg.sendToTarget()
                                        true
                                    }.getOrDefault(false)
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    runCatching {
                                        if (url != null) currentUrl = url
                                        progress = 10
                                        responded = true
                                        // 新页面开始加载：清掉上一轮的「无响应 / 卡住 / 空白 / 证书」判定，
                                        // 否则换页之后旧提示还挂在屏幕上
                                        noResponse = false
                                        stalled = false
                                        looksBlank = false
                                        sslFailed = false
                                        // 离开平台域（回到登录表单）= 自动抓取的那次结果已作废：
                                        // 重新武装，等再次登录完成时还能自动抓
                                        if (mimoUrls != null && url != null && !MimoEndpoints.isMimo(url)) {
                                            autoCaptured = false
                                        }
                                        // 尽早挂错误钩子：入口 chunk 加载失败只以「未处理的 Promise 拒绝」
                                        // 形式出现，等到 onPageFinished 再挂就已经错过了
                                        view?.evaluateJavascript(ERROR_HOOK_JS) { }
                                        Log.i(TAG, "onPageStarted $url")
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    runCatching {
                                        if (url != null) {
                                            currentUrl = url
                                            cookie = CookieManager.getInstance().getCookie(fetchUrl.ifBlank { url }).orEmpty()
                                        }
                                        progress = 100
                                        finished = true
                                        view?.evaluateJavascript(ERROR_HOOK_JS) { }
                                    }
                                    // 空白页检测：原先只看 innerText 长度，分辨不了
                                    // 「HTML 压根没下来」和「HTML 下来了但脚本没渲染」——
                                    // 现在整份 DOM / 资源状态一次取回来（见 PROBE_JS）。
                                    // 走独立标志而不是写进 status：写进 status 会把下面
                                    // 「内核过旧 / 脚本执行失败」这类更具体的判断盖掉。
                                    view?.let { probeDom(it) }
                                    // 验证码这类多步页面常比屏幕宽，自动缩到屏幕内（不重载，不丢输入）
                                    view?.let { autoFitIfOverflow(it) }
                                    // 登录完成的信号：已落回查询接口所在域 + 平台域出现会话 Cookie。
                                    // 此时页面多半停在 followup 的原始 JSON（或控制台）上，用户未必
                                    // 知道还要点「抓取额度」—— 自动抓一次，成功直接收尾关闭登录页；
                                    // 失败（会话其实已失效等）保留页面，让用户手动重试或重新登录。
                                    val wv = view
                                    if (mimoUrls != null && wv != null && url != null &&
                                        !autoCaptured && !busy && MimoEndpoints.isMimo(url)
                                    ) {
                                        val jar = runCatching {
                                            CookieManager.getInstance().getCookie(fetchUrl).orEmpty()
                                        }.getOrDefault("")
                                        if (MimoEndpoints.hasSession(jar)) {
                                            autoCaptured = true
                                            busy = true
                                            status = ""
                                            Log.i(TAG, "auto capture after login at ${shortUrl(url)}")
                                            capture(captureScope, wv, fetchUrls, true, jar) { c, body ->
                                                busy = false
                                                if (body != null) onCaptured(c, body) else status = fetchFailedText
                                            }
                                        }
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    val req = request ?: return
                                    if (req.isForMainFrame) {
                                        responded = true
                                        val desc = error?.description?.toString().orEmpty()
                                        // POST 形式的重定向不经过 shouldOverrideUrlLoading，
                                        // 明文跳转会被拦在这里：升级成 https 重载一次，别让登录死在错误页
                                        if (desc.contains("CLEARTEXT", true)) {
                                            val upgraded = upgradeCleartextForFetch(req.url?.toString().orEmpty(), fetchUrl)
                                            if (upgraded != null) {
                                                Log.w(TAG, "cleartext blocked, reloading as https: ${shortUrl(upgraded)}")
                                                runCatching { view?.loadUrl(upgraded) }
                                                return
                                            }
                                        }
                                        status = desc.ifBlank { fetchFailedText }
                                        Log.w(TAG, "onReceivedError main ${error?.errorCode}: $status")
                                    } else {
                                        // 子资源失败原先被这里过滤掉 —— 白屏的盲区正是在这：
                                        // 外壳 HTML 好好地下来了、入口 chunk 挂了，页面一样是空的
                                        noteResourceFailure("${error?.errorCode} ${shortUrl(req.url?.toString())}")
                                        Log.w(TAG, "onReceivedError sub ${error?.errorCode} ${req.url}")
                                    }
                                }

                                /**
                                 * 证书校验失败。**默认实现就是静默取消加载** —— 页面停在空白，
                                 * 一个字的提示都没有，用户看到的就是「打不开」。
                                 * 这里必须显式 cancel()：绝不能为了让它能打开就 proceed()，
                                 * 那等于关掉证书校验，等于把中间人攻击当正常路径。
                                 */
                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?,
                                ) {
                                    handler?.cancel()
                                    sslFailed = true
                                    responded = true
                                    Log.e(TAG, "onReceivedSslError primary=${error?.primaryError} url=${error?.url}")
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?,
                                ) {
                                    val req = request ?: return
                                    if (req.isForMainFrame) {
                                        responded = true
                                        status = "HTTP ${errorResponse?.statusCode ?: 0}"
                                        Log.w(TAG, "onReceivedHttpError main $status")
                                    } else {
                                        // 同上：404 的 chunk 是「页面空白」最直接的证据
                                        noteResourceFailure(
                                            "${errorResponse?.statusCode ?: 0} ${shortUrl(req.url?.toString())}"
                                        )
                                        Log.w(TAG, "onReceivedHttpError sub ${errorResponse?.statusCode} ${req.url}")
                                    }
                                }

                                /** 渲染进程被系统回收时会白屏 / 只画一半，重建 WebView 并回到当前页 */
                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?,
                                ): Boolean {
                                    Log.e(TAG, "onRenderProcessGone didCrash=${detail?.didCrash()}")
                                    val back = view?.url ?: currentUrl
                                    runCatching { view?.destroy() }
                                    webView.value = null
                                    webViewKey += 1
                                    currentUrl = back
                                    responded = false
                                    return true
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val url = request?.url?.toString().orEmpty()
                                    return handleUrlOverride(view, url)
                                }

                                @Deprecated("Deprecated in Java")
                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    if (url.isNullOrBlank()) return false
                                    return handleUrlOverride(view, url)
                                }
                            }
                            // 渲染进程被回收后重建时用 currentUrl，回到崩溃时所在的页面
                            // 而不是入口页（否则登录到一半会被打回首页）。
                            // MiMo 在等接口解析登录页期间先不加载（见 waitingLoginUrl 的说明）。
                            if (!waitingLoginUrl) {
                                loadUrl(currentUrl.ifBlank { startUrl })
                            }
                            webView.value = this
                        }
                    },
                    // WebView 必须显式销毁：它持有 Activity context 与正在跑的 JS 定时器，
                    // 只从组合里移除不会释放，每次登录都会漏一个。
                    // 这里不调 loadUrl（实例可能已被 onRenderProcessGone 销毁过，对已销毁的
                    // WebView 调任何方法行为都未定义）。
                    // 注意类型是 View 不是 WebView：factory 在构造失败时会退成一个空 View
                    // （见上面的 try/catch），所以这里要 as? 一下，兜底路径才不会被当成 WebView 调
                    onRelease = { view ->
                        (view as? WebView)?.let { wv ->
                            runCatching { wv.stopLoading() }
                            runCatching { wv.destroy() }
                            if (webView.value === wv) webView.value = null
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 内核缺失 / 建不起来时盖一层说明。**叠在上面而不是替掉 AndroidView**：
            // 少一层 if/else 缩进，而且「为什么白屏」永远有人讲 —— 这正是之前缺的东西。
            if (providerInfo == null || setupError.isNotBlank()) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (providerInfo == null) {
                            webViewMissing
                        } else {
                            stringResource(R.string.login_setup_failed, setupError)
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // 键盘弹起时也走「紧凑模式」：验证码页要的是高度
        if (immersive || imeVisible) {
            // 全屏时只留一个悬浮小按钮，其余空间全给网页
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (immersive) {
                    IconButton(onClick = { immersive = false }) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = stringResource(R.string.login_exit_fullscreen))
                    }
                } else {
                    // 键盘挡着的时候，收面板而不是退全屏
                    IconButton(onClick = { keyboard?.hide() }) {
                        Icon(Icons.Default.KeyboardHide, contentDescription = stringResource(R.string.login_hide_keyboard))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { webView.value?.let { fitToWidth(it) } }) {
                        Icon(Icons.Default.ZoomOutMap, contentDescription = fitText)
                    }
                    Button(
                        onClick = {
                            val wv = webView.value
                            if (wv == null || fetchUrl.isBlank()) {
                                status = fetchFailedText
                                return@Button
                            }
                            busy = true
                            status = ""
                            capture(captureScope, wv, fetchUrls, mimoUrls != null, cookie) { c, body ->
                                busy = false
                                if (body == null) status = fetchFailedText else onCaptured(c, body)
                            }
                        },
                        enabled = !busy && fetchUrl.isNotBlank(),
                    ) { Text(stringResource(R.string.action_login_fetch_now), fontSize = 12.sp) }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                val isGoogle = currentUrl.contains("accounts.google.com")
                // 顺序 = 排查顺序：先「我们这边就装不起来」（内核缺失/构造失败），
                // 再页面明确报出来的错（证书/HTTP/网络），然后是能给白屏定性的判断
                // （脚本挂了 / 内核过旧），最后才是「没响应」和常规登录提示。
                val engineBroken = consoleError.isNotBlank() && isLikelyEngineFailure(consoleError)
                val needsAttention = providerInfo == null || setupError.isNotBlank() ||
                    sslFailed || noResponse || stalled || isGoogle || engineTooOld || engineBroken ||
                    (looksBlank && resFail > 0)
                Text(
                    text = when {
                        providerInfo == null -> webViewMissing
                        setupError.isNotBlank() -> stringResource(R.string.login_setup_failed, setupError)
                        sslFailed -> sslErrorText
                        waitingLoginUrl -> resolvingText
                        status.isNotBlank() -> status
                        engineBroken -> stringResource(R.string.login_script_failed, consoleError)
                        engineTooOld -> engineOldText ?: blankHint
                        // 空白且确实有文件没下来：先把「谁没下来」讲出来，
                        // 比泛泛的「内容为空」有用得多
                        looksBlank && resFail > 0 ->
                            stringResource(R.string.login_blank_res, resFail, resFailDetail)
                        stalled -> stringResource(R.string.login_stalled, (STALL_WATCHDOG_MS / 1000).toInt())
                        looksBlank -> blankHint
                        noResponse -> noResponseText
                        isGoogle -> googleBlockedText
                        cookie.isNotBlank() -> loggedInText
                        else -> notLoggedInText
                    },
                    fontSize = 11.sp,
                    color = if (needsAttention) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (providerInfo != null) {
                    // 点这行复制诊断信息（WebView 版本 / 地址 / 错误），装机上出问题直接贴出来
                    Text(
                        buildString {
                            append("WebView: $providerInfo")
                            // 内核主版本直接摆出来：白屏的第一嫌疑就是它，用户一眼就能对上
                            if (engineMajor != null) append(" · Chrome $engineMajor")
                            if (consoleError.isNotBlank()) append(" · JS: $consoleError")
                            // 登录页是从接口回包里取的（而不是猜控制台深链）
                            if (loginFromApi) append(" · login=api")
                            append(" · ")
                            append(if (diagCopied) diagCopiedText else diagCopyText)
                        },
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (diagCopied) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { copyDiagnostics() },
                    )
                }
                // 内核缺失 / 过旧：白屏的头号原因，直接给一个能按的出口
                if (providerInfo == null || engineTooOld) {
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { openWebViewStore(ctx) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_update_webview), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 顶部图标已经承担了「适应宽度 / 模式切换 / 浏览器打开」，这里只留两个动作
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val saved = CookieManager.getInstance().getCookie(fetchUrl.ifBlank { currentUrl })
                            onCaptured(saved?.takeIf { it.isNotBlank() }, null)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.action_login_save_cookie), fontSize = 12.sp) }
                    Button(
                    onClick = {
                        val wv = webView.value
                        if (wv == null || fetchUrl.isBlank()) {
                            status = fetchFailedText
                            return@Button
                        }
                        busy = true
                        status = ""
                        capture(captureScope, wv, fetchUrls, mimoUrls != null, cookie) { c, body ->
                            busy = false
                            if (body == null) status = fetchFailedText else onCaptured(c, body)
                        }
                    },
                    modifier = Modifier.weight(1.6f),
                    enabled = !busy && fetchUrl.isNotBlank(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(R.string.action_login_fetch_now), fontSize = 13.sp)
                }
                }
            }
        }
    }
}

/**
 * 诊断信息里只留 `host + path`：完整 URL 又长又带 query，
 * 贴出来会把真正要看的那几个字符淹掉。
 */
private fun shortUrl(raw: String?): String {
    val s = raw ?: return ""
    return runCatching {
        val u = Uri.parse(s)
        val host = u.host.orEmpty()
        if (host.isBlank()) s else host + (u.path ?: "")
    }.getOrDefault(s).take(70)
}

/**
 * 查询接口域上的明文 http 跳转升级为 https。
 *
 * 小米 SSO 登录成功后，sts 会 302 到 followup 参数里的 http:// 明文地址（接口 401 回包
 * 实测如此，2026-09），而 Android 9+ 默认禁止明文加载 —— 不升级就是
 * ERR_CLEARTEXT_NOT_PERMITTED，登录成功却停在错误页上。只升级与查询接口同域、且查询
 * 接口本身是 https 的跳转：别的站点不动（升了反而可能打不开），本机 http 云函数地址
 * （查询接口就是 http）也不动。
 */
internal fun upgradeCleartextForFetch(url: String, fetchUrl: String): String? {
    val trimmed = url.trim()
    if (!trimmed.lowercase().startsWith("http://")) return null
    if (!fetchUrl.trim().lowercase().startsWith("https://")) return null
    val host = runCatching { URI(trimmed).host?.lowercase() }.getOrNull() ?: return null
    val fetchHost = runCatching { URI(fetchUrl.trim()).host?.lowercase() }.getOrNull() ?: return null
    if (host != fetchHost) return null
    return "https://" + trimmed.substring("http://".length)
}

/** API 29–32 的强制暗色同样会把页面刷黑，统一关掉（更高版本用 ALGORITHMIC_DARKENING 分支） */
@Suppress("DEPRECATION")
private fun applyForceDarkOff(settings: WebSettings) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
        WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
    }
}

/**
 * 登录页 WebView 固定用「白天」上下文创建。
 *
 * 新版 WebView 会把应用的暗色主题透传给页面（prefers-color-scheme: dark）：诊断信息
 * 显示页面明明加载成功（finished=yes、blank=no、DOM 完整），但小米登录页的暗色样式
 * 是纯黑背景 + 30% 透明度的控件 —— 用户看到的就是一片黑。登录要的是能看清表单，
 * 不需要跟随主题；这里把 WebView 自己的 uiMode 强制成 NIGHT_NO。
 */
private fun lightWebContext(base: Context): Context {
    val cfg = Configuration(base.resources.configuration)
    cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        Configuration.UI_MODE_NIGHT_NO
    return base.createConfigurationContext(cfg)
}

/** 底部的一枚小动作：图标 + 短文字，横向紧凑排列 */
@Composable
private fun CompactAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(18.dp)) { icon() }
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 10.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 在页面上下文里请求额度接口：同源请求，自动携带登录态。
 * 成功时回调 (cookie, json 文本)，失败回调 (cookie, null)。
 */
private fun capture(
    scope: CoroutineScope,
    webView: WebView,
    urls: List<String>,
    mergeMimo: Boolean,
    currentCookie: String,
    onResult: (cookie: String?, json: String?) -> Unit,
) {
    if (urls.isEmpty()) {
        onResult(currentCookie.takeIf { it.isNotBlank() }, null)
        return
    }
    scope.launch {
        val results = PageFetch.run(webView, urls)
        val fresh = CookieManager.getInstance().getCookie(urls.first()).orEmpty()
        if (results == null) {
            onResult(fresh.takeIf { it.isNotBlank() }, null)
            return@launch
        }
        // 小米三个接口合并成一份；其他厂商就是单接口的原始回包
        val body = if (mergeMimo) {
            MimoEndpoints.merge(results)?.toString()
        } else {
            results.firstOrNull()?.takeIf { it.isOk }?.body
        }
        val ok = body != null && runCatching { JSONObject(body) }.isSuccess
        if (ok) {
            onResult(fresh.takeIf { it.isNotBlank() }, body)
        } else {
            onResult(fresh.takeIf { it.isNotBlank() }, null)
        }
    }
}

/**
 * 未登录时厂商会回 401 + `loginUrl`，它就是权威登录页。
 * 这里用普通的 HTTP 请求去问一次（不带任何 Cookie，副作用只有一次 GET）。
 */
private suspend fun resolveLoginUrlFromApi(apiUrl: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            // 未登录可能是 401 也可能是 302，自己判断，别让框架吞掉回包
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "quota-board-android")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        PageFetch.loginUrlIn(text)
    }.getOrNull()
}

/**
 * 跳到应用商店的「Android System WebView」页面更新内核。
 * 内核太旧是这类控制台白屏的头号原因（主脚本语法不兼容，整段不执行）。
 */
private fun openWebViewStore(ctx: android.content.Context) {
    val market = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=com.google.android.webview"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(market) }
        .onFailure { runCatching { ctx.startActivity(web) } }
}

/** evaluateJavascript 的返回值是 JSON 字面量，解一层拿到对象 */
private fun decodeJsString(raw: String?): JSONObject? {
    if (raw.isNullOrBlank() || raw == "null") return null
    val inner = runCatching { org.json.JSONTokener(raw).nextValue() }.getOrNull() ?: return null
    return when (inner) {
        is JSONObject -> inner
        is String -> runCatching { JSONObject(inner) }.getOrNull()
        else -> null
    }
}

/**
 * PROBE_JS 的返回。取不到的字段一律留默认值 —— 诊断信息少一行，好过整段解析失败。
 */
private data class PageProbe(
    val ready: String,
    val text: Int,
    val textSample: String,
    val bodyHtml: Int,
    val rootChildren: Int,
    val rootHtml: Int,
    val scriptSrc: Int,
    val firstScript: String,
    val iframes: Int,
    val firstIframe: String,
    val resources: Int,
    val badResources: Int,
    val badDetail: String,
    val jsErrors: String,
    val probeError: String,
    val bgColor: String,
) {
    /** 页面几乎是空的：正文没字、根节点没有子节点 */
    val blank: Boolean get() = text < 40 && rootChildren <= 0

    /** 紧凑一行，直接进诊断信息（字段名保持英文，方便跨语言贴 issue） */
    fun line(): String = buildString {
        append("ready=").append(ready)
        append(" text=").append(text)
        if (textSample.isNotBlank()) append(" txt=\"").append(textSample).append("\"")
        append(" body=").append(bodyHtml)
        append(" root=").append(rootChildren)
        append("/").append(rootHtml)
        append(" js=").append(scriptSrc)
        if (firstScript.isNotBlank()) append(" js1=").append(firstScript)
        if (iframes > 0) {
            append(" ifr=").append(iframes)
            if (firstIframe.isNotBlank()) append("(").append(firstIframe).append(")")
        }
        append(" res=").append(resources)
        append(" bad=").append(badResources)
        if (badDetail.isNotBlank()) append("(").append(badDetail).append(")")
        if (bgColor.isNotBlank()) append(" bg=").append(bgColor)
        if (jsErrors.isNotBlank()) append(" errJs=").append(jsErrors)
        if (probeError.isNotBlank()) append(" probeErr=").append(probeError)
    }
}

private fun decodeProbe(raw: String?): PageProbe? {
    val obj = decodeJsString(raw) ?: return null
    return PageProbe(
        ready = obj.optString("ready", "?"),
        text = obj.optInt("text", -1),
        textSample = obj.optString("txt", ""),
        bodyHtml = obj.optInt("html", -1),
        rootChildren = obj.optInt("root", -1),
        rootHtml = obj.optInt("rootHtml", -1),
        scriptSrc = obj.optInt("js", -1),
        firstScript = obj.optString("js1", ""),
        iframes = obj.optInt("ifr", 0),
        firstIframe = obj.optString("ifr1", ""),
        resources = obj.optInt("res", -1),
        badResources = obj.optInt("badN", 0),
        badDetail = obj.optString("bad", ""),
        jsErrors = obj.optString("errJs", ""),
        probeError = obj.optString("err", ""),
        bgColor = obj.optString("bg", ""),
    )
}
