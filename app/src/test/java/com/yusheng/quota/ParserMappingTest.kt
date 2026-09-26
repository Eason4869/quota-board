package com.yusheng.quota

import com.yusheng.quota.data.ImportCodec
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
class ParserMappingTest {
    @Test fun mimoShowsPlanAndBalanceTogetherAndMarksPartialResults() {
        val json = JSONObject("""{"mimo":{"usage":{"code":0,"data":{"monthUsage":{"items":[{"name":"month_total_token","used":25,"limit":100}]}}},"balance":{"code":0,"data":{"balance":12.5,"cashBalance":10,"giftBalance":2.5}},"unavailable":["detail"]}}""")
        val result = Parsers.parse("xiaomi", json, "", "", RuntimeEnvironment.getApplication())
        assertEquals(12.5, result.balance!!.amount, 0.0)
        assertEquals(75.0, result.subscription!!.remaining!!, 0.0)
        assertEquals(25.0, result.periods.single().used!!, 0.0)
        assertTrue(result.extras.any { it.value.contains("Plan details") })
        json.getJSONObject("mimo").remove("usage")
        val balanceOnly = Parsers.parse("xiaomi", json, "", "", RuntimeEnvironment.getApplication())
        assertEquals(12.5, balanceOnly.balance!!.amount, 0.0)
        assertNull(balanceOnly.subscription)
    }
    @Test fun explicitMappingsOverrideBuiltInValuesAndPreserveMetadata() {
        val result = Parsers.parse("openrouter",
            JSONObject("""{"data":{"total_credits":100,"total_usage":20,"customBalance":7,"customRemaining":9}}"""),
            "customBalance", "data.customRemaining", RuntimeEnvironment.getApplication())
        assertEquals(7.0, result.balance!!.amount, 0.0)
        assertEquals("$", result.balance!!.currency)
        assertEquals(9.0, result.subscription!!.remaining!!, 0.0)
        assertEquals(100.0, result.subscription!!.total!!, 0.0)
        assertTrue(result.extras.isNotEmpty())
    }
    @Test fun invalidExplicitMappingDoesNotSilentlyShowDefaultBalance() {
        assertThrows(IllegalArgumentException::class.java) {
            Parsers.parse("novita", JSONObject("""{"availableBalance":50000}"""),
                "missing", "", RuntimeEnvironment.getApplication())
        }
    }
    @Test fun blankAccountIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportCodec.decode("""{"accounts":[{"id":" ","templateId":"deepseek","name":"one"}]}""")
        }
    }
    @Test fun wrongAccountsTypeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ImportCodec.decode("""{"accounts":{}}""")
        }
    }
}
