package com.yusheng.quota.net

import android.content.Context
import android.util.Base64
import com.yusheng.quota.R
import com.yusheng.quota.data.QueryConfig
import com.yusheng.quota.data.QueryMode
import com.yusheng.quota.data.QueryResult
import com.yusheng.quota.data.Template
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 查询引擎：原生 HTTP 直连厂商接口。
 *
 * 设计要点：
 *  1. 网页型响应先尝试从 HTML 里抠出内嵌 JSON（__NEXT_DATA__ / __INITIAL_STATE__ 等）
 *  2. AK/SK 直连失败时回退到云函数代理
 *  3. AK/SK 允许粘贴 base64 包裹的凭证（控制台复制出来常常是这种）
 *  4. 配置了自定义提取器时优先用提取器结果，失败再回退内置解析
 */
class QueryEngine(private val context: Context) {

    private val scriptExtractor: ScriptExtractor by lazy { ScriptExtractor(context) }
    private val webSession: WebSessionFetcher by lazy { WebSessionFetcher(context) }

    suspend fun query(
        template: Template,
        cfg: QueryConfig,
        defaultTimeoutSec: Int = 10,
    ): QueryResult = withContext(Dispatchers.IO) {
        val timeout = (cfg.timeoutSec.takeIf { it > 0 } ?: defaultTimeoutSec).coerceIn(2, 60)

        val json: JSONObject = when (cfg.mode) {
            QueryMode.AKSK -> volcWithFallback(template.id, cfg, timeout)
            QueryMode.WEBHOOK -> requestJson(
                method = "POST",
                url = cfg.webhookUrl.ifBlank { cfg.url },
                headers = webhookHeaders(cfg),
                body = webhookBody(template.id, cfg),
                timeoutSec = timeout,
                mode = QueryMode.WEBHOOK,
                cfg = cfg,
            )
            // 小米 MiMo 的会话是 HttpOnly Cookie，普通 HTTP 客户端带不上，
            // 必须回到「页面上下文」里取（登录过一次后 CookieJar 里就有会话了）
            QueryMode.LOGIN -> if (MimoEndpoints.isMimo(cfg.url)) {
                mimoSessionFetch(cfg, timeout)
            } else {
                requestJson(
                    method = cfg.method.ifBlank { "GET" },
                    url = cfg.url,
                    headers = loginHeaders(cfg),
                    body = jsonBodyIfPost(cfg.method),
                    timeoutSec = timeout,
                    mode = QueryMode.LOGIN,
                )
            }
            QueryMode.API -> {
                if (template.id == "siliconflow") {
                    siliconFlowWithFallback(cfg, timeout)
                } else if (template.id == "openai") {
                    openAiUsageWithFallback(cfg, timeout)
                } else {
                    requestJson(
                        method = cfg.method.ifBlank { "GET" },
                        url = apiUrl(template.id, cfg),
                        headers = apiHeaders(template.id, cfg),
                        body = jsonBodyIfPost(cfg.method),
                        timeoutSec = timeout,
                        mode = QueryMode.API,
                    )
                }
            }
        }

        runScriptExtractor(cfg, json)
            ?: Parsers.parse(template.id, json, cfg.mapBalance, cfg.mapPlan, context)
    }


    /**
     * 硅基流动：国内站与国际站是两套域名，同一个 Key 通常在其中一个站点有效。
     * 依次尝试「用户填的地址 → 国内站 → 国际站 → 控制台接口」，
     * 并把每个候选的真实错误都带出来，避免只看到最后一条误导性的失败信息。
     */
    private suspend fun siliconFlowWithFallback(cfg: QueryConfig, timeoutSec: Int): JSONObject {
        val consoleUrl = "https://account.siliconflow.cn/api/user/balance"
        val candidates = LinkedHashSet<String>().apply {
            add(cfg.url)
            add("https://api.siliconflow.cn/v1/user/info")
            add("https://api.siliconflow.com/v1/user/info")
            if (cfg.cookie.isNotBlank()) add(consoleUrl)
        }.filter { it.isNotBlank() }

        val errors = mutableListOf<String>()
        var unauthorized = false

        for (url in candidates) {
            val isConsole = url.contains("account.siliconflow.cn")
            try {
                return requestJson(
                    method = "GET",
                    url = url,
                    headers = if (isConsole) loginHeaders(cfg) else apiHeaders("siliconflow", cfg),
                    body = "",
                    timeoutSec = timeoutSec,
                    mode = if (isConsole) QueryMode.LOGIN else QueryMode.API,
                )
            } catch (e: Exception) {
                val m = e.message.orEmpty()
                if (m.contains("401") || m.contains("403") || m.contains("invalid", true)) {
                    unauthorized = true
                }
                errors += "$url → ${m.ifBlank { "未知错误" }}"
            }
        }

        val hint = if (unauthorized) {
            "\n\n请检查 API Key 是否有效，以及账号属于哪个站点：\n" +
                "· 国内站：api.siliconflow.cn（在 cloud.siliconflow.cn 创建的 Key）\n" +
                "· 国际站：api.siliconflow.com（在 siliconflow.com 创建的 Key）\n" +
                "把「查询 URL」改成对应站点即可。控制台余额接口不认 API Key，需要改用「登录拉取」。"
        } else {
            ""
        }
        throw IllegalStateException(errors.joinToString("\n") + hint)
    }

