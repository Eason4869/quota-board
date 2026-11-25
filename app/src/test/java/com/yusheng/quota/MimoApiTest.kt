package com.yusheng.quota

import com.yusheng.quota.net.MimoApi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MimoApiTest {
    @Test fun tokenPlanKeyUsesDedicatedHostAndBearerWithoutCookies() {
        val result = MimoApi.fetch("  tp-test-key  ") { url, headers ->
            assertEquals("https://token-plan-cn.xiaomimimo.com/v1/platform/token_plan", url)
            assertEquals(mapOf("Authorization" to "Bearer tp-test-key", "Accept" to "application/json"), headers)
            JSONObject("""{"quota":{"total_credits":100,"remaining_credits":75}}""")
        }
        assertEquals(75.0, MimoApi.parse(result).subscription!!.remaining!!, 0.0)
    }

    @Test fun payAsYouGoKeyUsesBalanceHost() {
        val result = MimoApi.fetch("sk-test-key") { url, headers ->
            assertEquals("https://api.xiaomimimo.com/v1/platform/balance", url)
            assertEquals("Bearer sk-test-key", headers["Authorization"])
            JSONObject("""{"balance_infos":[{"currency":"USD","total_available":"12.50","frozen_balance":"2.00"}]}""")
        }
        assertEquals(10.5, MimoApi.parse(result).balance!!.amount, 0.0)
        assertEquals("$", MimoApi.parse(result).balance!!.currency)
    }

    @Test fun emptyKeyDoesNotSendRequestAndRejectedKeyDoesNotTryAnotherAccount() {
        var requests = 0
        assertThrows(IllegalArgumentException::class.java) {
            MimoApi.fetch(" ") { _, _ -> requests++; JSONObject() }
        }
        assertEquals(0, requests)
        assertThrows(IllegalStateException::class.java) {
            MimoApi.fetch("tp-test-key") { _, _ -> requests++; error("HTTP 401") }
        }
        assertEquals(1, requests)
    }
}
