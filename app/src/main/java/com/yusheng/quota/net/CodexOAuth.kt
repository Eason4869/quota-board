package com.yusheng.quota.net

import com.yusheng.quota.data.CodexCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class CodexHttpException(val status: Int) : IllegalStateException("Codex HTTP $status")
internal class CodexAuthException(val reconnect: Boolean = false) : IllegalStateException(
    if (reconnect) "Codex authorization expired; reconnect with OAuth." else "Codex authorization failed; check network and retry."
)

/** Official Codex public-client device flow; never handles the user's password or browser cookies. */
internal class CodexOAuth(
    private val proxyUrl: String = "",
    private val request: ((String, String, String) -> Response)? = null,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    data class Response(val status: Int, val body: String)
    data class Ticket(val userCode: String, val deviceId: String, val intervalMs: Long, val expiresAtMs: Long) {
        val browserUrl: String get() = "$ISSUER/codex/device"
    }
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<HttpURLConnection?>()

    fun begin(): Ticket {
        val response = post("$ISSUER/api/accounts/deviceauth/usercode",
            JSONObject().put("client_id", CLIENT_ID).toString())
        val json = successful(response)
        val deviceId = text(json, "device_auth_id") ?: throw CodexAuthException()
        val code = text(json, "user_code") ?: text(json, "usercode") ?: throw CodexAuthException()
        return Ticket(code, deviceId, json.optLong("interval", 5).coerceIn(1, 60) * 1000,
            clock() + 15 * 60 * 1000)
    }

    suspend fun awaitApproval(ticket: Ticket): CodexCredentials {
        var interval = ticket.intervalMs
        while (!closed.get() && clock() < ticket.expiresAtMs) {
            wait(minOf(interval, ticket.expiresAtMs - clock()).coerceAtLeast(1))
            if (closed.get() || clock() >= ticket.expiresAtMs) break
            val response = withContext(Dispatchers.IO) {
                post("$ISSUER/api/accounts/deviceauth/token", JSONObject()
                    .put("device_auth_id", ticket.deviceId).put("user_code", ticket.userCode).toString())
            }
            when (response.status) {
                403, 404 -> continue // Official pending responses, not an OAuth token yet.
                429 -> { interval = (interval + 5000).coerceAtMost(60_000); continue }
            }
            val code = successful(response)
            val verifier = text(code, "code_verifier") ?: throw CodexAuthException()
            val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8)))
            checkAuth(text(code, "code_challenge") == expected)
            val authorizationCode = text(code, "authorization_code") ?: throw CodexAuthException()
            return withContext(Dispatchers.IO) {
                tokenRequest(mapOf("grant_type" to "authorization_code", "client_id" to CLIENT_ID,
                    "code" to authorizationCode, "code_verifier" to verifier,
                    "redirect_uri" to "$ISSUER/deviceauth/callback"))
            }
        }
        throw CodexAuthException()
    }

    fun refresh(previous: CodexCredentials): CodexCredentials = tokenRequest(
        mapOf("grant_type" to "refresh_token", "client_id" to CLIENT_ID, "refresh_token" to previous.refreshToken), previous)

    private fun tokenRequest(fields: Map<String, String>, previous: CodexCredentials? = null): CodexCredentials {
        // Codex exchanges authorization codes as a form, but renews grants with JSON.
        val response = if (previous != null) {
            post("$ISSUER/oauth/token", JSONObject(fields).toString())
        } else post("$ISSUER/oauth/token", fields.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }, "application/x-www-form-urlencoded")
        if (response.status in listOf(400, 401, 403)) throw CodexAuthException(reconnect = previous != null)
        val json = successful(response)
        val access = text(json, "access_token") ?: throw CodexAuthException()
        val refresh = text(json, "refresh_token") ?: previous?.refreshToken ?: throw CodexAuthException()
        val claims = jwtClaims(text(json, "id_token")) ?: jwtClaims(access)
        val account = text(claims?.optJSONObject("https://api.openai.com/auth"), "chatgpt_account_id")
            ?: previous?.accountId ?: throw CodexAuthException()
        checkAuth(previous == null || previous.accountId == account)
        val expiresIn = json.optLong("expires_in", 0).takeIf { it in 1..31_536_000 }
        val expiresAt = expiresIn?.let { clock() + it * 1000 }
            ?: jwtClaims(access)?.optLong("exp", 0)?.takeIf { it > 0 && it < Long.MAX_VALUE / 1000 }?.times(1000)
            ?: (clock() + 3600_000)
        return CodexCredentials(previous?.sessionId ?: UUID.randomUUID().toString(), access, refresh, account, expiresAt)
    }

    private fun successful(response: Response): JSONObject {
        if (response.status !in 200..299) throw CodexAuthException()
        return try { JSONObject(response.body) } catch (_: Exception) { throw CodexAuthException() }
    }

    private fun post(url: String, body: String, contentType: String = "application/json"): Response {
        if (closed.get()) throw CodexAuthException()
        try {
            request?.let { return it(url, body, contentType) }
            val proxy = CodexProxy.parse(proxyUrl)
            val target = URI(url).toURL()
            val conn = (if (proxy == null) target.openConnection() else target.openConnection(proxy)) as HttpURLConnection
            active.set(conn)
            try {
                if (closed.get()) throw CodexAuthException()
                conn.requestMethod = "POST"
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Content-Type", contentType)
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                // Error bodies can contain credentials; only the status is needed.
                val text = if (status in 200..299) conn.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (output.size() + size > 1024 * 1024) throw CodexAuthException()
                        output.write(buffer, 0, size)
                    }
                    output.toString("UTF-8")
                } else ""
                return Response(status, text)
            } finally {
                active.compareAndSet(conn, null)
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw CodexAuthException()
        }
    }

    override fun close() { closed.set(true); active.getAndSet(null)?.disconnect() }

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun text(json: JSONObject?, key: String): String? =
            (json?.opt(key) as? String)?.takeIf { it.isNotBlank() }
        private fun checkAuth(valid: Boolean) { if (!valid) throw CodexAuthException() }
        private fun jwtClaims(token: String?): JSONObject? = runCatching {
            JSONObject(String(Base64.getUrlDecoder().decode(token!!.split('.')[1]), Charsets.UTF_8))
        }.getOrNull()
    }
}
