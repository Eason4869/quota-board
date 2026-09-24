package com.yusheng.quota.net

import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 在**页面上下文里**请求接口：同源 + 自动携带登录态。
 *
 * 控制台会话普遍是 HttpOnly Cookie —— `CookieManager.getCookie()` 拿不到，
 * App 自己的 HTTP 客户端也带不上，只有在页面里发 fetch 才是通的。
 * 这里把这段 JS 与回包解析抽出来，登录页和后台查询共用同一份实现。
 */
object PageFetch {

    data class Fetched(val url: String, val status: Int, val body: String) {
        val isOk: Boolean get() = status in 200..299
        fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
    }

    /** 一次性请求多个同源接口（避免多次 evaluateJavascript 的往返） */
    fun js(urls: List<String>, accept: String = "application/json, text/plain, */*"): String {
        val list = urls.joinToString(",") { JSONObject.quote(it) }
        val acceptLiteral = JSONObject.quote(accept)
        return """
            (function () {
              var urls = [$list];
              return Promise.all(urls.map(function (u) {
                return fetch(u, { credentials: "include", headers: { "Accept": $acceptLiteral } })
                  .then(function (r) {
                    return r.text().then(function (t) {
                      return { url: u, status: r.status, body: t };
                    });
                  })
                  .catch(function (e) {
                    return { url: u, status: 0, body: String((e && e.message) || e) };
                  });
              })).then(function (all) { return JSON.stringify(all); });
            })()
        """.trimIndent()
    }

    /** evaluateJavascript 的返回值是 JSON 字面量，解一层拿到结果数组 */
    fun decode(raw: String?): List<Fetched>? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val inner = runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: return null
        val text = when (inner) {
            is String -> inner
            else -> inner.toString()
        }
        val arr = runCatching { org.json.JSONArray(text) }.getOrNull() ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Fetched(
                url = o.optString("url"),
                status = o.optInt("status"),
                body = o.optString("body"),
            )
        }
    }

    /** 在已建好的 WebView 里跑一次抓取（必须在主线程调用） */
    suspend fun run(
        webView: WebView,
        urls: List<String>,
        timeoutMs: Long = 20_000,
    ): List<Fetched>? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<List<Fetched>?> { cont ->
            runCatching {
                webView.evaluateJavascript(js(urls)) { raw ->
                    if (cont.isActive) cont.resume(decode(raw))
                }
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /** 未登录时厂商会回一个带 loginUrl 的 401，这里把登录地址取出来 */
    fun loginUrlIn(body: String): String? =
        Regex("\"loginUrl\"\\s*:\\s*\"([^\"]+)\"")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
}
