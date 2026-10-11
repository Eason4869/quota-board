package com.yusheng.quota

import com.yusheng.quota.data.QueryConfig
import com.yusheng.quota.data.Account
import com.yusheng.quota.data.ImportCodec
import com.yusheng.quota.data.Settings
import com.yusheng.quota.data.Templates
import com.yusheng.quota.net.QueryEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.io.DataInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = QuotaApp::class)
class CodexProxyTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun accountProxySurvivesBackupRoundTrip() {
        val cfg = json.decodeFromString<QueryConfig>(
            """{"proxyUrl":"http://127.0.0.1:7890"}"""
        )
        val backup = ImportCodec.encode(listOf(Account("a", "openai", "Codex", query = cfg)), Settings())
        assertTrue("Proxy setting must be preserved in backup", backup.contains("http://127.0.0.1:7890"))
        assertEquals(cfg, ImportCodec.decode(backup).first.single().query)
    }

    @Test fun codexQueryUsesConfiguredHttpProxyAndKeepsAccountHeaders() = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 15_000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader()
                        val lines = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            lines += line
                        }
                        val body = "{\"balance\":12.5}"
                        socket.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray()
                        )
                        lines.joinToString("\n")
                    }
                }
                val cfg = json.decodeFromString<QueryConfig>(
                    """{"url":"http://127.0.0.1:1/usage","apiKey":"test-token","accountId":"test-account","timeoutSec":2,"proxyUrl":"http://127.0.0.1:${server.localPort}"}"""
                )
                val result = QueryEngine(RuntimeEnvironment.getApplication()).query(Templates.byId("openai"), cfg)
                assertEquals(12.5, result.balance!!.amount, 0.0)
                val headers = request.get(5, TimeUnit.SECONDS)
                assertTrue(headers.contains("GET http://127.0.0.1:1/usage"))
                assertTrue(headers.contains("Authorization: Bearer test-token", ignoreCase = true))
                assertTrue(headers.contains("chatgpt-account-id: test-account", ignoreCase = true))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun codexQueryUsesConfiguredSocks5Proxy() = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 15_000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        assertEquals(5, input.readUnsignedByte())
                        val methods = input.readUnsignedByte()
                        repeat(methods) { input.readUnsignedByte() }
                        output.write(byteArrayOf(5, 0)); output.flush()
                        assertEquals(5, input.readUnsignedByte())
                        assertEquals(1, input.readUnsignedByte())
                        input.readUnsignedByte()
                        val size = when (input.readUnsignedByte()) {
                            1 -> 4
                            3 -> input.readUnsignedByte()
                            4 -> 16
                            else -> throw AssertionError("Bad SOCKS address")
                        }
                        val address = ByteArray(size); input.readFully(address)
                        assertEquals(1, input.readUnsignedShort())
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1)); output.flush()
                        val reader = input.bufferedReader()
                        val first = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val body = "{\"balance\":8.0}"
                        output.write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray())
                        first
                    }
                }
                val cfg = QueryConfig(url = "http://127.0.0.1:1/usage", apiKey = "test-token", timeoutSec = 2,
                    proxyUrl = "socks5://127.0.0.1:${server.localPort}")
                val result = QueryEngine(RuntimeEnvironment.getApplication()).query(Templates.byId("openai"), cfg)
                assertEquals(8.0, result.balance!!.amount, 0.0)
                assertTrue(request.get(5, TimeUnit.SECONDS).startsWith("GET /usage"))
            } finally { executor.shutdownNow() }
        }
    }
}
