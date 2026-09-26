package com.yusheng.quota

import com.yusheng.quota.net.MimoEndpoints
import com.yusheng.quota.net.PageFetch
import org.junit.Assert.*
import org.junit.Test

class MimoSessionTest {
    @Test fun payAsYouGoStillLoadsWhenPlanIsUnavailable() {
        val result = MimoEndpoints.fetch("userId=A") { url, _ ->
            if (url == MimoEndpoints.USAGE) throw java.io.IOException("plan unavailable")
            PageFetch.Fetched(url, 200, if (url == MimoEndpoints.BALANCE)
                """{"code":0,"data":{"balance":12.5}}""" else """{"code":500,"data":null}""")
        }.getJSONObject("mimo")
        assertEquals(12.5, result.getJSONObject("balance").getJSONObject("data").getDouble("balance"), 0.0)
        assertFalse(result.has("usage"))
        assertEquals(2, result.getJSONArray("unavailable").length())
    }
    @Test fun businessErrorsAndEmptyDataAreNotQuota() {
        for (body in listOf("""{"code":500,"data":{}}""", """{"code":0,"data":null}""", "{}")) {
            assertThrows(IllegalStateException::class.java) {
                MimoEndpoints.fetch("userId=A") { url, _ -> PageFetch.Fetched(url, 200, body) }
            }
        }
    }
    @Test fun cancellationDoesNotContinueToOtherEndpoints() {
        var calls = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            MimoEndpoints.fetch("userId=A") { _, _ -> calls++; throw kotlinx.coroutines.CancellationException() }
        }
        assertEquals(1, calls)
    }
    @Test fun sessionRequiresExactNonemptyCookieNames() {
        assertFalse(MimoEndpoints.hasSession("other=api-platform_serviceToken; userId=1"))
        assertFalse(MimoEndpoints.hasSession("api-platform_serviceToken=; userId=1"))
        assertFalse(MimoEndpoints.hasSession("api-platform_serviceToken=abc"))
        assertTrue(MimoEndpoints.hasSession("api-platform_serviceToken=abc; userId=1"))
    }
    @Test fun eachAccountUsesItsSavedCookieForAllEndpoints() {
        val requests = mutableListOf<Pair<String, String>>()
        for (cookie in listOf("userId=A; api-platform_serviceToken=first", "userId=B; api-platform_serviceToken=second")) {
            val result = MimoEndpoints.fetch(cookie) { url, sentCookie ->
                requests += url to sentCookie
                PageFetch.Fetched(url, 200, """{"code":0,"data":{"cookie":"$sentCookie"}}""")
            }
            assertEquals(cookie, result.getJSONObject("mimo").getJSONObject("usage").getJSONObject("data").getString("cookie"))
        }
        assertEquals(listOf(MimoEndpoints.USAGE, MimoEndpoints.DETAIL, MimoEndpoints.BALANCE), requests.take(3).map { it.first })
        assertTrue(requests.take(3).all { it.second.contains("userId=A") })
        assertTrue(requests.drop(3).all { it.second.contains("userId=B") })
    }
    @Test fun absentCookieDoesNotFallBackToGlobalWebSession() {
        assertThrows(IllegalArgumentException::class.java) {
            MimoEndpoints.fetch("") { _, _ -> error("must not request") }
        }
    }
    @Test fun expiredSessionIsNotAcceptedAsAZeroBalance() {
        assertThrows(IllegalStateException::class.java) {
            MimoEndpoints.fetch("userId=A") { url, _ ->
                PageFetch.Fetched(url, 200, """{"code" : 401,"data":null}""")
            }
        }
    }
}
