package com.yusheng.quota.net

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import com.yusheng.quota.data.Balance
import com.yusheng.quota.data.Period
import com.yusheng.quota.data.QueryResult
import com.yusheng.quota.data.Subscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 自定义提取器：把配置里的 JS 片段放进一个临时 WebView 里执行。
 *
 * 执行约定：
 *  - `{{baseUrl}}` / `{{apiKey}}` 先做变量替换
 *  - 代码求值出一个对象；若有 `extractor(response)` 就调用它，否则直接用这个对象
 *  - 结果按 remaining / total / used / unit / planName / periods / payg 归一化
 *
 * 只在主线程建 WebView，跑完立即销毁；任何异常都返回 null，让调用方回退内置解析。
 */
class ScriptExtractor(private val context: Context) {

    suspend fun extract(
        code: String,
        responseJson: String,
        vars: Map<String, String>,
    ): QueryResult? = withContext(Dispatchers.Main) {
        if (code.isBlank()) return@withContext null

        val webView = runCatching {
            @SuppressLint("SetJavaScriptEnabled")
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
            }
        }.getOrNull() ?: return@withContext null

        try {
            val loaded = CompletableDeferred<Unit>()
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.complete(Unit)
                }
            }
            runCatching { webView.loadUrl("about:blank") }
            withTimeoutOrNull(3_000) { loaded.await() }

            val raw = withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<String?> { cont ->
                    runCatching { webView.evaluateJavascript(buildScript(code, responseJson, vars)) { value ->
                        if (cont.isActive) cont.resume(value)
                    } }.onFailure {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            } ?: return@withContext null

            val payload = decodeJsString(raw) ?: return@withContext null
            val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return@withContext null
            if (obj.has("__qbError")) return@withContext null
            normalize(obj)
        } finally {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    /** evaluateJavascript 的返回值本身是 JSON 字面量，这里解一层拿到内层字符串 */
    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val inner = runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: return null
        return when (inner) {
            is String -> inner
            is JSONObject -> inner.toString()
            else -> null
        }
    }

    private fun buildScript(code: String, responseJson: String, vars: Map<String, String>): String {
        var body = code
        vars.forEach { (k, v) -> body = body.replace("{{$k}}", v) }
        val codeLiteral = JSONObject.quote(body)
        val responseLiteral = JSONObject.quote(responseJson)
        return """
            (function () {
              try {
                var response = JSON.parse($responseLiteral);
                var obj = (new Function("return (" + $codeLiteral + ")"))();
                var out = (obj && typeof obj.extractor === "function") ? obj.extractor(response) : obj;
                return JSON.stringify(out == null ? null : out);
              } catch (e) {
                return JSON.stringify({ __qbError: String((e && e.message) || e) });
              }
            })()
        """.trimIndent()
    }

    // ── 结果归一化 ─────────────────────────────────────────
    private fun normalize(o: JSONObject): QueryResult? {
        val plan = o.optJSONObject("plan")
        val payg = o.optJSONObject("payg")
        val unit = o.optString("unit").ifBlank { "Credits" }

        val remaining = num(o, "remaining") ?: num(plan, "remaining")
        val total = num(o, "total") ?: num(plan, "total")
        val used = num(o, "used") ?: num(plan, "used")

        val paygAmount = num(payg, "balance") ?: if (remaining != null && total == null) remaining else null
        val currency = payg?.optString("currency").orEmpty().ifBlank { unitToSymbol(unit) }

        val tier = o.optString("planName").ifBlank { o.optString("tier") }
            .ifBlank { plan?.optString("tier").orEmpty() }
        val resetAt = o.optString("resets_at").ifBlank {
            o.optString("resetAt").ifBlank { plan?.optString("resetAt").orEmpty() }
        }.ifBlank { null }

        val balance = paygAmount?.let { Balance(it, currency) }
        val subscription = if (tier.isNotBlank() || remaining != null || total != null) {
            Subscription(
                tier = tier.ifBlank { "-" },
                remaining = remaining,
                total = total ?: if (remaining != null && used != null) remaining + used else null,
                unit = unit,
                resetAt = resetAt,
            )
        } else null

        val periods = mutableListOf<Period>()
        o.optJSONArray("periods")?.let { arr -> periods += readPeriods(arr) }
        if (periods.isEmpty()) plan?.optJSONArray("periods")?.let { arr -> periods += readPeriods(arr) }

        if (balance == null && subscription == null && periods.isEmpty()) return null
        return QueryResult(balance = balance, subscription = subscription, periods = periods)
    }

    private fun readPeriods(arr: JSONArray): List<Period> {
        val out = mutableListOf<Period>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            out += Period(
                label = p.optString("label").ifBlank { p.optString("id").ifBlank { "w${i + 1}" } },
                used = num(p, "used"),
                total = num(p, "total"),
                usedPct = num(p, "usedPct", "used_pct", "percentage"),
                resetAt = p.optString("resetAt").ifBlank { p.optString("reset_at") }.ifBlank { null },
            )
        }
        return out
    }

    private fun num(o: JSONObject?, vararg keys: String): Double? {
        if (o == null) return null
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            when (val v = o.get(k)) {
                is Number -> return v.toDouble()
                is String -> v.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun unitToSymbol(unit: String): String = when (unit.uppercase()) {
        "USD", "$" -> "$"
        "CNY", "RMB", "¥" -> "¥"
        else -> unit
    }
}
