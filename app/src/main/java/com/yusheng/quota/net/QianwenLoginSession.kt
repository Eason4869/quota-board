package com.yusheng.quota.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** QianWen's published CLI device authorization and Token Plan billing protocol. */
internal class QianwenLoginSession(
    private val request: ((url: String, body: String, authorization: String?) -> String)? = null,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : Closeable {
    data class Ticket(
        val browserUrl: String,
        val token: String,
        val verifier: String,
        val clientId: String,
        val intervalMs: Long,
        val expiresAtMs: Long,
    )

    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<HttpURLConnection?>()

    fun begin(): Ticket {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val clientId = UUID.randomUUID().toString()
        val json = post("https://t.qianwenai.com/cli/device/code?client_id=$clientId&code_challenge=$challenge&code_challenge_method=S256")
        check(json.optBoolean("Success") && json.optJSONObject("Data") != null) { "千问：无法创建登录请求" }
        val data = json.getJSONObject("Data")
        val browser = URI(data.getString("VerificationUrl"))
        require(browser.scheme == "https" && browser.userInfo == null && browser.port == -1 &&
            (browser.host == "qianwenai.com" || browser.host?.endsWith(".qianwenai.com") == true)) {
            "千问：授权地址不是官方服务"
        }
        return Ticket(
            browser.toString(), data.getString("Token"), verifier, clientId,
            data.optLong("Interval", 5).coerceIn(1, 30) * 1000,
            System.currentTimeMillis() + data.optLong("ExpiresIn", 300).coerceIn(1, 900) * 1000,
        )
    }

    suspend fun awaitApproval(ticket: Ticket): String {
        var interval = ticket.intervalMs
        while (!closed.get() && System.currentTimeMillis() < ticket.expiresAtMs) {
            wait(interval)
            if (closed.get() || System.currentTimeMillis() >= ticket.expiresAtMs) break
            val url = "https://t.qianwenai.com/cli/device/token?client_id=${ticket.clientId}" +
                "&token=${encode(ticket.token)}&code_verifier=${encode(ticket.verifier)}"
            val data = withContext(Dispatchers.IO) { post(url) }.optJSONObject("Data")
                ?: error("千问：授权响应无效")
            when (data.optString("Status").lowercase()) {
                "complete" -> {
                    val credentials = data.optJSONObject("Credentials") ?: error("千问：未收到访问凭据")
                    val token = credentials.optString("AccessToken")
                    check(token.isNotBlank()) { "千问：未收到访问凭据" }
                    return JSONObject().put("access_token", token)
                        .put("expires_at", credentials.optString("ExpireTime")).toString()
                }
                "authorization_pending" -> Unit
                "slow_down" -> interval = (interval + 5000).coerceAtMost(30_000)
                "expired_token", "access_denied" -> error("千问：授权已过期或被拒绝，请重试")
                else -> error("千问：未知授权状态")
            }
        }
        error("千问：授权已过期或取消")
    }

    fun quota(credential: String): JSONObject {
        val parsed = runCatching { JSONObject(credential) }.getOrNull()
            ?: error("千问：请使用浏览器授权，不支持用模型 API Key 查询额度")
        val token = parsed.optString("access_token")
        check(token.isNotBlank()) { "千问：请使用浏览器授权，不支持用模型 API Key 查询额度" }
        val expiry = parsed.optString("expires_at")
        if (expiry.isNotBlank()) {
            val expired = runCatching { Instant.parse(expiry).toEpochMilli() < System.currentTimeMillis() }.getOrDefault(false)
            check(!expired) { "千问：登录已过期，请重新连接" }
        }
        val root = JSONObject()
        val unavailable = org.json.JSONArray()
        for ((name, code, size) in listOf(
            Triple("personal", "sfm_tokenplanpersonal_dp_cn", "10"),
            Triple("teams", "sfm_tokenplanteams_dp_cn", "10"),
            Triple("addon", "sfm_tokenplanteamsaddon_dp_cn", "100"),
        )) {
            val body = JSONObject().put("product", "BssOpenAPI-V3")
                .put("action", "DescribeFrInstances").put("region", "cn-beijing")
                .put("params", JSONObject().put("Group", "tokenPlan")
                    .put("CommodityCode", code).put("PageNum", "1").put("PageSize", size))
            try {
                val response = post("https://cli.qianwenai.com/data/v2/api.json", body.toString(), "Bearer $token")
                check(response.optString("code") == "200") { "千问：额度接口返回业务错误" }
                root.put(name, response.getJSONObject("data"))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                unavailable.put(name)
            }
        }
        check(root.has("personal") || root.has("teams")) { "千问：未取得 Token Plan 数据，请检查授权状态" }
        if (unavailable.length() > 0) root.put("unavailable", unavailable)
        return JSONObject().put("qianwen", root)
    }

    private fun post(url: String, body: String = "", authorization: String? = null): JSONObject {
        check(!closed.get()) { "千问：登录已取消" }
        request?.let { return JSONObject(it(url, body, authorization)) }
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        active.set(conn)
        try {
            check(!closed.get()) { "千问：登录已取消" }
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "qianwen-cli/1.9.0")
            if (body.isNotEmpty()) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            authorization?.let { conn.setRequestProperty("Authorization", it) }
            val status = conn.responseCode
            if (status !in 200..299 && url.contains("/cli/device/token")) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText().take(2048) }.orEmpty()
                val signal = listOf("expired_token", "access_denied", "slow_down")
                    .firstOrNull { detail.contains(it, ignoreCase = true) }
                if (signal != null) return JSONObject().put("Data", JSONObject().put("Status", signal))
            }
            check(status in 200..299) {
                if (status == 401 || status == 403) "千问：登录已失效，请重新连接"
                else "千问：请求失败 ($status)"
            }
            val bytes = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 2 * 1024 * 1024) { "千问：响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            active.compareAndSet(conn, null)
            conn.disconnect()
        }
    }

    override fun close() {
        closed.set(true)
        active.getAndSet(null)?.disconnect()
    }

    companion object {
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
