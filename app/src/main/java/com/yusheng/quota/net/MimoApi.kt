package com.yusheng.quota.net

import com.yusheng.quota.data.Balance
import com.yusheng.quota.data.Period
import com.yusheng.quota.data.QueryResult
import com.yusheng.quota.data.Subscription
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Official key APIs, separate from the console's Cookie-only endpoints. */
internal object MimoApi {
    const val BALANCE = "https://api.xiaomimimo.com/v1/platform/balance"
    const val TOKEN_PLAN = "https://token-plan-cn.xiaomimimo.com/v1/platform/token_plan"

    fun fetch(key: String, request: (String, Map<String, String>) -> JSONObject): JSONObject {
        val token = key.trim()
        require(token.isNotBlank()) { "MiMo: 请填写 API Key（Token Plan 使用 tp- 专属 Key）" }
        val endpoint = if (token.startsWith("tp-")) TOKEN_PLAN else BALANCE
        return request(endpoint, mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"))
            .also { parse(it) }
    }

    fun parse(body: JSONObject): QueryResult {
        check(!body.has("error")) { "MiMo: ${body.optJSONObject("error")?.optString("message") ?: "API error"}" }
        body.optJSONArray("balance_infos")?.let { infos ->
            val rows = (0 until infos.length()).mapNotNull { infos.optJSONObject(it) }
            val info = rows.firstOrNull { it.optString("currency") == "CNY" } ?: rows.firstOrNull()
            val total = number(info, "total_available")
            check(total != null) { "MiMo: 余额响应缺少有效的 total_available" }
            val amount = total - (number(info, "frozen_balance") ?: 0.0)
            check(amount.isFinite()) { "MiMo: 无效的可用余额" }
            val currency = when (val code = info?.optString("currency").orEmpty()) {
                "", "CNY" -> "¥"
                "USD" -> "$"
                else -> code
            }
            return QueryResult(balance = Balance(amount, currency))
        }
        val quota = body.optJSONObject("quota")
        val total = number(quota, "total_credits")
        check(total != null && total > 0.0) { "MiMo: 未取得有效的 Token Plan 积分" }
        val used = number(quota, "used_credits")
            ?: number(quota, "remaining_credits")?.let { total - it }
        check(used != null) { "MiMo: Token Plan 响应缺少已用或剩余积分" }
        val remaining = number(quota, "remaining_credits") ?: (total - used).coerceAtLeast(0.0)
        val end = body.optJSONObject("period")?.optString("end_at")?.takeIf { it.isNotBlank() }
        val reset = end?.let {
            runCatching {
                LocalDateTime.parse(it.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .toInstant(ZoneOffset.UTC).toString()
            }.getOrDefault(it)
        }
        return QueryResult(
            subscription = Subscription(body.optString("plan_code").ifBlank { "MiMo Token Plan" },
                remaining.coerceAtLeast(0.0), total, "Credits", reset),
            periods = listOf(Period("Credits", used, total,
                usedPct = (used / total * 100.0).coerceIn(0.0, 100.0), resetAt = reset)),
        )
    }

    private fun number(body: JSONObject?, key: String): Double? =
        body?.opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() }
}
