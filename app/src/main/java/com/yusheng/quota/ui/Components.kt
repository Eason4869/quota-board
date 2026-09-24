package com.yusheng.quota.ui

import androidx.compose.foundation.Image
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yusheng.quota.R
import com.yusheng.quota.data.Period
import com.yusheng.quota.ui.theme.Glass
import com.yusheng.quota.ui.theme.PeriodColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt





/** templateId → 官网 Logo 资源（vector / png） */
fun logoResFor(templateId: String): Int = when (templateId) {
    "xiaomi" -> R.drawable.logo_xiaomi
    "volc_agent", "volc_coding" -> R.drawable.logo_volc
    "kimi" -> R.drawable.logo_kimi
    "zhipu" -> R.drawable.logo_zhipu
    "minimax" -> R.drawable.logo_minimax
    "opencode_go" -> R.drawable.logo_opencode
    "deepseek" -> R.drawable.logo_deepseek
    "siliconflow" -> R.drawable.logo_siliconflow
    "stepfun" -> R.drawable.logo_stepfun
    "novita" -> R.drawable.logo_novita
    "claude" -> R.drawable.logo_claude
    "gemini" -> R.drawable.logo_gemini
    "openai" -> R.drawable.logo_openai
    "openrouter" -> R.drawable.logo_openrouter
    else -> R.drawable.logo_generic
}

/** Dock 占屏宽的比例：1f = 满屏。想再调长短**只改这一个数**即可 */
private const val DockWidthFraction = 0.8f

/** Dock 的模糊半径：越大磨砂越重（24dp 大致相当于 iOS 的 regular 档） */
private val DockBlurRadius = 24.dp

/**
 * 液态玻璃 Dock：只有胶囊本体有材质与描边，**四周完全透明**，
 * 页面内容可以从下方穿过（配合调用方的底部内边距，不会被挡）。
 *
 * 模糊走 Haze：调用方在**页面内容**上挂 `Modifier.haze(state)`，这里用 `hazeChild(state)`
 * 把那份内容的一份模糊拷贝画进胶囊里。注意 Android 上真模糊要求 **API 32+**
 * （Haze 内部的 isBlurEnabledByDefault 就是这么判的），31 及以下会退回 dockScrim() 色纱。
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun GlassBottomBar(
    hazeState: HazeState,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    val items = listOf(
        Triple(Icons.Default.Home, stringResource(R.string.nav_home), 0),
        Triple(Icons.Default.Add, stringResource(R.string.nav_add), 1),
        Triple(Icons.Default.Settings, stringResource(R.string.nav_settings), 2),
    )
    val shape = RoundedCornerShape(26.dp)
    // 在组合期取好画笔：drawBehind 的 lambda 不是 @Composable，里面不能再调 Glass.xxx()
    val rimBrush = Glass.dockStrokeBrush()
    // 底色要够实：Haze 把模糊影像叠在这层之上，底色太透就会和背后的原始内容重影。
    // 想更透 / 更实就把 ultraThin 换成 thin / regular / thick。
    val haze = HazeMaterials
        .ultraThin(containerColor = MaterialTheme.colorScheme.surface)
        .copy(
            blurRadius = DockBlurRadius,
            noiseFactor = 0.04f,
            // API 31 及以下没有模糊路径，用这层色纱兜底
            fallbackTint = HazeTint(Glass.dockScrim()),
        )
    Box(
        Modifier
            // 原来这里是 widthIn(min = 180.dp)，但那只设了**下限**：里面的
            // Row(fillMaxWidth) 会去填父级最大约束（= 屏幕宽），所以那 180dp 从来没生效过，
            // Dock 实际是接近满屏宽的。要「短 20%」得按屏宽比例来
            .fillMaxWidth(DockWidthFraction)
            .shadow(
                elevation = 8.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.07f),
                spotColor = Color.Black.copy(alpha = 0.13f),
            )
            .clip(shape)
            .hazeChild(state = hazeState, style = haze),
    ) {
        // 玻璃质感：顶部高光渐变 + 描边（只覆盖胶囊本体，不铺满屏幕）
        Box(Modifier.matchParentSize().background(Glass.dockHighlight()))
        // 描边用 drawBehind 画而不是 Modifier.border：border 只吃纯色，这里要一条
        // **上亮下透**的渐变边。原来是一圈等亮的 white 0.5 —— 顶部有高光压着看不出来，
        // 底部那条就孤零零浮在近乎透明的填充上，看着就是「胶囊底部有一条不明显的长条」。
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawOutline(
                        outline = shape.createOutline(size, layoutDirection, this),
                        brush = rimBrush,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { (icon, label, idx) ->
                val selected = current == idx
                val pillColor by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                    label = "navPill",
                )
                val iconTint by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "navTint",
                )
                val scale by animateFloatAsState(if (selected) 1.12f else 1f, label = "navScale")
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(pillColor)
                        .clickable { onSelect(idx) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = iconTint,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                    )
                }
            }
        }
    }
}

/** 厂商徽章：有官网 Logo 时显示真实图标，否则回落字母简称 */
@Composable
fun VendorBadge(
    short: String,
    color: Long,
    size: Int = 42,
    templateId: String? = null,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(color).copy(alpha = 0.14f))
            .border(1.dp, Color(color).copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (templateId != null) {
            Image(
                painter = painterResource(logoResFor(templateId)),
                contentDescription = null,
                modifier = Modifier.size((size * 0.62f).toInt().dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = short,
                color = Color(color),
                fontSize = (size / 3).sp,
                fontWeight = FontWeight.Bold,
            )
        }
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
            .clip(RoundedCornerShape(22.dp))
            .background(Glass.highlight())
            .border(1.dp, Glass.stroke(), RoundedCornerShape(22.dp))
            .background(Glass.surfaceStrong())
            .padding(16.dp),
    ) { content() }
}

/** 单个周期额度条 */
@Composable
fun PeriodRow(period: Period, index: Int) {
    val color = PeriodColors[index % PeriodColors.size]
    val usedPct = period.usedPercent ?: 0.0
    val remainPct = period.remainPercent
    val targetFraction = (usedPct / 100.0).toFloat().coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(600),
        label = "periodBar",
    )

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
                .fillMaxWidth(animatedFraction)
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
