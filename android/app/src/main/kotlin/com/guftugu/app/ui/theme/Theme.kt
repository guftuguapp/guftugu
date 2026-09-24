package com.guftugu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = River,
    onPrimary = Color.White,
    primaryContainer = RiverMist,
    onPrimaryContainer = Color(0xFF0B3A66),
    secondary = Grass,
    onSecondary = Color.White,
    secondaryContainer = MeadowMist,
    onSecondaryContainer = Color(0xFF14391A),
    tertiary = GoldDeep,
    onTertiary = Color.White,
    tertiaryContainer = GoldMist,
    onTertiaryContainer = Color(0xFF4A3A05),
    error = ErrorLight,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Parchment,
    onBackground = Ink,
    surface = ParchmentBright,
    onSurface = Ink,
    surfaceVariant = ParchmentDeep,
    onSurfaceVariant = InkSoft,
    surfaceContainer = Parchment,
    surfaceContainerHigh = ParchmentDeep,
    surfaceContainerLow = ParchmentBright,
    outline = OutlineWarm,
    outlineVariant = Color(0xFFDDD3BF),
    inverseSurface = Ink,
    inverseOnSurface = Parchment,
)

private val DarkColors = darkColorScheme(
    primary = RiverLight,
    onPrimary = Color(0xFF003055),
    primaryContainer = Color(0xFF1E5A8F),
    onPrimaryContainer = RiverMist,
    secondary = Meadow,
    onSecondary = Color(0xFF0E3312),
    secondaryContainer = GrassDeep,
    onSecondaryContainer = MeadowMist,
    tertiary = Gold,
    onTertiary = Color(0xFF3A2C00),
    tertiaryContainer = GoldShadow,
    onTertiaryContainer = GoldMist,
    error = ErrorDark,
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = NightSky,
    onBackground = Moonlight,
    surface = NightSurface,
    onSurface = Moonlight,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = MoonlightSoft,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerLow = NightSky,
    outline = OutlineNight,
    outlineVariant = Color(0xFF2E4160),
    inverseSurface = Moonlight,
    inverseOnSurface = NightSky,
)

val GuftuguShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Colours that Material's scheme has no slot for (bubbles, gold accents). */
@Immutable
data class CraftColors(
    val bubbleMine: Color,
    val bubbleTheirs: Color,
    val onBubbleMine: Color,
    val onBubbleTheirs: Color,
    val gold: Color,
    val goldDeep: Color,
    val hairline: Color,
    val isDark: Boolean,
)

val LocalCraftColors = staticCompositionLocalOf {
    CraftColors(BubbleMine, BubbleTheirs, Ink, Ink, Gold, GoldDeep, OutlineWarm.copy(alpha = 0.55f), false)
}

/** Accessor: `GuftuguTheme.craft.bubbleMine`. */
object GuftuguTheme {
    val craft: CraftColors
        @Composable get() = LocalCraftColors.current
}

/**
 * Brand theme. Dynamic (wallpaper) colour is deliberately OFF so every phone
 * shows the same riverbank palette; pass `dynamicColor = true` to opt in.
 */
@Composable
fun GuftuguTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val craft = if (darkTheme) {
        CraftColors(BubbleMineDark, BubbleTheirsDark, Moonlight, Moonlight, Gold, GoldDeep, OutlineNight.copy(alpha = 0.6f), true)
    } else {
        CraftColors(BubbleMine, BubbleTheirs, Ink, Ink, Gold, GoldDeep, OutlineWarm.copy(alpha = 0.55f), false)
    }
    CompositionLocalProvider(LocalCraftColors provides craft) {
        MaterialTheme(colorScheme = colorScheme, typography = GuftuguTypography, shapes = GuftuguShapes, content = content)
    }
}
