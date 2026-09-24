package com.yusheng.quota.net

import android.content.Context
import com.yusheng.quota.R
import com.yusheng.quota.data.Balance
import com.yusheng.quota.data.Extra
import com.yusheng.quota.data.Period
import com.yusheng.quota.data.QueryResult
import com.yusheng.quota.data.Subscription
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 各厂商响应 → 归一化结果。
 *
 * 字段与判定逻辑按 **cc-switch** 的 Rust 实现对齐
 * （`services/balance.rs`、`services/coding_plan.rs`）；
 * 不同厂商字段差异大，这里按厂商单独解析，不套统一模板。
 *
 * 窗口标签统一成 4 个规范名：`5h` / `weekly` / `monthly` / `daily`，UI 层再翻译。
 */
object Parsers {

    fun parse(
        templateId: String,
        json: JSONObject,
        mapBalance: String,
        mapPlan: String,
        context: Context,
    ): QueryResult =
        when (templateId) {
            "deepseek" -> deepSeek(json)
            "openrouter" -> openRouter(json)
            "siliconflow" -> siliconFlow(json)
            "stepfun" -> stepFun(json)
            "novita" -> novita(json)
            "kimi" -> kimi(json)
            "zhipu" -> zhipu(json, context)
            "minimax" -> miniMax(json)
            "volc_agent", "volc_coding" -> volc(json)
            "opencode_go" -> openCodeGo(json)
            "claude" -> claude(json)
            "gemini" -> gemini(json)
            "openai" -> openAi(json)
            else -> generic(json, mapBalance, mapPlan)
        }

    // ── 按量余额类 ────────────────────────────────────────

    /** GET /user/balance → balance_infos[] + is_available */
    private fun deepSeek(j: JSONObject): QueryResult {
        val infos = j.optJSONArray("balance_infos") ?: JSONArray()
        val rows = (0 until infos.length()).mapNotNull { infos.optJSONObject(it) }
        val cny = rows.firstOrNull { it.optString("currency") == "CNY" } ?: rows.firstOrNull()
        val available = j.optBoolean("is_available", true)
        return QueryResult(
            balance = cny?.let {
                Balance(
                    amount = num(it, "total_balance") ?: 0.0,
                    currency = if (it.optString("currency") == "USD") "$" else "¥",
                )
            },
            extras = buildList {
                add(Extra("is_available", available.toString()))
                cny?.let {
                    add(Extra("granted", raw(it, "granted_balance")))
                    add(Extra("toppedUp", raw(it, "topped_up_balance")))
                }
            },
        )
    }

    /** GET /api/v1/credits → data{total_credits,total_usage} */
    private fun openRouter(j: JSONObject): QueryResult {
        val d = j.optJSONObject("data") ?: j
        val total = num(d, "total_credits") ?: 0.0
        val used = num(d, "total_usage") ?: 0.0
        val remain = (total - used).coerceAtLeast(0.0)
        return QueryResult(
            balance = Balance(remain, "$"),
            subscription = Subscription("Credits", remain, total, "USD"),
            extras = listOf(Extra("used", fmt(used))),
        )
    }

    /**
     * 余额：官方 API `data{balance, chargeBalance, totalBalance, status}`；
     * 控制台（登录态）返回结构略有差异，这里把常见字段都兼容一遍。
     */
    private fun siliconFlow(j: JSONObject): QueryResult {
        val d = j.optJSONObject("data") ?: j
        val amount = num(d, "totalBalance")
            ?: num(d, "balance")
            ?: num(d, "total_balance")
            ?: num(d, "amount")
            ?: num(d, "remaining")
            ?: num(d, "chargeBalance")
            ?: 0.0
        return QueryResult(
            balance = Balance(amount, "¥"),
            extras = buildList {
                num(d, "chargeBalance")?.let { add(Extra("chargeBalance", fmt(it))) }
                num(d, "totalBalance")?.let { add(Extra("totalBalance", fmt(it))) }
                str(d, "status")?.let { add(Extra("status", it)) }
            },
        )
    }

