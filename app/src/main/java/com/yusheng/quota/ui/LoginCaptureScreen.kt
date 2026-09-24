package com.yusheng.quota.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.delay
import org.json.JSONObject

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

/** 桌面 UA：布局是假的没关系，**版本号必须跟内核一致**，理由同上 */
private fun desktopUaFrom(engineMajor: Int?): String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/${engineMajor ?: 120}.0.0.0 Safari/537.36"

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
 *     这是 HttpOnly 会话唯一可行的取数方式
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
    var status by remember { mutableStateOf("") }
    var desktopMode by remember { mutableStateOf(false) }
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

    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // 内核真实 UA —— 我们发给站点的 UA 由它派生，内核版本也从它判断
    val defaultUa = remember {
        runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull()
    }
    val engineMajor = remember(defaultUa, providerInfo) {
        engineMajorOf(defaultUa) ?: engineMajorOf(providerInfo)
    }
    val mobileUa = remember(defaultUa) { mobileUaFrom(defaultUa) }
    val desktopUa = remember(engineMajor) { desktopUaFrom(engineMajor) }
    val engineTooOld = engineMajor != null && engineMajor < MODERN_ENGINE_FLOOR

    LaunchedEffect(initialUrl, startUrl, providerInfo, engineMajor) {
        Log.i(
            TAG,
            "engineChrome=$engineMajor floor=$MODERN_ENGINE_FLOOR provider=$providerInfo " +
                "start=${startUrl} effective=${initialUrl}",
        )
    }

    val loggedInText = stringResource(R.string.login_state_logged_in)
    val notLoggedInText = stringResource(R.string.login_state_unknown)
    val fetchFailedText = stringResource(R.string.login_fetch_failed)
    val openBrowserText = stringResource(R.string.action_open_browser)
    val desktopText = stringResource(R.string.login_desktop_mode)
    val mobileText = stringResource(R.string.login_mobile_mode)
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
     * 发起一轮新加载（刷新 / 切桌面版）。复位上一轮的判定，并让看门狗重新计时。
     * `responded = false` 必须在 loadUrl/reload **之前**同步做掉，见上面 effect 的注释。
     */
    fun startNewLoad(wv: WebView, reload: Boolean) {
        responded = false
        noResponse = false
        sslFailed = false
        looksBlank = false
        status = ""
        if (reload) wv.reload() else wv.loadUrl(currentUrl.ifBlank { startUrl })
        loadGen += 1
        Log.i(TAG, "load start (reload=$reload) url=${currentUrl.ifBlank { startUrl }}")
    }

    /** 诊断信息：让用户直接复制出来贴进 issue，字段名保持英文，不随语言变 */
    fun diagnostics(): String = buildString {
        append("app=").append(BuildConfig.VERSION_NAME)
        append("\nandroid=").append(android.os.Build.VERSION.SDK_INT)
        append("\nwebview=").append(providerInfo ?: "missing")
        append("\nengine_chrome=").append(engineMajor?.toString() ?: "?")
        append("\nengine_floor=").append(MODERN_ENGINE_FLOOR)
        append("\nua_sent=").append(if (desktopMode) desktopUa else mobileUa)
        append("\nurl=").append(currentUrl)
        append("\nstatus=").append(status.ifBlank { "-" })
        append("\nblank=").append(if (looksBlank) "yes" else "no")
        append("\nssl_error=").append(if (sslFailed) "yes" else "no")
        append("\nno_response=").append(if (noResponse) "yes" else "no")
        append("\nsetup_error=").append(setupError.ifBlank { "-" })
        append("\nconsole=").append(consoleError.ifBlank { "-" })
    }

    fun copyDiagnostics() {
        val text = diagnostics()
        Log.i(TAG, text.replace('\n', ' '))
        clipboard.setText(AnnotatedString(text))
        diagCopied = true
    }

    // 「已复制」提示两秒后自动消失，不影响下一轮操作
    LaunchedEffect(diagCopied) {
        if (diagCopied) {
            delay(2000)
            diagCopied = false
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
                IconButton(onClick = { webView.value?.let { startNewLoad(it, reload = true) } }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                }
                IconButton(onClick = { immersive = true }) {
                    Icon(Icons.Default.Fullscreen, contentDescription = stringResource(R.string.login_fullscreen))
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
                            WebView(context)
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
                            settings.userAgentString = if (desktopMode) desktopUa else mobileUa
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
                                    progress = newProgress
                                }

                                /** 记录第一条脚本错误：白屏大多是脚本挂了 */
                                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                                    val text = msg?.message().orEmpty()
                                    if (consoleError.isBlank() &&
                                        (msg?.messageLevel() == ConsoleMessage.MessageLevel.ERROR || text.contains("error", true))
                                    ) {
                                        consoleError = text.take(120)
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
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                    transport.webView = view
                                    resultMsg.sendToTarget()
                                    return true
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    if (url != null) currentUrl = url
                                    progress = 10
                                    responded = true
                                    Log.i(TAG, "onPageStarted $url")
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (url != null) {
                                        currentUrl = url
                                        CookieManager.getInstance().getCookie(url)?.let { cookie = it }
                                    }
                                    progress = 100
                                    // 空白页检测：内容太少说明布局/脚本没跑起来。
                                    // 用独立标志而不是写进 status —— 写进 status 会把下面
                                    // 「内核过旧 / 脚本执行失败」这类更具体的判断盖掉，
                                    // 而它们才是白屏的原因。
                                    view?.evaluateJavascript(
                                        "(function(){return String((document.body&&document.body.innerText||'').trim().length);})()"
                                    ) { len ->
                                        val n = len?.trim('"')?.toIntOrNull() ?: 0
                                        looksBlank = n < 40
                                        if (looksBlank) {
                                            Log.w(TAG, "page looks blank (innerText=$n) url=$currentUrl")
                                        }
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        responded = true
                                        status = error?.description?.toString() ?: fetchFailedText
                                        Log.w(TAG, "onReceivedError ${error?.errorCode}: $status")
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
                                    if (request?.isForMainFrame == true) {
                                        responded = true
                                        status = "HTTP ${errorResponse?.statusCode ?: 0}"
                                        Log.w(TAG, "onReceivedHttpError $status")
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
                                    if (url.startsWith("http") || url.startsWith("about:")) return false
                                    openInBrowser(url)
                                    return true
                                }

                                @Deprecated("Deprecated in Java")
                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    if (url.isNullOrBlank()) return false
                                    if (url.startsWith("http") || url.startsWith("about:")) return false
                                    openInBrowser(url)
                                    return true
                                }
                            }
                            // 渲染进程被回收后重建时用 currentUrl，回到崩溃时所在的页面
                            // 而不是入口页（否则登录到一半会被打回首页）
                            loadUrl(currentUrl.ifBlank { startUrl })
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

        if (immersive) {
            // 全屏时只留一个悬浮小按钮，其余空间全给网页
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { immersive = false }) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = stringResource(R.string.login_exit_fullscreen))
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
                            capture(wv, fetchUrl, cookie, currentUrl) { c, body ->
                                onCaptured(c, body)
                            }
                        },
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
                    sslFailed || noResponse || isGoogle || engineTooOld || engineBroken
                Text(
                    text = when {
                        providerInfo == null -> webViewMissing
                        setupError.isNotBlank() -> stringResource(R.string.login_setup_failed, setupError)
                        sslFailed -> sslErrorText
                        status.isNotBlank() -> status
                        engineBroken -> stringResource(R.string.login_script_failed, consoleError)
                        engineTooOld -> engineOldText ?: blankHint
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
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 适应宽度：桌面版控制台一键缩到屏幕内
                    CompactAction(
                        icon = { Icon(Icons.Default.ZoomOutMap, contentDescription = fitText) },
                        label = fitText,
                        onClick = { webView.value?.let { fitToWidth(it) } },
                    )
                    CompactAction(
                        icon = {
                            Icon(
                                if (desktopMode) Icons.Default.PhoneAndroid else Icons.Default.DesktopWindows,
                                contentDescription = if (desktopMode) mobileText else desktopText,
                            )
                        },
                        label = if (desktopMode) mobileText else desktopText,
                        onClick = {
                            desktopMode = !desktopMode
                            webView.value?.let { wv ->
                                wv.settings.userAgentString = if (desktopMode) desktopUa else mobileUa
                                startNewLoad(wv, reload = true)
                            }
                        },
                    )
                    CompactAction(
                        icon = { Icon(Icons.Default.Save, contentDescription = stringResource(R.string.action_login_save_cookie)) },
                        label = stringResource(R.string.action_login_save_cookie),
                        onClick = { onCaptured(cookie.takeIf { it.isNotBlank() }, null) },
                    )
                    CompactAction(
                        icon = { Icon(Icons.Default.OpenInBrowser, contentDescription = openBrowserText) },
                        label = openBrowserText,
                        onClick = { openInBrowser(webView.value?.url ?: currentUrl) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val wv = webView.value
                        if (wv == null || fetchUrl.isBlank()) {
                            status = fetchFailedText
                            return@Button
                        }
                        busy = true
                        status = ""
                        capture(wv, fetchUrl, cookie, currentUrl) { c, body ->
                            busy = false
                            if (body == null) status = fetchFailedText else onCaptured(c, body)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
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

/** API 29–32 的强制暗色同样会把页面刷黑，统一关掉（更高版本用 ALGORITHMIC_DARKENING 分支） */
@Suppress("DEPRECATION")
private fun applyForceDarkOff(settings: WebSettings) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
        WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
    }
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
    webView: WebView,
    fetchUrl: String,
    currentCookie: String,
    currentUrl: String,
    onResult: (cookie: String?, json: String?) -> Unit,
) {
    webView.evaluateJavascript(jsFetch(fetchUrl)) { raw ->
        val payload = decodeJsString(raw)
        if (payload == null) {
            onResult(currentCookie.takeIf { it.isNotBlank() }, null)
            return@evaluateJavascript
        }
        val body = payload.optString("body")
        val code = payload.optInt("status")
        val ok = runCatching { JSONObject(body) }.isSuccess
        if (code in 200..299 && ok) {
            val fresh = CookieManager.getInstance().getCookie(webView.url ?: currentUrl)
                ?: currentCookie
            onResult(fresh.takeIf { it.isNotBlank() }, body)
        } else {
            onResult(currentCookie.takeIf { it.isNotBlank() }, null)
        }
    }
}

/** 在页面上下文里请求额度接口：同源请求，自动携带登录态 */
private fun jsFetch(url: String): String {
    val urlLiteral = JSONObject.quote(url)
    return """
        (function () {
          return fetch($urlLiteral, {
            credentials: "include",
            headers: { "Accept": "application/json, text/plain, */*" }
          }).then(function (r) {
            return r.text().then(function (t) {
              return JSON.stringify({ status: r.status, body: t });
            });
          }).catch(function (e) {
            return JSON.stringify({ status: 0, body: String((e && e.message) || e) });
          });
        })()
    """.trimIndent()
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
