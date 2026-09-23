package com.yusheng.quota.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yusheng.quota.R
import org.json.JSONObject

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

    Column(Modifier.fillMaxSize()) {
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
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (url != null) {
                                    currentUrl = url
                                    CookieManager.getInstance().getCookie(url)?.let { cookie = it }
                                }
                                progress = 100
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
