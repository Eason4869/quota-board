package com.yusheng.quota.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.yusheng.quota.BuildConfig
import com.yusheng.quota.R
import org.json.JSONObject

/** 手机 UA：去掉系统 WebView 的 `wv` 标记，站点才会给出可登录的移动页 */
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

/** 桌面 UA：控制台只有桌面版布局时使用 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

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
    var currentUrl by remember { mutableStateOf(startUrl) }
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
    val progressAlpha by animateFloatAsState(if (progress in 1..99) 1f else 0f, label = "loginProgress")

    val ctx = LocalContext.current

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
            val scale = (visibleCss / pageWidth * 100f).toInt().coerceIn(25, 150)
            wv.setInitialScale(scale)
            status = "$fitText ${scale}%"
            wv.reload()
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
                IconButton(onClick = { webView.value?.reload() }) {
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
                        WebView(context).apply {
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
                            settings.userAgentString = if (desktopMode) DESKTOP_UA else MOBILE_UA
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
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (url != null) {
                                        currentUrl = url
                                        CookieManager.getInstance().getCookie(url)?.let { cookie = it }
                                    }
                                    progress = 100
                                    status = ""
                                    // 空白页检测：内容太少说明布局/脚本没跑起来，给出手动兜底提示
                                    view?.evaluateJavascript(
                                        "(function(){return String((document.body&&document.body.innerText||'').trim().length);})()"
                                    ) { len ->
                                        val n = len?.trim('"')?.toIntOrNull() ?: 0
                                        if (n < 40) status = blankHint
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        status = error?.description?.toString() ?: fetchFailedText
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        status = "HTTP ${errorResponse?.statusCode ?: 0}"
                                    }
                                }

                                /** 渲染进程被系统回收时会白屏 / 只画一半，重建 WebView 并回到当前页 */
                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?,
                                ): Boolean {
                                    val back = view?.url ?: currentUrl
                                    runCatching { view?.destroy() }
                                    webView.value = null
                                    webViewKey += 1
                                    currentUrl = back
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
                            loadUrl(startUrl)
                            webView.value = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
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
                Text(
                    text = when {
                        providerInfo == null -> webViewMissing
                        status.isNotBlank() -> status
                        isGoogle -> googleBlockedText
                        cookie.isNotBlank() -> loggedInText
                        else -> notLoggedInText
                    },
                    fontSize = 11.sp,
                    color = if (providerInfo == null || isGoogle) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (providerInfo != null) {
                    Text(
                        buildString {
                            append("WebView: $providerInfo")
                            if (consoleError.isNotBlank()) append(" · JS: $consoleError")
                        },
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
                                wv.settings.userAgentString = if (desktopMode) DESKTOP_UA else MOBILE_UA
                                wv.reload()
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
