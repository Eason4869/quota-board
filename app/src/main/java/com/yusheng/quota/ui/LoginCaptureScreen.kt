package com.yusheng.quota.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import com.yusheng.quota.R
import org.json.JSONObject

/** 纯 Chrome 手机 UA：去掉系统 WebView 的 `wv` 标记，避免站点识别后拒绝加载登录页 */
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

/**
 * 应用内登录 + 取数。
 *
 * 用 WebView 解决两件事：
 *  1. 登录后从 WebView 的 Cookie Jar 里取 Cookie（HttpOnly 之外的部分）
 *  2. **在页面内直接 fetch 额度接口** —— 同源 + 自动带登录态，
 *     这也是 HttpOnly 会话唯一可行的取数方式
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
    val webView = remember { mutableStateOf<WebView?>(null) }

    val loggedInText = stringResource(R.string.login_state_logged_in)
    val notLoggedInText = stringResource(R.string.login_state_unknown)
    val fetchFailedText = stringResource(R.string.login_fetch_failed)
    val openBrowserText = stringResource(R.string.action_open_browser)
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

    // 返回键先走网页历史，退无可退再关闭登录页
    BackHandler {
        val wv = webView.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onCancel()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.title_login),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    currentUrl,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { openInBrowser(webView.value?.url ?: currentUrl) }) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = openBrowserText)
            }
            IconButton(onClick = { webView.value?.reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        }

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
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
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        // 去掉 wv 标记 + 固定手机 UA，站点才会给出可登录的移动页
                        settings.userAgentString = MOBILE_UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                if (url != null) currentUrl = url
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (url != null) {
                                    currentUrl = url
                                    CookieManager.getInstance().getCookie(url)?.let { cookie = it }
                                }
                                progress = 100
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

                            @Deprecated("Deprecated in Java")
                            @Suppress("DEPRECATION")
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                if (url.isNullOrBlank()) return false
                                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) {
                                    return false
                                }
                                // 非 http(s) 交给系统（mailto / market / 自定义 scheme）
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

        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = status.ifBlank {
                    if (cookie.isNotBlank()) loggedInText else notLoggedInText
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { openInBrowser(webView.value?.url ?: currentUrl) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.height(0.dp))
                Text(openBrowserText)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onCaptured(cookie.takeIf { it.isNotBlank() }, null) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_login_save_cookie)) }

                Button(
                    onClick = {
                        val wv = webView.value
                        if (wv == null || fetchUrl.isBlank()) {
                            status = fetchFailedText
                            return@Button
                        }
                        busy = true
                        status = ""
                        wv.evaluateJavascript(jsFetch(fetchUrl)) { raw ->
                            busy = false
                            val payload = decodeJsString(raw)
                            if (payload == null) {
                                status = fetchFailedText
                                return@evaluateJavascript
                            }
                            val body = payload.optString("body")
                            val code = payload.optInt("status")
                            val ok = runCatching { JSONObject(body) }.isSuccess
                            if (code in 200..299 && ok) {
                                CookieManager.getInstance().getCookie(wv.url ?: currentUrl)?.let { cookie = it }
                                onCaptured(cookie.takeIf { it.isNotBlank() }, body)
                            } else {
                                status = "$fetchFailedText (HTTP $code)"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && fetchUrl.isNotBlank(),
                ) { Text(stringResource(R.string.action_login_fetch_now)) }
            }
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
