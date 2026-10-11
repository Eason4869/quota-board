package com.yusheng.quota.widget

import android.content.Context
import com.yusheng.quota.R
import com.yusheng.quota.data.Account
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

data class WidgetPeriodContent(val label: String, val value: String, val remaining: Int, val reset: String?)

data class WidgetContent(
    val title: String,
    val value: String,
    val detail: String,
    val status: String,
    val compactStatus: String = status,
    val periods: List<WidgetPeriodContent> = emptyList(),
    val resetCards: String? = null,
) {
    companion object {
        fun from(context: Context, account: Account?): WidgetContent {
            if (account == null) return WidgetContent(
                context.getString(R.string.app_name), "—",
                context.getString(R.string.widget_choose_source), context.getString(R.string.widget_tap_edit),
            )
            val result = account.result
            val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
            fun format(value: Double) = if (value.isFinite()) number.format(value) else "—"
            fun window(label: String): String {
                val resource = when (label.lowercase()) {
                    "5h", "session", "five_hour", "fivehour", "rolling", "rolling_5h" -> R.string.widget_window_5h
                    "weekly", "week", "7d" -> R.string.widget_window_week
                    "monthly", "month" -> R.string.widget_window_month
                    "daily", "day", "24h" -> R.string.widget_window_day
                    else -> return label
                }
                return context.getString(resource)
            }
            val balance = result?.balance
            val period = result?.periods?.firstOrNull { it.remainPercent != null }
            val subscription = result?.subscription
            val value = when {
                balance != null -> "${balance.currency}${format(balance.amount)}"
                period != null -> "${format(period.remainPercent!!)}%"
                subscription?.remaining != null -> "${format(subscription.remaining)} ${subscription.unit}"
                else -> "—"
            }
            val windows = result?.periods.orEmpty().take(3).mapNotNull { p ->
                p.remainPercent?.let { "${window(p.label)} ${format(it)}%" }
            }.joinToString(" · ")
            val detail = when {
                windows.isNotBlank() -> context.getString(R.string.widget_remaining_windows, windows)
                balance != null -> context.getString(R.string.label_balance)
                subscription != null -> subscription.tier ?: context.getString(R.string.widget_remaining)
                else -> context.getString(R.string.widget_no_data)
            }
            val timestamp = result?.fetchedAt?.takeIf { it > 0 }?.let {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
            }
            val status = when {
                account.lastError != null && timestamp != null -> context.getString(R.string.widget_stale, timestamp)
                account.lastError != null -> context.getString(R.string.widget_query_failed)
                timestamp != null -> context.getString(R.string.widget_updated, timestamp)
                else -> context.getString(R.string.widget_no_data)
            }
            val compactTime = result?.fetchedAt?.takeIf { it > 0 }?.let { fetchedAt ->
                val fetched = Calendar.getInstance().apply { timeInMillis = fetchedAt }
                val now = Calendar.getInstance()
                val today = fetched.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    fetched.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
                SimpleDateFormat(if (today) "HH:mm" else "MM/dd", context.resources.configuration.locales[0]).format(Date(fetchedAt))
            }
            val compactStatus = when {
                compactTime != null -> context.getString(if (account.lastError != null) R.string.widget_stale_short else R.string.widget_updated_short, compactTime)
                account.lastError != null -> context.getString(R.string.widget_failed_short)
                else -> context.getString(R.string.widget_no_data)
            }
            val periods = result?.periods.orEmpty().mapNotNull { p ->
                val remaining = p.remainPercent?.takeIf { it.isFinite() } ?: return@mapNotNull null
                WidgetPeriodContent(window(p.label), "${format(remaining)}%", remaining.toInt(), resetLabel(p.resetAt, context))
            }
            val resetCards = result?.extras.orEmpty().firstNotNullOfOrNull { extra ->
                val key = extra.label.lowercase().replace(Regex("[\\s_-]"), "")
                if (key !in setOf("resetcards", "resetcardcount", "resetcardscount", "重置卡", "重置卡数量")) null
                else extra.value.toLongOrNull()?.takeIf { it >= 0 }?.let { context.getString(R.string.widget_reset_cards, it) }
            }
            return WidgetContent(account.name, value, detail, status, compactStatus, periods, resetCards)
        }

        /** Display an absolute time: cached windows must never appear to have reset themselves. */
        private fun resetLabel(raw: String?, context: Context): String? {
            if (raw.isNullOrBlank()) return null
            val zone = ZoneId.systemDefault()
            val time = runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(zone).toLocalDateTime() }.getOrNull()
                ?: runCatching { Instant.parse(raw).atZone(zone).toLocalDateTime() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(raw) }.getOrNull()
            if (time != null) return time.format(DateTimeFormatter.ofPattern("MM/dd HH:mm", context.resources.configuration.locales[0]))
            val date = runCatching { LocalDate.parse(raw) }.getOrNull()
            return date?.format(DateTimeFormatter.ofPattern("MM/dd")) ?: raw
        }
    }
}
