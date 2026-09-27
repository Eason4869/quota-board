package com.yusheng.quota.net

import org.json.JSONObject
import java.io.Closeable
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One isolated Xiaomi QR login attempt. Never uses the process/WebView cookie store. */
internal class MimoLoginSession(
    private val transport: ((URI, String, Int) -> Response)? = null,
) : Closeable {
    data class Response(val status: Int, val bytes: ByteArray, val headers: Map<String, List<String>>) {
        val text: String get() = bytes.toString(Charsets.UTF_8)
    }
    data class Ticket(val imageUrl: String, val browserUrl: String, val pollUrl: String)

    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<HttpURLConnection?>()

    fun begin(): Ticket {
        val unauthorized = json(get(MimoEndpoints.USAGE))
        val login = trustedUrl(unauthorized.getString("loginUrl")).toString()
        val descriptor = json(get(login + if ('?' in login) "&_json=true" else "?_json=true"))
        val ticket = json(get(qrEndpoint(descriptor.getString("location"))))
        check(ticket.optInt("code", -1) == 0) { "MiMo: 无法创建登录请求" }
        // The official QR page forwards _json unchanged. Removing it changes the
        // ticket's callback contract and makes Xiaomi's confirmation page reject it.
        return Ticket(
            trustedUrl(ticket.getString("qr")).toString(),
            browserLoginUrl(ticket.getString("loginUrl")),
            trustedUrl(ticket.getString("lp")).toString(),
        )
    }

    fun image(ticket: Ticket): ByteArray {
        val response = get(ticket.imageUrl)
        check(response.status == 200) { "MiMo: 无法加载二维码" }
        return response.bytes
    }

    fun awaitApproval(ticket: Ticket): String {
        // This is one server-held request, not a busy polling loop. Official page uses 315 s.
        val approved = json(get(ticket.pollUrl, 315_000))
        check(approved.optInt("code", -1) == 0) { "MiMo: 登录请求已过期或未获批准，请重试" }
        get(approved.getString("location"))
        val cookie = cookieFor(URI(MimoEndpoints.USAGE))
        check(MimoEndpoints.hasSession(cookie)) { "MiMo: 未取得平台会话，请重新连接" }
        return cookie
    }

    fun quota(cookie: String, timeoutSec: Int = 20): JSONObject {
        require(MimoEndpoints.hasSession(cookie)) { "MiMo: Cookie 缺少有效的平台会话" }
        require('\r' !in cookie && '\n' !in cookie) { "MiMo: Cookie 格式错误" }
        return MimoEndpoints.fetch(cookie) { url, saved ->
            // Fixed quota endpoints only; imported credentials never follow a redirect.
            val uri = URI(url)
            val result = request(uri, saved, timeoutSec.coerceIn(2, 60) * 1000)
            PageFetch.Fetched(url, result.status, result.text)
        }
    }

    private fun get(url: String, timeout: Int = 20_000): Response {
        var uri = trustedUrl(url)
        repeat(10) {
            val response = request(uri, cookieFor(uri), timeout)
            cookies.put(uri, response.headers)
            if (response.status !in listOf(301, 302, 303, 307, 308)) return response
            val location = response.headers.entries.firstOrNull { it.key.equals("Location", true) }
                ?.value?.firstOrNull() ?: error("MiMo: 登录跳转无效")
            uri = trustedUrl(uri.resolve(location).toString())
        }
        error("MiMo: 登录跳转过多")
    }

    private fun cookieFor(uri: URI): String = cookies.get(uri, emptyMap()).entries
        .filter { it.key.equals("Cookie", true) }.flatMap { it.value }.joinToString("; ")

    private fun request(uri: URI, cookie: String, timeout: Int): Response {
        check(!closed.get()) { "MiMo: 登录已取消" }
        transport?.let { return it(uri, cookie, timeout) }
        val conn = uri.toURL().openConnection() as HttpURLConnection
        active.set(conn)
        try {
            check(!closed.get()) { "MiMo: 登录已取消" }
            conn.instanceFollowRedirects = false
            conn.connectTimeout = minOf(timeout, 20_000)
            conn.readTimeout = timeout
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 QuotaBoard")
            if (uri.host == "platform.xiaomimimo.com") {
                conn.setRequestProperty("Origin", MimoEndpoints.HOST)
                conn.setRequestProperty("Referer", "${MimoEndpoints.HOST}/console/plan-manage")
            }
            if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
            val status = conn.responseCode
            val headers = conn.headerFields.filterKeys { it != null }
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 2 * 1024 * 1024) { "MiMo: 响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            return Response(status, bytes, headers)
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
        internal fun trustedUrl(value: String): URI {
            val uri = URI(value)
            val host = uri.host?.lowercase().orEmpty()
            require(uri.scheme in listOf("https", "http") && uri.rawUserInfo == null &&
                uri.port in listOf(-1, 443) && (host == "platform.xiaomimimo.com" ||
                host == "account.xiaomi.com" || host.endsWith(".account.xiaomi.com"))) {
                "MiMo: 登录地址不属于官方服务"
            }
            return if (uri.scheme == "http") URI("https" + value.substring(4)) else uri
        }

        internal fun qrEndpoint(location: String): String {
            val uri = trustedUrl(location)
            require(uri.host == "account.xiaomi.com") { "MiMo: 登录参数无效" }
            val query = uri.rawQuery.orEmpty().split('&').filter {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") != "_"
            }.joinToString("&")
            return "https://account.xiaomi.com/longPolling/loginUrl?$query"
        }

        internal fun browserLoginUrl(value: String): String {
            val uri = trustedUrl(value)
            val parameters = uri.rawQuery.orEmpty().split('&').filter {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") != "_json"
            }
            val base = uri.toString().substringBefore('?')
            return if (parameters.isEmpty()) base else "$base?${parameters.joinToString("&")}"
        }

        private fun json(response: Response): JSONObject =
            JSONObject(response.text.removePrefix("&&&START&&&"))
    }
}
