package com.yusheng.quota.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFFFF6900)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFF0B0E13),
    onBackground = Color(0xFFF3F7FC),
    surface = Color(0xFF141A22),
    onSurface = Color(0xFFF3F7FC),
    surfaceVariant = Color(0xFF1C2430),
    onSurfaceVariant = Color(0xFF7E8B9C),
    outline = Color(0xFF2A3545),
)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFF3F5F9),
    onBackground = Color(0xFF152033),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF152033),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF6B7A90),
    outline = Color(0xFFD7DEE8),
)

@Composable
fun QuotaBoardTheme(
    darkMode: String = "system",
    content: @Composable () -> Unit,
) {
    val dark = when (darkMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}

/** 周期窗口配色 */
val PeriodColors = listOf(
    Color(0xFFFF8A3D),
    Color(0xFF5B9DFF),
    Color(0xFF2FD48B),
    Color(0xFFC084FC),
)

/**
 * 液态玻璃材质：半透明表面 + 顶部高光 + 高光描边。
 *
 * 卡片走「上亮下暗 + 白描边」近似苹果 Liquid Glass —— Android 没有系统级的 backdrop
 * blur 接口，卡片这层做不了真模糊。Dock 例外：它有真正的背景模糊，走 Haze，
 * 见 ui/Components.kt 的 GlassBottomBar。
 */
object Glass {
    @Composable
    fun isDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

    @Composable
    fun surface(): Color =
        if (isDark()) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.72f)

    @Composable
    fun surfaceStrong(): Color =
        if (isDark()) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.88f)

    @Composable
    fun stroke(): Color =
        if (isDark()) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.92f)

    @Composable
    fun highlight(): Brush =
        if (isDark()) {
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.18f),
                0.45f to Color.White.copy(alpha = 0.04f),
                1f to Color.Transparent,
            )
        } else {
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.55f),
                0.45f to Color.White.copy(alpha = 0.12f),
                1f to Color.Transparent,
            )
        }

    @Composable
    fun background(): Brush =
        if (isDark()) {
            Brush.verticalGradient(
                0f to Color(0xFF121826),
                0.45f to Color(0xFF0B0E13),
                1f to Color(0xFF151C2A),
            )
        } else {
            Brush.verticalGradient(
                0f to Color(0xFFEAF1FB),
                0.45f to Color(0xFFF7F9FC),
                1f to Color(0xFFE7EEF8),
            )
        }

    /**
     * Dock 顶部高光：叠在 Haze 磨砂底之上的一道内高光，模拟玻璃的厚度。
     *
     * 注意：下面几个「dock*」成员与 highlight()/stroke() 是**分开的两套** ——
     * 卡片用后者，Dock 用前者，别混用，否则调 Dock 会连带把卡片也改掉。
     */
    @Composable
    fun dockHighlight(): Brush =
        if (isDark()) {
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.09f),
                0.45f to Color.White.copy(alpha = 0.02f),
                1f to Color.Transparent,
            )
        } else {
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.16f),
                0.45f to Color.White.copy(alpha = 0.04f),
                1f to Color.Transparent,
            )
        }

    /**
     * Dock 描边：**上亮下透的渐变**，不是一圈等亮的实边。
     *
     * 等亮描边在顶部被高光压着看不出来，底部那一段却会孤零零地浮在填充上，
     * 看上去就是「胶囊底部有一条不明显的长条」。
     */
    @Composable
    fun dockStrokeBrush(): Brush =
        if (isDark()) {
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.22f),
                0.5f to Color.White.copy(alpha = 0.10f),
                1f to Color.White.copy(alpha = 0.04f),
            )
        } else {
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.85f),
                0.5f to Color.White.copy(alpha = 0.40f),
                1f to Color.White.copy(alpha = 0.16f),
            )
        }

    /**
     * API < 32 上没有任何可用的模糊路径，Haze 会退回「色纱」兜底（[HazeStyle.fallbackTint]）。
     * 这里给接近不透明的表面色，免得老系统上 Dock 从磨砂玻璃突然变成一块几乎透明的板子。
     */
    @Composable
    fun dockScrim(): Color =
        if (isDark()) Color(0xFF141A22).copy(alpha = 0.88f) else Color(0xFFFFFFFF).copy(alpha = 0.84f)

    private fun Color.luminance(): Float = (0.299f * red + 0.587f * green + 0.114f * blue)
}