    // ── 自定义提取器（在 WebView 里执行配置的 JS）──────────
    private suspend fun runScriptExtractor(cfg: QueryConfig, json: JSONObject): QueryResult? {
        val code = cfg.script
        if (code.isBlank()) return null
        val vars = mapOf(
            "baseUrl" to cfg.url.ifBlank { cfg.webhookUrl },
            "apiKey" to cfg.apiKey.ifBlank { cfg.secretAccessKey }.ifBlank { cfg.cookie },
        )
        return runCatching { scriptExtractor.extract(code, json.toString(), vars) }.getOrNull()
    }

    /**
     * 小米 MiMo：在隐藏 WebView 的页面上下文里取「用量 / 详情 / 余额」三个接口。
     *
     * 三个接口统一信封 `{code,data}`；未登录时回 `{"code":401,"loginUrl":...}`，
     * 这时要明确告诉用户去「登录取数」，而不是丢一个看不懂的 JSON 出去。
     */
    private suspend fun mimoSessionFetch(cfg: QueryConfig, timeoutSec: Int): JSONObject {
        val urls = MimoEndpoints.endpointsFor(cfg.url) ?: MimoEndpoints.ALL
        val results = webSession.fetch(
            originProbe = MimoEndpoints.originProbeFor(cfg.url),
            urls = urls,
            timeoutMs = (timeoutSec.coerceIn(5, 60)) * 1000L,
        )
        val usage = results?.firstOrNull { it.url.contains("tokenPlan/usage") }
        if (usage != null && MimoEndpoints.isNotLoggedIn(usage.body)) {
            throw IllegalStateException(
                context.getString(R.string.err_mimo_need_login, MimoEndpoints.REQUIRED_COOKIES)
            )
        }
        val merged = results?.let { MimoEndpoints.merge(it) }
        return merged ?: throw IllegalStateException(
            context.getString(R.string.err_mimo_fetch_failed, MimoEndpoints.REQUIRED_COOKIES)
        )
    }

    // ── 火山 AK/SK 签名查询 ────────────────────────────────
    // 与 cc-switch 一致：Agent Plan 与 Coding Plan 共用同一份 AK/SK，
    // 先按模板顺序探测两个 Action，签名/鉴权错误立刻停止，不再试另一个。
    private fun volcWithFallback(templateId: String, cfg: QueryConfig, timeoutSec: Int): JSONObject {
        val ak = maybeDecodeBase64(cfg.accessKeyId)
        val sk = maybeDecodeBase64(cfg.secretAccessKey)
        require(ak.isNotBlank() && sk.isNotBlank()) { context.getString(R.string.err_need_aksk) }
        val region = cfg.region.ifBlank { "cn-beijing" }
        val actions = if (templateId == "volc_agent") {
            listOf("GetAFPUsage", "GetCodingPlanUsage")
        } else {
            listOf("GetCodingPlanUsage", "GetAFPUsage")
        }

        val direct = runCatching {
            var lastBody: JSONObject? = null
            val errors = mutableListOf<String>()
            for (action in actions) {
                try {
                    val body = volcOpenApiCall(action, ak, sk, region, timeoutSec)
                    val parsed = Parsers.parse(templateId, body, "", "", context)
                    if (parsed.periods.isNotEmpty()) return@runCatching body
                    lastBody = body
                } catch (e: VolcAuthException) {
                    throw e // 凭证问题，另一个 plan 一样会失败
                } catch (e: Exception) {
                    errors += "$action: ${e.message}"
                }
            }
            // 签名没问题但两个 plan 都没解析出额度：按 cc-switch 的做法把原始响应带出来，方便核对字段
            if (lastBody != null) {
                throw IllegalStateException(
                    context.getString(R.string.err_volc_no_plan, lastBody.toString().take(200))
                )
            }
            throw IllegalStateException(
                errors.joinToString("; ").ifBlank { context.getString(R.string.err_volc_failed) }
            )
        }
        direct.getOrNull()?.let { return it }

        // 鉴权类失败不再兜底到代理（换个 plan 也一样失败）
        (direct.exceptionOrNull() as? VolcAuthException)?.let { throw it }

        if (cfg.webhookUrl.isNotBlank()) {
            val action = actions.first()
            return requestJson(
                method = "POST",
                url = cfg.webhookUrl,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                body = JSONObject()
                    .put("action", action)
                    .put("accessKeyId", cfg.accessKeyId)
                    .put("secretAccessKey", cfg.secretAccessKey)
                    .put("region", region)
                    .toString(),
                timeoutSec = timeoutSec,
                mode = QueryMode.AKSK,
            )
        }
        throw direct.exceptionOrNull() ?: IllegalStateException(context.getString(R.string.err_volc_failed))
    }