    /** GET https://api.stepfun.com/v1/accounts → {balance, total_cash_balance, total_voucher_balance} */
    private fun stepFun(j: JSONObject): QueryResult {
        val balance = num(j, "balance") ?: 0.0
        return QueryResult(
            balance = Balance(balance, "¥"),
            extras = buildList {
                num(j, "total_cash_balance")?.let { add(Extra("cash", fmt(it))) }
                num(j, "total_voucher_balance")?.let { add(Extra("voucher", fmt(it))) }
            },
        )
    }

    /** GET https://api.novita.ai/v3/user/balance → availableBalance（单位 0.0001 USD） */
    private fun novita(j: JSONObject): QueryResult {
        val raw = num(j, "availableBalance") ?: 0.0
        val usd = raw / 10000.0
        return QueryResult(
            balance = Balance(usd, "$"),
            extras = listOf(Extra("availableBalance", fmt(raw))),
        )
    }

    // ── 订阅额度类 ────────────────────────────────────────

    /**
     * GET https://api.kimi.com/coding/v1/usages
     * `limits[]` 每条 detail 是一个 5 小时窗口；`usage` 是周额度。
     */
    private fun kimi(j: JSONObject): QueryResult {
        val periods = mutableListOf<Period>()
        j.optJSONArray("limits")?.let { arr ->
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i)?.optJSONObject("detail") ?: continue
                val limit = num(d, "limit") ?: 1.0
                val remain = num(d, "remaining") ?: 0.0
                periods += Period(
                    label = "5h",
                    used = (limit - remain).coerceAtLeast(0.0),
                    total = limit,
                    resetAt = resetTime(d.opt("resetTime")),
                )
            }
        }
        j.optJSONObject("usage")?.let { u ->
            val limit = num(u, "limit") ?: 1.0
            val remain = num(u, "remaining") ?: 0.0
            periods += Period(
                label = "weekly",
                used = (limit - remain).coerceAtLeast(0.0),
                total = limit,
                resetAt = resetTime(u.opt("resetTime")),
            )
        }
        val first = periods.firstOrNull()
        return QueryResult(
            subscription = first?.let { Subscription("Kimi Coding", it.remain, it.total, "quota", it.resetAt) },
            periods = periods,
        )
    }

    /**
     * GET /api/monitor/usage/quota/limit → data{level, limits[]}
     *
     * 窗口判定（对齐 cc-switch `parse_zhipu_token_tiers`）：
     *  1. 优先看 `unit`：3 = 5 小时窗口，6 = 每周窗口（`number` 有 5/7/1 多种取值，不可靠）
     *  2. `unit` 缺失或值不认识时兜底：没有 reset 的条目优先归 5 小时（0% 时可能没有 reset），
     *     其余按 reset 升序依次填空槽；智谱最多两条 TOKENS_LIMIT
     *  3. 不能按 nextResetTime 排序代替判定 —— 周期末尾周窗口会比 5 小时窗口更早重置
     */
    private fun zhipu(j: JSONObject, context: Context): QueryResult {
        if (j.has("success") && !j.optBoolean("success", true)) {
            throw IllegalStateException(
                context.getString(
                    R.string.err_zhipu_api,
                    str(j, "msg", "message") ?: context.getString(R.string.err_unknown),
                )
            )
        }
        val data = j.optJSONObject("data") ?: j
        val limits = data.optJSONArray("limits")

        var fiveHour: Entry? = null
        var weekly: Entry? = null
        val unclassified = mutableListOf<Entry>()

        if (limits != null) {
            for (i in 0 until limits.length()) {
                val item = limits.optJSONObject(i) ?: continue
                val type = item.optString("type")
                if (!type.equals("TOKENS_LIMIT", true) && !type.equals("CREDIT_LIMIT", true)) continue
                val percentage = num(item, "percentage") ?: 0.0
                val resetMs = longOrNull(item.opt("nextResetTime"))
                val entry = Entry(resetMs, percentage, resetMs?.let { epochToIso(it) })
                when (longOrNull(item.opt("unit"))) {
                    3L -> if (fiveHour == null) fiveHour = entry else unclassified += entry
                    6L -> if (weekly == null) weekly = entry else unclassified += entry
                    else -> unclassified += entry
                }
            }
        }

        unclassified
            .sortedWith(compareByDescending<Entry> { it.resetMs != null }.thenBy { it.resetMs ?: 0L })
            .forEach { entry ->
                if (fiveHour == null) fiveHour = entry else if (weekly == null) weekly = entry
            }

        val periods = buildList {
            fiveHour?.let { add(Period("5h", null, 100.0, usedPct = it.percentage, resetAt = it.resetAt)) }
            weekly?.let { add(Period("weekly", null, 100.0, usedPct = it.percentage, resetAt = it.resetAt)) }
        }
        val first = periods.firstOrNull()
        return QueryResult(
            subscription = first?.let {
                Subscription(
                    tier = str(data, "level") ?: "GLM",
                    remaining = it.remain,
                    total = 100.0,
                    unit = "%",
                    resetAt = it.resetAt,
                )
            },
            periods = periods,
        )
    }

    /**
     * GET {api.minimaxi.com|api.minimax.io}/v1/api/openplatform/coding_plan/remains
     *
     * 字段是「剩余百分比」：`model_remains[]` 里取 `model_name == "general"`（跳过 video 等），
     * 5 小时窗口取 `current_interval_remaining_percent` / `end_time`；
     * 周桶只在 `current_weekly_status == 1` 时有效（3 表示该套餐没有周限额）。
     */
    private fun miniMax(j: JSONObject): QueryResult {
        val root = j.optJSONObject("data") ?: j
        val remains = root.optJSONArray("model_remains") ?: return QueryResult()
        val item = (0 until remains.length())
            .mapNotNull { remains.optJSONObject(it) }
            .firstOrNull { it.optString("model_name") == "general" }
            ?: return QueryResult()

        val periods = mutableListOf<Period>()
        num(item, "current_interval_remaining_percent")?.let { remainPct ->
            periods += Period(
                label = "5h",
                used = (100.0 - remainPct).coerceAtLeast(0.0),
                total = 100.0,
                usedPct = 100.0 - remainPct,
                resetAt = resetTime(item.opt("end_time")),
            )
        }
        if (longOrNull(item.opt("current_weekly_status")) == 1L) {
            num(item, "current_weekly_remaining_percent")?.let { remainPct ->
                periods += Period(
                    label = "weekly",
                    used = (100.0 - remainPct).coerceAtLeast(0.0),
                    total = 100.0,
                    usedPct = 100.0 - remainPct,
                    resetAt = resetTime(item.opt("weekly_end_time")),
                )
            }
        }
        val first = periods.firstOrNull()
        return QueryResult(
            subscription = first?.let {
                Subscription("MiniMax Coding", it.remain, 100.0, "%", it.resetAt)
            },
            periods = periods,
        )
    }

    /**
     * GET https://opencode.ai/zen/go/v1/usage
     * `usage.{rolling,weekly,monthly}.{percent,resetsAt}`，percent 是**已用**百分数。
     * percent == 0 时上游给的 resetsAt 是「now + 窗口时长」的占位值，丢弃。
     */
    private fun openCodeGo(j: JSONObject): QueryResult {
        val usage = j.optJSONObject("usage") ?: return QueryResult()
        val windows = listOf("rolling" to "5h", "weekly" to "weekly", "monthly" to "monthly")
        val periods = mutableListOf<Period>()
        windows.forEach { (key, label) ->
            val w = usage.optJSONObject(key) ?: return@forEach
            val percent = num(w, "percent") ?: return@forEach
            periods += Period(
                label = label,
                used = percent,
                total = 100.0,
                usedPct = percent,
                resetAt = if (percent > 0.0) resetTime(w.opt("resetsAt")) else null,
            )
        }
        val first = periods.firstOrNull()
        return QueryResult(
            subscription = first?.let { Subscription("OpenCode Go", it.remain, 100.0, "%", it.resetAt) },
            periods = periods,
        )
    }

    /**
     * 火山方舟
     *  - Agent Plan（GetAFPUsage）：`AFPFiveHour/AFPWeekly/AFPMonthly` 的 Quota/Used 是绝对 AFP 值
     *  - Coding Plan（GetCodingPlanUsage）：`QuotaUsage[]` 只给百分比
     *  `Quota <= 0` 视为该窗口未订阅 / 未启用，跳过。
     */
    private fun volc(j: JSONObject): QueryResult {
        val raw = j.optJSONObject("Result") ?: j.optJSONObject("result") ?: j
        val periods = mutableListOf<Period>()
        var planName: String? = null

        for ((key, label) in listOf(
            "AFPFiveHour" to "5h",
            "AFPWeekly" to "weekly",
            "AFPMonthly" to "monthly",
        )) {
            val w = raw.optJSONObject(key) ?: continue
            val quota = num(w, "Quota") ?: 0.0
            if (quota <= 0.0) continue
            val used = num(w, "Used") ?: 0.0
            periods += Period(label, used, quota, resetAt = resetTime(w.opt("ResetTime")))
        }
        if (periods.isNotEmpty()) {
            planName = str(raw, "PlanType")?.let { "Agent Plan $it" } ?: "Agent Plan"
        }

        if (periods.isEmpty()) {
            val arr = raw.optJSONArray("QuotaUsage")
                ?: raw.optJSONArray("Usages")
                ?: raw.optJSONArray("Details")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val label = str(item, "Level", "Type", "Period", "Label", "Window") ?: continue
                    val canonical = volcWindowLabel(label) ?: continue
                    val percent = num(item, "Percent", "UsedPercent", "UsagePercent") ?: 0.0
                    periods += Period(
                        label = canonical,
                        used = percent,
                        total = 100.0,
                        usedPct = percent,
                        resetAt = resetTime(item.opt("ResetTime") ?: item.opt("ResetTimestamp")),
                    )
                }
                if (periods.isNotEmpty()) planName = "Coding Plan"
            }
        }

        val first = periods.firstOrNull()
        return QueryResult(
            subscription = first?.let {
                Subscription(planName ?: "Plan", it.remain, it.total, "AFP", it.resetAt)
            },
            periods = periods,
        )
    }

    /** GetCodingPlanUsage 的窗口标签 → 规范名；不认识的窗口直接跳过 */
    private fun volcWindowLabel(label: String): String? = when (label.lowercase()) {
        "session", "5h", "fivehour", "five_hour", "rolling_5h" -> "5h"
        "weekly", "week", "7d" -> "weekly"
        "monthly", "month" -> "monthly"
        else -> null
    }

    /** Claude 订阅限速窗口：同一份数据在不同接口里字段名不同，逐个别名尝试 */
    private fun claude(j: JSONObject): QueryResult {
        val periods = mutableListOf<Period>()
        val fiveHour = num(j, "five_hour", "five_hour_used_pct")
            ?: num(j.optJSONObject("5h"), "used_pct", "used")
        fiveHour?.let {
            periods += Period(
                "5h", null, 100.0, usedPct = it,
                resetAt = str(j, "five_hour_reset_at", "resets_at"),
            )
        }
        val weekly = num(j, "weekly", "weekly_used_pct")
            ?: num(j.optJSONObject("seven_day"), "used_pct", "used")
        weekly?.let {
            periods += Period(
                "weekly", null, 100.0, usedPct = it,
                resetAt = str(j, "weekly_reset_at") ?: str(j.optJSONObject("seven_day"), "resets_at"),
            )
        }
        val first = periods.firstOrNull()
        return QueryResult(
            subscription = first?.let { Subscription("Claude", it.remain, 100.0, "%", it.resetAt) },
            periods = periods,
        )
    }

    private fun gemini(j: JSONObject): QueryResult {
        val periods = mutableListOf<Period>()
        val fiveHour = j.optJSONObject("5h")
        val used5 = num(j, "five_hour_used") ?: num(fiveHour, "used")
        val total5 = num(j, "five_hour_limit") ?: num(fiveHour, "limit")
        if (used5 != null && total5 != null) {
            periods += Period("5h", used5, total5, resetAt = str(j, "reset_at"))
        }
        val daily = j.optJSONObject("daily")
        val usedD = num(j, "daily_used") ?: num(daily, "used")
        val totalD = num(j, "daily_limit") ?: num(daily, "limit")
        if (usedD != null && totalD != null) {
            periods += Period("daily", usedD, totalD, resetAt = str(j, "daily_reset_at"))
        }
        val first = periods.firstOrNull()
        return QueryResult(
            subscription = first?.let { Subscription("Gemini", it.remain, it.total, "次", it.resetAt) },
            periods = periods,
        )
    }

    private fun openAi(j: JSONObject): QueryResult {
        val bal = num(j, "balance", "total_balance", "credits")
        val plan = str(j, "plan", "planName")
        return QueryResult(
            balance = bal?.let { Balance(it, "$") },
            subscription = if (plan != null || bal != null) {
                Subscription(plan ?: "OpenAI", bal, null, "USD")
            } else null,
        )
    }

    /** 通用：自动识别常见字段，支持 mapBalance / mapPlan 路径覆盖 */
    private fun generic(j: JSONObject, mapBalance: String, mapPlan: String): QueryResult {
        val payload = j.optJSONObject("data")?.optJSONObject("result")
            ?: j.optJSONObject("data")
            ?: j.optJSONObject("result")
            ?: j

        val bal = path(payload, mapBalance.ifBlank { "payg.balance" })
            ?: path(payload, "balance")
            ?: path(payload, "data.balance")
        val remain = path(payload, mapPlan.ifBlank { "plan.remaining" })
            ?: path(payload, "remaining")
            ?: path(payload, "plan.creditsRemaining")
        val total = path(payload, "plan.total") ?: path(payload, "total")
        val tier = pathStr(payload, "plan.tier") ?: pathStr(payload, "tier") ?: pathStr(payload, "planName")

        val periods = mutableListOf<Period>()
        payload.optJSONArray("periods")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                periods += Period(
                    label = p.optString("label").ifBlank { p.optString("id").ifBlank { "window${i + 1}" } },
                    used = num(p, "used"),
                    total = num(p, "total"),
                    usedPct = num(p, "usedPct", "used_pct", "percentage"),
                    resetAt = str(p, "resetAt", "reset_at"),
                )
            }
        }

        return QueryResult(
            balance = bal?.let { Balance(it, pathStr(payload, "payg.currency") ?: "¥") },
            subscription = if (tier != null || remain != null) {
                Subscription(tier ?: "-", remain, total, "Credits", pathStr(payload, "plan.resetAt"))
            } else null,
            periods = periods,
        )
    }

    // ── 工具 ──────────────────────────────────────────────

    private data class Entry(val resetMs: Long?, val percentage: Double, val resetAt: String?)

    private fun num(o: JSONObject?, vararg keys: String): Double? {
        if (o == null) return null
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            when (val v = o.get(k)) {
                is Number -> return v.toDouble()
                is String -> v.trim().toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun str(o: JSONObject?, vararg keys: String): String? {
        if (o == null) return null
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            val v = o.optString(k)
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    private fun raw(o: JSONObject, key: String): String =
        if (o.has(key) && !o.isNull(key)) o.optString(key) else "—"

    private fun longOrNull(v: Any?): Long? = when (v) {
        null, JSONObject.NULL -> null
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong()
        else -> null
    }

    /**
     * 重置时间归一化：字符串原样返回；数字自动区分秒 / 毫秒；
     * `<= 0` 视为无重置时间（火山 session 无活动窗口会回 -1）。
     */
    private fun resetTime(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        is String -> v.takeIf { it.isNotBlank() }
        is Number -> epochToIso(v.toLong())
        else -> null
    }

    private fun epochToIso(ms: Long): String? {
        if (ms <= 0L) return null
        val real = if (ms < 1_000_000_000_000L) ms * 1000 else ms
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(real))
    }

    private fun fmt(v: Double): String = "%.2f".format(v)

    private fun path(o: JSONObject, p: String): Double? {
        val v = pathRaw(o, p) ?: return null
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    private fun pathStr(o: JSONObject, p: String): String? {
        val v = pathRaw(o, p) ?: return null
        return v.toString().takeIf { it.isNotBlank() && it != "null" }
    }

    private fun pathRaw(o: JSONObject, p: String): Any? {
        if (p.isBlank()) return null
        var cur: Any? = o
        for (seg in p.removePrefix("$.").split(".")) {
            cur = when (cur) {
                is JSONObject -> if (cur.has(seg)) cur.get(seg) else return null
                else -> return null
            }
        }
        return cur
    }
}
