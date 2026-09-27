package com.yusheng.quota

import com.yusheng.quota.net.MimoLoginSession
import com.yusheng.quota.net.MimoEndpoints
import org.junit.Assert.*
import org.junit.Test

class MimoLoginTest {
    @Test fun unauthorizedResponseExposesDecodedLoginUrl() {
        val body = """{"code":401,"loginUrl":"https:\/\/account.xiaomi.com\/pass\/serviceLogin?sid=api-platform\u0026_json=true"}"""
        assertEquals(
            "https://account.xiaomi.com/pass/serviceLogin?sid=api-platform&_json=true",
            MimoEndpoints.loginUrlIn(body),
        )
    }

    @Test fun onlyOfficialHttpsTargetsCanReceiveSessionRequests() {
        for (url in listOf("https://evil.test", "https://account.xiaomi.com.evil.test", "https://evil.test@account.xiaomi.com", "file:///etc/passwd", "https://account.xiaomi.com:444/")) {
            assertThrows(IllegalArgumentException::class.java) { MimoLoginSession.trustedUrl(url) }
        }
        assertEquals("https://platform.xiaomimimo.com/sts?a=1", MimoLoginSession.trustedUrl("http://platform.xiaomimimo.com/sts?a=1").toString())
        assertEquals("c3.lp.account.xiaomi.com", MimoLoginSession.trustedUrl("https://c3.lp.account.xiaomi.com/lp/s?k=test").host)
    }

    @Test fun qrTicketPreservesOfficialJsonCallbackMode() {
        val url = MimoLoginSession.qrEndpoint("https://account.xiaomi.com/fe/service/login?sid=api-platform&_json=true&callback=https%3A%2F%2Fplatform.xiaomimimo.com%2Fsts&_sign=test")
        assertTrue(url.contains("_json=true"))
        assertTrue(url.contains("callback=https%3A%2F%2Fplatform.xiaomimimo.com%2Fsts"))
        assertTrue(url.contains("_sign=test"))
    }

    @Test fun approvalRedirectCapturesOnlyPlatformCookiesAndSessionsStayIsolated() {
        val requests = mutableListOf<Pair<String, String>>()
        fun session() = MimoLoginSession { uri, cookie, _ ->
            requests += uri.toString() to cookie
            when (uri.path) {
                "/api/v1/tokenPlan/usage" -> reply("""{"code":401,"loginUrl":"https://account.xiaomi.com/pass/serviceLogin?sid=api-platform"}""", 401)
                "/pass/serviceLogin" -> reply("""&&&START&&&{"location":"https://account.xiaomi.com/fe/service/login?sid=api-platform&_json=true"}""", cookies = listOf("passport=secret; Path=/; Secure"))
                "/longPolling/loginUrl" -> reply("""{"code":0,"qr":"https://account.xiaomi.com/pass/qr/login?t=x","loginUrl":"https://c3.account.xiaomi.com/longPolling/login?t=x&_json=true","lp":"https://c3.lp.account.xiaomi.com/lp/s?k=x"}""")
                "/lp/s" -> reply("""&&&START&&&{"code":0,"location":"http://platform.xiaomimimo.com/sts"}""")
                "/sts" -> reply("ok", cookies = listOf("api-platform_serviceToken=token; Path=/; Secure", "userId=123; Path=/; Secure"))
                else -> error("unexpected request")
            }
        }
        session().use { first ->
            val ticket = first.begin()
            assertEquals("https://c3.account.xiaomi.com/longPolling/login?t=x", ticket.browserUrl)
            val cookie = first.awaitApproval(ticket)
            assertTrue(MimoEndpoints.hasSession(cookie))
            assertFalse(cookie.contains("passport"))
        }
        session().use { it.begin() }
        assertTrue(requests.filter { it.first == MimoEndpoints.USAGE }.all { it.second.isEmpty() })
        assertTrue(requests.filter { it.first.contains("platform.xiaomimimo.com") }.all { !it.second.contains("passport") })
        assertTrue(requests.none { it.first.contains("/longPolling/login?") })
    }

    @Test fun expiredTicketIsNotSavedAsASession() {
        MimoLoginSession { _, _, _ -> reply("""{"code":70031}""") }.use { session ->
            assertThrows(IllegalStateException::class.java) {
                session.awaitApproval(MimoLoginSession.Ticket("https://account.xiaomi.com/qr", "https://account.xiaomi.com/login", "https://c3.lp.account.xiaomi.com/lp/s"))
            }
        }
    }

    @Test fun untrustedRedirectIsNeverRequested() {
        var calls = 0
        MimoLoginSession { _, _, _ ->
            calls++
            MimoLoginSession.Response(302, byteArrayOf(), mapOf("Location" to listOf("https://evil.test/steal")))
        }.use { session ->
            assertThrows(IllegalArgumentException::class.java) { session.begin() }
        }
        assertEquals(1, calls)
    }

    @Test fun disposedSessionCannotIssueRequests() {
        val session = MimoLoginSession { _, _, _ -> error("must not send after cancellation") }
        session.close()
        assertThrows(IllegalStateException::class.java) { session.begin() }
    }

    @Test fun importedCookieIsNotForwardedToLoginRedirect() {
        val targets = mutableListOf<String>()
        MimoLoginSession { uri, _, _ ->
            targets += uri.toString()
            MimoLoginSession.Response(302, byteArrayOf(), mapOf("Location" to listOf("https://account.xiaomi.com/")))
        }.use { session ->
            assertThrows(IllegalStateException::class.java) { session.quota("userId=1; api-platform_serviceToken=test") }
        }
        assertEquals(MimoEndpoints.ALL, targets)
    }

    private fun reply(body: String, status: Int = 200, cookies: List<String> = emptyList()) =
        MimoLoginSession.Response(status, body.toByteArray(), mapOf("Set-Cookie" to cookies))
}
