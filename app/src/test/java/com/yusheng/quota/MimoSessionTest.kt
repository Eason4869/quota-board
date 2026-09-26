package com.yusheng.quota

import com.yusheng.quota.net.MimoEndpoints
import com.yusheng.quota.net.PageFetch
import org.junit.Assert.*
import org.junit.Test

class MimoSessionTest {
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
