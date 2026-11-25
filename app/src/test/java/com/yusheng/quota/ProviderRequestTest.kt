package com.yusheng.quota

import com.yusheng.quota.data.QueryConfig
import com.yusheng.quota.data.QueryMode
import com.yusheng.quota.data.Templates
import com.yusheng.quota.net.QueryEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderRequestTest {
    private data class Request(val path: String, val method: String, val auth: String?, val body: String)

    private fun server(reply: (Request) -> String, run: (String) -> Unit) {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: SocketException) {
                    if (server.isClosed) break else throw e
                }
                socket.use {
                    it.soTimeout = 5_000
                    val input = it.getInputStream().bufferedReader(Charsets.UTF_8)
                    val start = input.readLine().split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val body = CharArray(headers["content-length"]?.toInt() ?: 0)
                    var offset = 0
                    while (offset < body.size) {
                        val read = input.read(body, offset, body.size - offset)
                        check(read > 0) { "Truncated request" }
                        offset += read
                    }
                    val bytes = reply(Request(start[1].substringBefore('?'), start[0], headers["authorization"], String(body)))
                        .toByteArray(Charsets.UTF_8)
                    it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                }
            }
        }
        try { run("http://127.0.0.1:${server.localPort}") } finally {
            server.close()
            try { task.get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
    }

    private fun query(vendor: String, url: String, project: String = "") = runBlocking {
        QueryEngine(RuntimeEnvironment.getApplication()).query(Templates.byId(vendor),
            QueryConfig(mode = QueryMode.API, url = url, apiKey = "test-key", projectId = project))
    }

    @Test fun minimaxBusinessFailureContinuesToLegacyEndpoint() {
        val paths = mutableListOf<String>()
        server({ request ->
            paths += request.path
            assertEquals("GET", request.method)
            assertEquals("Bearer test-key", request.auth)
            if (request.path == "/v1/token_plan/remains")
                """{"base_resp":{"status_code":1004,"status_msg":"endpoint unavailable"}}"""
            else """{"base_resp":{"status_code":0},"model_remains":[{"model_name":"general","current_interval_remaining_percent":75}]}"""
        }) { base ->
            val result = query("minimax", "$base/v1/token_plan/remains")
            assertEquals(25.0, result.periods.single().usedPercent!!, 0.0)
            assertEquals(listOf("/v1/token_plan/remains", "/v1/api/openplatform/coding_plan/remains"), paths)
        }
    }

    @Test fun zhipuOnlyFallsBackForExplicitNoPlanAndUsesRawKey() {
        val paths = mutableListOf<String>()
        server({ request ->
            paths += request.path
            assertEquals("test-key", request.auth)
            if (request.path == "/quota") """{"success":false,"code":500,"msg":"当前用户不存在coding plan"}"""
            else """{"success":true,"data":{"availableBalance":12.5,"balance":20}}"""
        }) { base ->
            assertEquals(12.5, query("zhipu", "$base/quota").balance!!.amount, 0.0)
            assertEquals(listOf("/quota", "/api/biz/account/query-customer-account-report"), paths)
        }
    }

    @Test fun zhipuOtherBusinessErrorsDoNotBecomeBalanceQueries() {
        var count = 0
        server({ count++; """{"success":false,"msg":"invalid key"}""" }) { base ->
            assertThrows(IllegalStateException::class.java) { query("zhipu", "$base/quota") }
            assertEquals(1, count)
        }
    }

    @Test fun geminiDiscoversProjectBeforeFetchingSummary() {
        val paths = mutableListOf<String>()
        server({ request ->
            paths += request.path
            assertEquals("POST", request.method)
            assertEquals("Bearer test-key", request.auth)
            if (request.path.endsWith(":loadCodeAssist")) {
                assertEquals("ANTIGRAVITY", JSONObject(request.body).getJSONObject("metadata").getString("ideType"))
                """{"cloudaicompanionProject":{"id":"managed-project"},"currentTier":{"id":"free-tier"}}"""
            } else {
                assertEquals("managed-project", JSONObject(request.body).getString("project"))
                """{"groups":[{"buckets":[{"bucketId":"gemini-5h","remainingFraction":0.6}]}]}"""
            }
        }) { base ->
            val result = query("gemini", "$base/v1internal:retrieveUserQuotaSummary")
            assertEquals(40.0, result.periods.single().usedPercent!!, 0.001)
            assertEquals(listOf("/v1internal:loadCodeAssist", "/v1internal:retrieveUserQuotaSummary"), paths)
        }
    }

    @Test fun candidateValidationDoesNotDiscardExplicitFieldMappings() {
        for (vendor in listOf("siliconflow", "minimax", "novita", "gemini")) {
            var requests = 0
            server({ requests++; """{"data":{"money":12.5}}""" }) { base ->
                val result = runBlocking {
                    QueryEngine(RuntimeEnvironment.getApplication()).query(Templates.byId(vendor),
                        QueryConfig(mode = QueryMode.API, url = "$base/custom", apiKey = "test-key", mapBalance = "data.money"))
                }
                assertEquals(vendor, 12.5, result.balance!!.amount, 0.0)
                assertEquals(vendor, 1, requests)
            }
        }
    }
}
