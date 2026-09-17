package com.dietcam.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 全局配色。深色为主，绿色作强调色。 */
object Palette {
    val Background = Color(0xFF0F1115)
    val Surface = Color(0xFF181B21)
    val SurfaceHigh = Color(0xFF22262E)
    val Outline = Color(0xFF2E333C)

    val Accent = Color(0xFF4ADE80)
    val AccentDim = Color(0xFF1F6F4A)
    val OnAccent = Color(0xFF0B1F14)

    val Protein = Color(0xFF60A5FA)
    val Carbs = Color(0xFFFBBF24)
    val Fat = Color(0xFFF472B6)

    val TextPrimary = Color(0xFFF2F5F8)
    val TextSecondary = Color(0xFF98A2B3)
    val TextTertiary = Color(0xFF6B7280)

    val Danger = Color(0xFFF87171)
    val Warning = Color(0xFFFBBF24)
    val Info = Color(0xFF7DD3FC)

    val Scrim = Color(0xE6000000)
}

/** 稍微大一点的圆角，整体更柔和。 */
object Radii {
    val sm = 10.dp
    val md = 16.dp
    val lg = 22.dp
    val xl = 28.dp
}

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

private val AppColors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentDim,
    onPrimaryContainer = Palette.TextPrimary,
    secondary = Palette.Info,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextSecondary,
    outline = Palette.Outline,
    error = Palette.Danger,
    onError = Color.White,
)

@Composable
fun DietCamTheme(content: @Composable () -> Unit) {
    // 目前只做深色；浅色留作后续扩展
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = AppColors,
        typography = AppTypography,
        content = content,
    )
}
