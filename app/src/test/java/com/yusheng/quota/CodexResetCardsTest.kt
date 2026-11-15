package com.yusheng.quota

import com.yusheng.quota.net.Parsers
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = QuotaApp::class)
class CodexResetCardsTest {
    private fun count(raw: String) = Parsers.parse("openai", JSONObject(raw), "", "",
        RuntimeEnvironment.getApplication()).extras.firstOrNull { it.label == "reset_cards" }?.value

    @Test fun readsAvailableCountAndKeepsExplicitZero() {
        assertEquals("3", count("""{"rate_limit_reset_credits":{"available_count":3}}"""))
        assertEquals("0", count("""{"rate_limit_reset_credits":{"available_count":0}}"""))
    }

    @Test fun missingOrInvalidCountsStayUnknown() {
        for (value in listOf("null", "-1", "1.5", "true", "\"3\"", "1e100")) {
            assertNull(value, count("""{"rate_limit_reset_credits":{"available_count":$value}}"""))
        }
        assertNull(count("{}"))
        assertNull(count("""{"rate_limit_reset_credits":null}"""))
        assertNull(count("""{"rate_limit_reset_credits":{"total_earned_count":5}}"""))
    }
}
