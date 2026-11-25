package com.yusheng.quota

import com.yusheng.quota.data.QueryMode
import com.yusheng.quota.data.Templates
import com.yusheng.quota.net.Parsers
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderQuotaRegressionTest {
    private fun parse(vendor: String, body: String) = Parsers.parse(
        vendor, JSONObject(body), "", "", RuntimeEnvironment.getApplication(),
    )

    @Test fun mimoDefaultsToKeyAndKeepsSessionAsAnOption() {
        val template = Templates.byId("xiaomi")
        assertEquals(QueryMode.API, template.defaultMode)
        assertEquals(QueryMode.API, template.defaults.mode)
        assertTrue(template.modes.contains(QueryMode.LOGIN))
    }

    @Test fun mimoBalanceSubtractsFrozenFundsAndKeepsNegativeBalance() {
        val result = parse("xiaomi", """{"object":"balance","is_available":false,"balance_infos":[{"currency":"CNY","total_available":"10.50","frozen_balance":"12.00"}]}""")
        assertEquals(-1.5, result.balance!!.amount, 0.0)
        assertEquals("¥", result.balance!!.currency)
        assertNull(result.subscription)
    }

    @Test fun mimoCreditsIncludeCompensationAndUseUtcWithoutInventingMonthlyWindow() {
        val result = parse("xiaomi", """{"object":"token_plan","quota":{"total_credits":"1100","used_credits":"275","remaining_credits":"825"},"period":{"end_at":"2027-11-25 08:30:00"}}""")
        assertEquals(1100.0, result.subscription!!.total!!, 0.0)
        assertEquals(825.0, result.subscription!!.remaining!!, 0.0)
        assertEquals("Credits", result.subscription!!.unit)
        assertEquals("2027-11-25T08:30:00Z", result.subscription!!.resetAt)
        assertEquals(25.0, result.periods.single().usedPercent!!, 0.0)
        assertNotEquals("monthly", result.periods.single().label)
    }

    @Test fun mimoCanDeriveUsedCreditsFromRemaining() {
        val result = parse("xiaomi", """{"quota":{"total_credits":100,"remaining_credits":0}}""")
        assertEquals(100.0, result.periods.single().usedPercent!!, 0.0)
    }

    @Test fun mimoRejectsErrorsAndMissingCreditsInsteadOfShowingEmptySuccess() {
        for (body in listOf("{}", """{"error":{"message":"Invalid key"}}""",
            """{"quota":{"total_credits":100}}""", """{"balance_infos":[{"currency":"CNY"}]}""")) {
            assertThrows(IllegalStateException::class.java) { parse("xiaomi", body) }
        }
    }

    @Test fun minimaxExhaustedWeeklyWindowIsVisible() {
        val result = parse("minimax", """{"base_resp":{"status_code":0},"model_remains":[{"model_name":"general","current_interval_remaining_percent":80,"current_weekly_status":2,"current_weekly_remaining_percent":0}]}""")
        assertEquals(100.0, result.periods.single { it.label == "weekly" }.usedPercent!!, 0.0)
    }

    @Test fun minimaxBusinessErrorIsNotAnEmptyQuota() {
        assertThrows(IllegalStateException::class.java) {
            parse("minimax", """{"base_resp":{"status_code":1004,"status_msg":"invalid api key"}}""")
        }
    }

    @Test fun zhipuPayAsYouGoUsesAvailableBalance() {
        val result = parse("zhipu", """{"success":true,"data":{"balance":100,"availableBalance":85,"frozenBalance":15}}""")
        assertEquals(85.0, result.balance!!.amount, 0.0)
        assertNull(result.subscription)
    }

    @Test fun zhipuWindowWithoutResetIsFiveHoursWhenUnitsAreMissing() {
        val result = parse("zhipu", """{"success":true,"data":{"limits":[{"type":"TOKENS_LIMIT","percentage":70,"nextResetTime":1800000000000},{"type":"TOKENS_LIMIT","percentage":0}]}}""")
        assertEquals(0.0, result.periods.single { it.label == "5h" }.usedPercent!!, 0.0)
        assertEquals(70.0, result.periods.single { it.label == "weekly" }.usedPercent!!, 0.0)
    }

    @Test fun invalidBalanceResponsesAreNotZeroMoney() {
        for (vendor in listOf("deepseek", "openrouter", "siliconflow", "stepfun", "novita", "moonshot")) {
            assertThrows("$vendor must reject an error body", IllegalStateException::class.java) {
                parse(vendor, """{"error":{"message":"invalid api key"}}""")
            }
        }
    }

    @Test fun openRouterKeepsOverdrawnBalance() {
        val result = parse("openrouter", """{"data":{"total_credits":10,"total_usage":14}}""")
        assertEquals(-4.0, result.balance!!.amount, 0.0)
    }

    @Test fun explicitMappingStillSupportsCustomBalanceShapes() {
        for (vendor in listOf("deepseek", "openrouter", "siliconflow", "stepfun", "novita", "moonshot")) {
            val result = Parsers.parse(vendor, JSONObject("""{"data":{"money":12.5}}"""),
                "data.money", "", RuntimeEnvironment.getApplication())
            assertEquals(vendor, 12.5, result.balance!!.amount, 0.0)
        }
    }

    @Test fun geminiSummaryKeepsDifferentModelPoolsAndWindows() {
        val result = parse("gemini", """{"groups":[{"displayName":"Gemini Models","buckets":[{"bucketId":"gemini-5h","window":"5h","remainingFraction":0.5,"resetTime":"2026-11-25T12:00:00Z"},{"bucketId":"gemini-weekly","window":"weekly","remainingFraction":1}]},{"displayName":"Claude and GPT models","buckets":[{"bucketId":"3p-5h","window":"5h","remainingFraction":0}]}]}""")
        assertEquals(3, result.periods.size)
        assertEquals(listOf(50.0, 0.0, 100.0), result.periods.map { it.usedPercent })
        assertEquals(3, result.periods.map { it.label }.distinct().size)
        assertEquals("2026-11-25T12:00:00Z", result.periods.first().resetAt)
    }
}
