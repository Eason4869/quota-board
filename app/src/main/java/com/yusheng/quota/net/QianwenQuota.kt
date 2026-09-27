package com.yusheng.quota.net

import com.yusheng.quota.data.Extra
import com.yusheng.quota.data.QueryResult
import com.yusheng.quota.data.Subscription
import org.json.JSONObject

/** Converts official QianWen billing instances into displayed Credit balances. */
internal object QianwenQuota {
    fun parse(json: JSONObject): QueryResult {
        val root = json.getJSONObject("qianwen")
        val names = mutableListOf<String>()
        val extras = mutableListOf<Extra>()
        var remaining = 0.0
        var total = 0.0
        var count = 0
        for ((key, title) in listOf("personal" to "Personal", "teams" to "Team", "addon" to "Add-on")) {
            val items = root.optJSONObject(key)?.optJSONArray("Data") ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val status = item.optJSONObject("Status")?.optString("Code") ?: item.optString("Status")
                if (status != "valid") continue
                val capacity = item.opt("InitCapacityBaseValue")?.toString()?.toDoubleOrNull() ?: continue
                val leftField = if (item.optString("CapacityTypeCode") == "periodMonthlyShift")
                    "periodCapacityBaseValue" else "CurrCapacityBaseValue"
                val left = item.opt(leftField)?.toString()?.toDoubleOrNull()
                    ?: item.opt("CurrCapacityBaseValue")?.toString()?.toDoubleOrNull() ?: continue
                if (!capacity.isFinite() || !left.isFinite() || capacity < 0 || left < 0) continue
                val name = item.optString("TemplateName").ifBlank {
                    item.optString("CommodityName").ifBlank { title }
                }
                names += name
                extras += Extra(name, "${left.toLong()} / ${capacity.toLong()} Credits")
                if (key != "addon") {
                    total += capacity
                    remaining += left
                    count++
                }
            }
        }
        // A successful empty list is a genuine account without a current plan.
        val checked = root.has("personal") || root.has("teams")
        check(checked) { "千问：未取得 Token Plan 数据" }
        val missingPlans = !root.has("personal") || !root.has("teams")
        if (count == 0) extras += Extra("Token Plan", if (missingPlans) "Not retrieved" else "No active plan")
        root.optJSONArray("unavailable")?.let { missing ->
            if (missing.length() > 0) extras += Extra("未取到", (0 until missing.length())
                .joinToString(" / ") { missing.optString(it) })
        }
        return QueryResult(
            subscription = if (count > 0) Subscription(
                tier = names.joinToString(" + "), remaining = remaining, total = total, unit = "Credits",
            ) else null,
            extras = extras,
        )
    }
}
