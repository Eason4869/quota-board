package com.yusheng.quota.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

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
        typography = Typography().let { it },
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

/** 液态玻璃材质 */
object Glass {
    val surfaceLight = Color(0xB3FFFFFF)
    val surfaceDark = Color(0x33FFFFFF)
    val strokeLight = Color(0x66FFFFFF)
    val strokeDark = Color(0x2EFFFFFF)

    @Composable
    fun surface(): Color = if (isSystemInDarkTheme()) surfaceDark else surfaceLight

    @Composable
    fun stroke(): Color = if (isSystemInDarkTheme()) strokeDark else strokeLight
}

