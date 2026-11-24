package org.sodahub.sodam.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

private val DefaultSeed = Color(0xFF34D6C4)

private val DarkColors = darkColorScheme(
    background = Color(0xFF0B0F12),
    surface = Color(0xFF12181D),
)

private val LightColors = lightColorScheme()

/** 简洁风格：中性底色 + 保留强调色，容器色弱化（参考 NeriPlayer 的 Clean 皮肤）。 */
private val CleanLight = NeutralPalette(
    background = Color(0xFFF6F7F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEFF1),
    onSurface = Color(0xFF191B1E),
    onSurfaceVariant = Color(0xFF5B5F66),
    outline = Color(0xFFD3D6DB),
    outlineVariant = Color(0xFFE4E7EB),
)

private val CleanDark = NeutralPalette(
    background = Color(0xFF0D0F11),
    surface = Color(0xFF15181B),
    surfaceVariant = Color(0xFF1E2226),
    onSurface = Color(0xFFE6E8EA),
    onSurfaceVariant = Color(0xFFA1A6AD),
    outline = Color(0xFF3A3F45),
    outlineVariant = Color(0xFF272C31),
)

private data class NeutralPalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
)

private val CleanShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/**
 * 主题由四部分组成：
 * - [themeMode]：system / light / dark
 * - [themeColorHex]：主题色种子（6 位 HEX）
 * - [paletteStyle]：调色板风格（TonalSpot / Neutral / Vibrant / ...）
 * - [uiStyle]：界面风格 —— `material`（Material You）/ `clean`（简洁）
 */
@Composable
fun SodamTheme(
    themeMode: String = "system",
    themeColorHex: String = DEFAULT_THEME_COLOR,
    paletteStyle: String = DEFAULT_PALETTE_STYLE,
    uiStyle: String = DEFAULT_UI_STYLE,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = remember(dark, themeColorHex, paletteStyle, uiStyle) {
        buildScheme(dark, themeColorHex, paletteStyle, uiStyle)
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = if (uiStyle == UI_STYLE_CLEAN) CleanShapes else Shapes(),
    ) {
        // 必须铺一层 Surface：它同时提供主题背景色和正确的 LocalContentColor。
        // 缺了它，Text 默认用黑色，在黑色窗口背景上就完全看不见。
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            content()
        }
    }
}

private data class PaletteTone(
    val hueShift: Float,
    val saturationScale: Float,
    val lightValue: Float,
    val darkValue: Float,
)

private fun paletteTone(style: String): PaletteTone = when (style) {
    "vibrant" -> PaletteTone(0f, 1.15f, 0.84f, 1f)
    "neutral" -> PaletteTone(0f, 0.18f, 0.55f, 0.9f)
    "expressive" -> PaletteTone(38f, 1.1f, 0.8f, 1f)
    "monochrome" -> PaletteTone(0f, 0f, 0.42f, 0.92f)
    "fidelity" -> PaletteTone(0f, 1f, 0.86f, 1f)
    else -> PaletteTone(0f, 1f, 0.8f, 1f) // tonal spot
}

private fun buildScheme(
    dark: Boolean,
    hex: String,
    paletteStyle: String,
    uiStyle: String,
): ColorScheme {
    val base = if (dark) DarkColors else LightColors
    val seed = parseSeed(hex)
    val tone = paletteTone(paletteStyle)
    val primary = seed.adjustHsv(
        hueShift = tone.hueShift,
        saturationScale = tone.saturationScale,
        valueScale = if (dark) tone.darkValue else tone.lightValue,
    )
    val secondary = seed.adjustHsv(
        hueShift = tone.hueShift - 18f,
        saturationScale = tone.saturationScale * 0.8f,
        valueScale = if (dark) tone.darkValue * 0.9f else tone.lightValue,
    )
    val onPrimary = if (primary.luminance() > 0.45f) Color(0xFF07120F) else Color.White
    val primaryContainer = lerp(primary, base.surface, if (dark) 0.72f else 0.86f)
    val onPrimaryContainer = if (dark) lerp(primary, Color.White, 0.55f) else lerp(primary, Color.Black, 0.45f)
    val accentScheme = base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onPrimary,
        secondaryContainer = lerp(secondary, base.surface, if (dark) 0.78f else 0.9f),
        onSecondaryContainer = onPrimaryContainer,
        tertiary = secondary,
        surfaceTint = primary,
    )
    if (uiStyle != UI_STYLE_CLEAN) return accentScheme

    // 简洁风格：底色与容器全部中性化，只保留主色的强调作用。
    val neutral = if (dark) CleanDark else CleanLight
    val tintAmount = if (dark) 0.05f else 0.03f
    fun tinted(color: Color) = lerp(color, primary, tintAmount)
    return accentScheme.copy(
        background = tinted(neutral.background),
        surface = tinted(neutral.surface),
        surfaceVariant = tinted(neutral.surfaceVariant),
        surfaceContainerLowest = tinted(neutral.surface),
        surfaceContainerLow = tinted(neutral.surface),
        surfaceContainer = tinted(neutral.surfaceVariant),
        surfaceContainerHigh = tinted(neutral.surfaceVariant),
        surfaceContainerHighest = tinted(neutral.surfaceVariant),
        onBackground = neutral.onSurface,
        onSurface = neutral.onSurface,
        onSurfaceVariant = neutral.onSurfaceVariant,
        outline = neutral.outline,
        outlineVariant = neutral.outlineVariant,
        primaryContainer = lerp(primary, neutral.surfaceVariant, 0.82f),
        onPrimaryContainer = if (dark) lerp(primary, Color.White, 0.5f) else lerp(primary, Color.Black, 0.5f),
        secondaryContainer = lerp(secondary, neutral.surfaceVariant, 0.88f),
        onSecondaryContainer = neutral.onSurface,
        tertiaryContainer = lerp(secondary, neutral.surfaceVariant, 0.9f),
        onTertiaryContainer = neutral.onSurface,
        surfaceTint = Color.Transparent,
    )
}

private fun parseSeed(hex: String): Color {
    val clean = hex.trim().removePrefix("#")
    val value = clean.toLongOrNull(16)
    return if (clean.length == 6 && value != null) Color(0xFF000000 or value) else DefaultSeed
}

private fun Color.adjustHsv(
    hueShift: Float,
    saturationScale: Float,
    valueScale: Float,
): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[0] = ((hsv[0] + hueShift) % 360f + 360f) % 360f
    hsv[1] = (hsv[1] * saturationScale).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * valueScale).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
