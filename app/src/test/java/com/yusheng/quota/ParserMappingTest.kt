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
