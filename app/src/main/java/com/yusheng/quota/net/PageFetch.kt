package com.yusheng.quota.net

import android.webkit.WebView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.util.UUID
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 在**页面上下文里**请求接口：同源 + 自动携带登录态。
 *
 * fetch 自动携带页面会话。evaluateJavascript 不会等待 Promise，
 * 因此用每次请求独立的结果槽轮询完成状态，并在结束时中止请求、清理槽。
 */
object PageFetch {

    data class Fetched(val url: String, val status: Int, val body: String) {
        val isOk: Boolean get() = status in 200..299
        fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
    }

    /** 一次性请求多个同源接口（避免多次 evaluateJavascript 的往返） */
    internal fun js(urls: List<String>, key: String, accept: String = "application/json, text/plain, */*"): String {
        val list = urls.joinToString(",") { JSONObject.quote(it) }
        val acceptLiteral = JSONObject.quote(accept)
        val keyLiteral = JSONObject.quote(key)
        return """
            (function () {
              var urls = [$list];
              var key = $keyLiteral;
              var slot = { result: null, controller: new AbortController() };
              window[key] = slot;
              Promise.all(urls.map(function (u) {
                return fetch(u, { credentials: "include", signal: slot.controller.signal, headers: { "Accept": $acceptLiteral } })
                  .then(function (r) {
                    return r.text().then(function (t) {
                      return { url: u, status: r.status, body: t };
                    });
                  })
                  .catch(function (e) {
                    return { url: u, status: 0, body: String((e && e.message) || e) };
                  });
              })).then(function (all) {
                if (window[key] === slot) slot.result = JSON.stringify(all);
              });
              return true;
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
    ): List<Fetched>? = run(urls, timeoutMs) { script, callback ->
        webView.evaluateJavascript(script, callback)
    }

    internal suspend fun run(
        urls: List<String>,
        timeoutMs: Long,
        evaluate: (String, (String?) -> Unit) -> Unit,
    ): List<Fetched>? {
        val key = "__quotaFetch_" + UUID.randomUUID().toString().replace("-", "")
        val literal = JSONObject.quote(key)
        suspend fun eval(script: String): String? = suspendCancellableCoroutine { cont ->
            try {
                evaluate(script) { raw -> if (cont.isActive) cont.resume(raw) }
            } catch (e: Exception) {
                cont.cancel(e)
            }
        }
        try {
            return withTimeoutOrNull(timeoutMs) {
                eval(js(urls, key))
                var result: List<Fetched>? = null
                while (result == null) {
                    result = decode(eval("window[$literal] ? window[$literal].result : null"))
                    if (result == null) delay(100)
                }
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            // No suspending cleanup: cancellation must also remove the page state.
            runCatching {
                evaluate("(function(){var s=window[$literal];delete window[$literal];if(s)s.controller.abort();})()") {}
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