    /** 鉴权类失败：换 plan 也没用，直接透出提示 */
    private class VolcAuthException(message: String) : IllegalStateException(message)

    /** 单次火山 OpenAPI 调用：业务错误（含 200 + ResponseMetadata.Error）统一在这里抛出 */
    private fun volcOpenApiCall(
        action: String,
        ak: String,
        sk: String,
        region: String,
        timeoutSec: Int,
    ): JSONObject {
        val signed = VolcSigner.sign(action, ak, sk, region)
        val raw = httpRequest("POST", signed.url, signed.headers, "", timeoutSec)
        val body = runCatching { JSONObject(raw) }.getOrElse {
            throw IllegalStateException(context.getString(R.string.err_volc_not_json, raw.take(120)))
        }
        // 火山 OpenAPI 的业务错误常以 200 + ResponseMetadata.Error 返回
        val err = body.optJSONObject("ResponseMetadata")?.optJSONObject("Error")
            ?: body.optJSONObject("Error")
        if (err != null) {
            val code = err.optString("Code")
            val msg = err.optString("Message")
            val detail = context.getString(R.string.err_volc_api, code, msg)
            if (isAuthErrorCode(code)) {
                throw VolcAuthException("$detail ${context.getString(R.string.err_volc_auth_hint)}")
            }
            throw IllegalStateException(detail)
        }
        return body
    }

    private fun isAuthErrorCode(code: String): Boolean {
        val c = code.lowercase()
        return listOf("auth", "signature", "accessdenied", "denied", "unauthorized", "forbidden", "credential", "token")
            .any { c.contains(it) }
    }

    // ── 请求头组装 ─────────────────────────────────────────
    /**
     * 智谱团队版：同一个 quota 路径 + `?type=2`，另外必须带组织 ID / 项目 ID 两个请求头
     * （与 cc-switch `query_zhipu_team` 一致，组织与项目缺一不可）。
     */
    private fun apiUrl(templateId: String, cfg: QueryConfig): String {
        if (templateId != "zhipu") return cfg.url
        if (cfg.orgId.isBlank() || cfg.projectId.isBlank()) return cfg.url
        val sep = if (cfg.url.contains("?")) "&" else "?"
        return if (cfg.url.contains("type=")) cfg.url else "${cfg.url}$sep" + "type=2"
    }

    private fun apiHeaders(templateId: String, cfg: QueryConfig): Map<String, String> {
        val headers = mutableMapOf("Accept" to "application/json")
        if (cfg.apiKey.isNotBlank()) {
            // 智谱使用裸 Key，不加 Bearer
            headers["Authorization"] =
                if (templateId == "zhipu") cfg.apiKey else "Bearer ${cfg.apiKey}"
        }
        when (templateId) {
            "zhipu" -> {
                headers["Accept-Language"] = "en-US,en"
                if (cfg.orgId.isNotBlank()) headers["bigmodel-organization"] = cfg.orgId.trim()
                if (cfg.projectId.isNotBlank()) headers["bigmodel-project"] = cfg.projectId.trim()
            }
            // Claude Code 的 OAuth 令牌走官方用量接口，必须带这个 beta 头
            "claude" -> headers["anthropic-beta"] = "oauth-2025-04-20"
            // Codex / ChatGPT 的 access token 需要配套账号 ID 与 CLI UA
            "openai" -> {
                headers["User-Agent"] = "codex-cli"
                if (cfg.accountId.isNotBlank()) headers["chatgpt-account-id"] = cfg.accountId.trim()
            }
        }
        if (cfg.method.equals("POST", true)) headers["Content-Type"] = "application/json"
        return headers
    }

