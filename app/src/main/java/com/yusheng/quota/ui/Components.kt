package com.yusheng.quota.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yusheng.quota.R
import com.yusheng.quota.data.Period
import com.yusheng.quota.ui.theme.PeriodColors
import kotlin.math.roundToInt

/** 厂商色块（Kotlin 端用品牌色 + 简称；官网图标见 assets/logos） */
@Composable
fun VendorBadge(short: String, color: Long, size: Int = 42) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(color).copy(alpha = 0.14f))
            .border(1.dp, Color(color).copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = short,
            color = Color(color),
            fontSize = (size / 3).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) { content() }
}

/** 单个周期额度条 */
@Composable
fun PeriodRow(period: Period, index: Int) {
    val color = PeriodColors[index % PeriodColors.size]
    val usedPct = period.usedPercent ?: 0.0
    val remainPct = period.remainPercent

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = periodLabel(period.label),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = buildString {
                    if (period.used != null && period.total != null) {
                        append("${fmtNum(period.used)} / ${fmtNum(period.total)}")
                    } else if (usedPct > 0.0) {
                        append("${stringResource(R.string.label_used)} ${usedPct.roundToInt()}%")
                    }
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((usedPct / 100.0).toFloat().coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(color),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = period.resetAt.orEmpty(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (remainPct != null) {
                Text(
                    text = stringResource(R.string.label_remain_pct, remainPct.roundToInt()),
                    fontSize = 11.sp,
                    color = remainColor(remainPct),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 剩余比例配色：≤10% 红，≤25% 黄，其余绿 */
fun remainColor(remainPct: Double): Color = when {
    remainPct <= 10.0 -> Color(0xFFFF5A6E)
    remainPct <= 25.0 -> Color(0xFFF5A524)
    else -> Color(0xFF2FD48B)
}

/** 各厂商窗口标签 → 当前语言的展示名 */
@Composable
fun periodLabel(raw: String): String = when (raw.lowercase()) {
    "5h", "session", "five_hour", "fivehour", "rolling", "rolling_5h" -> stringResource(R.string.window_5h)
    "weekly", "week", "7d" -> stringResource(R.string.window_week)
    "monthly", "month" -> stringResource(R.string.window_month)
    "daily", "day", "24h" -> stringResource(R.string.window_day)
    else -> raw
}

/** 非 Composable 场景（小组件）用 */
fun periodLabelPlain(day: String, week: String, month: String, fiveHour: String, raw: String): String =
    when (raw.lowercase()) {
        "5h", "session", "five_hour", "fivehour", "rolling", "rolling_5h" -> fiveHour
        "weekly", "week", "7d" -> week
        "monthly", "month" -> month
        "daily", "day", "24h" -> day
        else -> raw
    }

@Composable
fun relTime(ts: Long): String {
    if (ts <= 0L) return stringResource(R.string.dash_never)
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> stringResource(R.string.rel_just_now)
        diff < 3_600_000 -> stringResource(R.string.rel_minutes, (diff / 60_000).toInt())
        diff < 86_400_000 -> stringResource(R.string.rel_hours, (diff / 3_600_000).toInt())
        else -> stringResource(R.string.rel_days, (diff / 86_400_000).toInt())
    }
}

/**
 * 数字展示：≥ 10000 时使用**当前语言**的紧凑单位
 * （中文「万 / 亿」，英文「K / M / B」…），其余保持原样。
 */
fun fmtNum(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "—"
    if (kotlin.math.abs(v) >= 10_000) {
        val compact = runCatching {
            android.icu.text.CompactDecimalFormat.getInstance(
                java.util.Locale.getDefault(),
                android.icu.text.CompactDecimalFormat.CompactStyle.SHORT,
            ).apply { maximumFractionDigits = 1 }.format(v)
        }.getOrNull()
        if (!compact.isNullOrBlank()) return compact
    }
    return if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v)
}

fun fmtMoney(amount: Double, currency: String): String = "$currency ${fmtNum(amount)}"
