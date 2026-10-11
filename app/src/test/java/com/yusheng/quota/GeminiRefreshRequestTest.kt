package com.yusheng.quota

import com.yusheng.quota.net.QueryEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = QuotaApp::class)
class GeminiRefreshRequestTest {
    @Test fun refreshSendsEncodedOAuthFormBeforeReadingAccessToken() {
        val token = "1//test+token&= 中文"
        val request = exchange("HTTP/1.1 200 OK", "{\"access_token\":\"new-access-token\"}") { url ->
            assertEquals("new-access-token", QueryEngine(RuntimeEnvironment.getApplication()).refreshGeminiAccessToken(token, url))
        }
        assertTrue(request.first().startsWith("POST /token "))
        assertTrue(request.any { it.equals("Content-Type: application/x-www-form-urlencoded", ignoreCase = true) })
        val body = request.last().split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        assertEquals("refresh_token", body["grant_type"])
        assertEquals(token, body["refresh_token"])
        assertTrue(body["client_id"].orEmpty().endsWith(".apps.googleusercontent.com"))
        assertTrue(body["client_secret"].orEmpty().isNotBlank())
    }

    @Test fun oauthErrorRemainsReadableWithoutExposingTheRefreshToken() {
        exchange("HTTP/1.1 400 Bad Request", "{\"error\":\"invalid_grant\"}") { url ->
            try {
                QueryEngine(RuntimeEnvironment.getApplication()).refreshGeminiAccessToken("1//private-test-token", url)
                fail("Invalid grant must fail")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("invalid_grant"))
                assertFalse(e.message.orEmpty().contains("private-test-token"))
            }
        }
    }

    /** Real local HTTP exchange, including the output bytes rather than just the form builder. */
    private fun exchange(status: String, responseBody: String, query: (String) -> Unit): List<String> {
        ServerSocket(0).use { server ->
            server.soTimeout = 10_000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<List<String>> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val headers = mutableListOf<String>()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            headers += line
                        }
                        val length = headers.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val body = CharArray(length)
                        var offset = 0
                        while (offset < length) {
                            val read = input.read(body, offset, length - offset)
                            if (read == -1) throw AssertionError("Truncated OAuth form")
                            offset += read
                        }
                        val bytes = responseBody.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().write(("$status\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII) + bytes)
                        headers + String(body)
                    }
                }
                query("http://127.0.0.1:${server.localPort}/token")
                return received.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
