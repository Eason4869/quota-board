package com.yusheng.quota

import com.yusheng.quota.net.QianwenLoginSession
import com.yusheng.quota.net.QianwenQuota
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QianwenLoginTest {
    @Test fun deviceFlowUsesOnePkceIdentityAndStopsOnApproval() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val session = QianwenLoginSession(request = { url, body, _ ->
            calls += url to body
            when {
                url.contains("/cli/device/code") -> """{"Success":true,"Data":{"Token":"ticket","VerificationUrl":"https://platform.qianwenai.com/approve","ExpiresIn":300,"Interval":1}}"""
                url.contains("/cli/device/token") && calls.size == 2 -> """{"Success":true,"Data":{"Status":"authorization_pending"}}"""
                else -> """{"Success":true,"Data":{"Status":"complete","Credentials":{"AccessToken":"access","ExpireTime":"2099-01-01T00:00:00Z"}}}"""
            }
        }, wait = {})
        session.use {
            val ticket = it.begin()
            assertEquals("https://platform.qianwenai.com/approve", ticket.browserUrl)
            assertEquals("access", JSONObject(it.awaitApproval(ticket)).getString("access_token"))
        }
        assertEquals(3, calls.size)
        val client = Regex("client_id=([^&]+)")
        assertEquals(client.find(calls[0].first)?.groupValues?.get(1), client.find(calls[1].first)?.groupValues?.get(1))
        assertTrue(calls[0].first.contains("code_challenge_method=S256"))
        assertTrue(calls[1].first.contains("code_verifier="))
        assertFalse(calls[0].first.contains("code_verifier="))
    }

    @Test fun expiredTicketNeverBecomesCredential() = runTest {
        val session = QianwenLoginSession(request = { url, _, _ ->
            if (url.contains("/code")) """{"Success":true,"Data":{"Token":"t","VerificationUrl":"https://platform.qianwenai.com/approve","ExpiresIn":300,"Interval":1}}"""
            else """{"Success":true,"Data":{"Status":"expired_token"}}"""
        }, wait = {})
        session.use {
            val ticket = it.begin()
            try {
                it.awaitApproval(ticket)
                fail("Expired device ticket was accepted")
            } catch (_: IllegalStateException) { }
        }
    }

    @Test fun quotaUsesBearerAndKnownBillingActionsOnly() {
        val calls = mutableListOf<Pair<String, String>>()
        val session = QianwenLoginSession(request = { url, body, auth ->
            calls += body to auth.orEmpty()
            if (JSONObject(body).getJSONObject("params").getString("CommodityCode") == "sfm_tokenplanpersonal_dp_cn")
                """{"code":"200","data":{"Data":[{"Status":"valid","InitCapacityBaseValue":"100","CurrCapacityBaseValue":"40","TemplateName":"Personal"}]}}"""
            else """{"code":"200","data":{"Data":[]}}"""
        })
        session.use {
            val data = it.quota("""{"access_token":"secret","expires_at":"2099-01-01T00:00:00Z"}""")
            assertEquals("Personal", QianwenQuota.parse(data).subscription?.tier)
        }
        assertTrue(calls.all { it.second == "Bearer secret" })
        assertTrue(calls.all { JSONObject(it.first).getString("product") == "BssOpenAPI-V3" })
        assertEquals(3, calls.size)
        assertTrue(calls.all { JSONObject(it.first).getJSONObject("params").getString("PageSize").isNotEmpty() })
    }

    @Test fun failedBusinessResponseIsNotZeroCredits() {
        QianwenLoginSession(request = { _, _, _ -> """{"code":"403","data":{"Data":[]}}""" }).use {
            assertThrows(IllegalStateException::class.java) { it.quota("""{"access_token":"secret"}""") }
        }
    }

    @Test fun parserKeepsIndividualAndTeamRatherThanDroppingOne() {
        val root = JSONObject("""{"qianwen":{"personal":{"Data":[{"Status":"valid","InitCapacityBaseValue":"100","CurrCapacityBaseValue":"40","TemplateName":"Personal"}]},"teams":{"Data":[{"Status":{"Code":"valid"},"InitCapacityBaseValue":"200","CurrCapacityBaseValue":"75","TemplateName":"Team"}]},"addon":{"Data":[{"Status":"valid","InitCapacityBaseValue":"50","CurrCapacityBaseValue":"25","TemplateName":"Add-on"}]}}}""")
        val result = QianwenQuota.parse(root)
        assertEquals(300.0, result.subscription!!.total!!, 0.0)
        assertEquals(115.0, result.subscription!!.remaining!!, 0.0)
        assertEquals(3, result.extras.size)
    }

    @Test fun partialEmptyQuotaIsNotReportedAsNoPlan() {
        val root = JSONObject("""{"qianwen":{"teams":{"Data":[]},"unavailable":["personal"]}}""")
        val result = QianwenQuota.parse(root)
        assertEquals("Not retrieved", result.extras.first { it.label == "Token Plan" }.value)
    }
}