    /**
     * ChatGPT / Codex 用量。
     *
     * 官方 Codex CLI 调 `backend-api/wham/usage`；社区实现也有走 `backend-api/codex/usage` 的，
     * 两个都试，谁先成功用谁。凭据是 CLI 落盘的 access token + 账号 ID，**不需要浏览器**。
     */
    private suspend fun openAiUsageWithFallback(cfg: QueryConfig, timeoutSec: Int): JSONObject {
        val candidates = LinkedHashSet<String>().apply {
            add(cfg.url)
            add("https://chatgpt.com/backend-api/wham/usage")
            add("https://chatgpt.com/backend-api/codex/usage")
        }.filter { it.isNotBlank() }

        val errors = mutableListOf<String>()
        for (url in candidates) {
            try {
                return requestJson("GET", url, apiHeaders("openai", cfg), "", timeoutSec, QueryMode.API)
            } catch (e: Exception) {
                errors += "${url.substringAfter("chatgpt.com")} → ${e.message}"
            }
        }
        throw IllegalStateException(errors.joinToString("\n"))
    }

    private fun loginHeaders(cfg: QueryConfig): Map<String, String> {
        val headers = mutableMapOf(
            "Accept" to "application/json, text/plain, */*",
            // 控制台接口大多会拦掉非浏览器 UA
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; QuotaBoard/1.1) AppleWebKit/537.36",
        )
        if (cfg.cookie.isNotBlank()) headers["Cookie"] = cfg.cookie
        if (cfg.method.equals("POST", true)) headers["Content-Type"] = "application/json"
        return headers
    }

    private fun webhookHeaders(cfg: QueryConfig): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
        if (cfg.whAuthValue.isNotBlank()) {
            when (cfg.whAuthType) {
                "bearer" -> headers[cfg.whAuthKey.ifBlank { "Authorization" }] =
                    cfg.whAuthPrefix.ifBlank { "Bearer " } + cfg.whAuthValue
                "header" -> headers[cfg.whAuthKey.ifBlank { "Authorization" }] =
                    cfg.whAuthPrefix + cfg.whAuthValue
                else -> Unit // query 走 URL 拼参
            }
        }
        return headers
    }

    private fun jsonBodyIfPost(method: String): String =
        if (method.equals("POST", true)) "{}" else ""

    /** 火山代理约定：把 AK/SK 放在 body 里交给你的函数签名 */
    private fun webhookBody(templateId: String, cfg: QueryConfig): String =
        if (templateId == "volc_agent" || templateId == "volc_coding") {
            JSONObject()
                .put("action", if (templateId == "volc_agent") "GetAFPUsage" else "GetCodingPlanUsage")
                .put("accessKeyId", cfg.accessKeyId)
                .put("secretAccessKey", cfg.secretAccessKey)
                .put("region", cfg.region.ifBlank { "cn-beijing" })
                .toString()
        } else ""

    private fun withQueryAuth(url: String, cfg: QueryConfig): String {
        if (cfg.whAuthType != "query" || cfg.whAuthValue.isBlank()) return url
        val sep = if (url.contains("?")) "&" else "?"
        val key = URLEncoder.encode(cfg.whAuthKey.ifBlank { "token" }, "UTF-8")
        val value = URLEncoder.encode(cfg.whAuthValue, "UTF-8")
        return "$url$sep$key=$value"
    }

    // ── 取 JSON（含网页型响应兜底）──────────────────────────
    private fun requestJson(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutSec: Int,
        mode: QueryMode,
        cfg: QueryConfig? = null,
    ): JSONObject {
        val target = if (cfg != null) withQueryAuth(url.trim(), cfg) else url.trim()
        require(target.isNotBlank()) {
            context.getString(
                if (mode == QueryMode.WEBHOOK) R.string.err_need_webhook else R.string.err_need_url
            )
        }
        val text = httpRequest(method, target, headers, body, timeoutSec)
        return toJsonObject(text, mode)
    }

    private fun toJsonObject(text: String, mode: QueryMode): JSONObject {
        if (text.isBlank()) throw IllegalStateException(context.getString(R.string.err_empty_response))
        runCatching { return JSONObject(text) }
        extractJsonFromHtml(text)?.let { return it }
        if (mode == QueryMode.LOGIN) {
            throw IllegalStateException(context.getString(R.string.err_not_json_login))
        }
        throw IllegalStateException(context.getString(R.string.err_not_json, text.take(80)))
    }

    /** 从 HTML 里抠出内嵌 JSON（SSR 的控制台页面很常见） */
    private fun extractJsonFromHtml(html: String): JSONObject? {
        val patterns = listOf(
            Regex("""window\.__INITIAL_STATE__\s*=\s*(\{[\s\S]*?\});?\s*</script>"""),
            Regex("""window\.__NUXT__\s*=\s*(\{[\s\S]*?\});?\s*</script>"""),
            Regex("""id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>"""),
            Regex("""window\.__DATA__\s*=\s*(\{[\s\S]*?\});?\s*</script>"""),
        )
        for (re in patterns) {
            val m = re.find(html) ?: continue
            val raw = m.groupValues.getOrNull(1) ?: continue
            runCatching { return JSONObject(raw) }
        }
        val keys = listOf("credits", "credit", "quota", "balance", "remaining", "afp", "usage")
        for (match in Regex("""\{[^{}]{60,4000}\}""").findAll(html)) {
            val candidate = match.value
            val lower = candidate.lowercase()
            if (keys.any { lower.contains(it) }) {
                runCatching { return JSONObject(candidate) }
            }
        }
        return null
    }

    // ── 原生 HTTP ──────────────────────────────────────────
    private fun httpRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutSec: Int,
    ): String {
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }
            .getOrElse { throw IllegalStateException(context.getString(R.string.err_bad_url, url)) }
        conn.apply {
            requestMethod = method
            connectTimeout = timeoutSec.coerceIn(2, 60) * 1000
            readTimeout = timeoutSec.coerceIn(2, 60) * 1000
            instanceFollowRedirects = true
            headers.forEach { (k, v) ->
                val name = k.filter { it.code in 0x20..0x7E }.trim()
                val value = v.filter { it.code in 0x20..0x7E }.trim()
                if (name.isNotEmpty() && value.isNotEmpty()) setRequestProperty(name, value)
            }
            if (body.isNotEmpty()) doOutput = true
        }

        if (body.isNotEmpty()) {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""

        if (code !in 200..299) {
            val msg = runCatching {
                JSONObject(text).let { j ->
                    j.optJSONObject("ResponseMetadata")?.optJSONObject("Error")?.optString("Message")
                        ?.takeIf { it.isNotBlank() }
                        ?: j.optString("message").takeIf { it.isNotBlank() }
                        ?: j.optString("msg").takeIf { it.isNotBlank() }
                        ?: j.optString("error").takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            throw IllegalStateException(
                context.getString(
                    R.string.err_http,
                    code,
                    msg ?: text.take(200).ifBlank { context.getString(R.string.err_no_body) },
                )
            )
        }
        return text
    }

    /**
     * 控制台导出 / 复制出来的 AK、SK 有时外面还包了一层 base64，
     * 直接签名会得到 SignatureDoesNotMatch —— 这里做两层探测。
     */
    private fun maybeDecodeBase64(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return s
        if (Regex("""^(AKLT|AKID)[0-9a-zA-Z]{12,}$""").matches(s)) return s
        if (Regex("""^[0-9a-fA-F]{32,}$""").matches(s)) return s
        val looksBase64 = Regex("""^[A-Za-z0-9+/]+={0,2}$""").matches(s) && s.length % 4 == 0
        if (!looksBase64) return s
        var current = s
        repeat(2) {
            val decoded = runCatching {
                String(Base64.decode(current, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: return s
            if (!decoded.all { it.code in 0x20..0x7E }) return s
            val isKey = Regex("""^(AKLT|AKID)[0-9a-zA-Z]{12,}$""").matches(decoded) ||
                Regex("""^[0-9a-fA-F]{32,}$""").matches(decoded)
            if (isKey) return decoded
            val nested = Regex("""^[A-Za-z0-9+/]+={0,2}$""").matches(decoded) && decoded.length % 4 == 0
            if (!nested) return s
            current = decoded
        }
        return current
    }
}
