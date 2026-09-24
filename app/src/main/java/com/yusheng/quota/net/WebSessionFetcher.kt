package com.yusheng.quota.net

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 用「隐藏 WebView」在页面上下文里取数。
 *
 * 为什么需要它：控制台会话多是 HttpOnly Cookie，App 自己的 HTTP 客户端带不上；
 * 而 WebView 的 CookieJar 是 **App 级共享且持久** 的 —— 用户在内置登录页登录过一次之后，
 * 后续的普通「查询」就能靠它取数，不必每次都打开登录页。
 *
 * 做法：先加载一个**同源轻量页**（不启动 SPA）建立 origin，再在里面 fetch 目标接口。
 */
class WebSessionFetcher(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(
        originProbe: String,
        urls: List<String>,
        timeoutMs: Long = 25_000,
    ): List<PageFetch.Fetched>? = withContext(Dispatchers.Main) {
        val webView = runCatching {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                // 与登录页保持一致：只去掉 wv 标记，保留内核真实版本号
                val real = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
                if (!real.isNullOrBlank()) {
                    settings.userAgentString = real
                        .replace("; wv)", ")")
                        .replace(Regex("\\sVersion/\\d+(\\.\\d+)*"), "")
                        .replace(Regex("\\swv\\b"), "")
                }
            }
        }.getOrNull() ?: return@withContext null

        try {
            val loaded = CompletableDeferred<Unit>()
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.complete(Unit)
                }
            }
            runCatching { webView.loadUrl(originProbe) }
            // 同源页拿不到也要继续：有些站点 robots.txt 被拦，但 origin 仍然建立了
            withTimeoutOrNull(12_000) { loaded.await() }
            PageFetch.run(webView, urls, timeoutMs)
        } finally {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }
}
