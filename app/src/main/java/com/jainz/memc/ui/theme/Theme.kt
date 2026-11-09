package com.jainz.memc.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Cinematic dark palette: near-black canvas, soft raised surfaces, warm amber accent. */
object Palette {
    val Canvas = Color(0xFF0B0D10)
    val Surface = Color(0xFF15181D)
    val SurfaceHigh = Color(0xFF1E222A)
    val Stroke = Color(0x22FFFFFF)
    val Accent = Color(0xFFFFB547)
    val AccentDim = Color(0x33FFB547)
    val Text = Color(0xFFF2F3F5)
    val TextDim = Color(0xFFA2A8B3)
    val TextFaint = Color(0xFF6B717C)
    val Danger = Color(0xFFFF6B6B)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF1A1206),
    primaryContainer = Palette.AccentDim,
    onPrimaryContainer = Palette.Accent,
    secondary = Color(0xFF9FB4FF),
    background = Palette.Canvas,
    onBackground = Palette.Text,
    surface = Palette.Canvas,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Surface,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    surfaceContainerLow = Palette.Surface,
    surfaceContainerLowest = Palette.Canvas,
    outline = Palette.Stroke,
    outlineVariant = Palette.Stroke,
    error = Palette.Danger,
)

private val type = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
    )
}

@Composable
fun MemcTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
