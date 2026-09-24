package com.guftugu.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The Guftugu palette is lifted straight from the logo: a clear sky, a
 * meandering stream, a sunlit meadow, and a bevelled gold frame, all set on
 * warm parchment. Dark mode is the same riverbank at night.
 */
// sky & river
val SkyDeep = Color(0xFF1F6FD0)
val Sky = Color(0xFF63B0EC)
val SkyPale = Color(0xFFDDF2FF)
val RiverDeep = Color(0xFF1A63AE)
val River = Color(0xFF2F86D2)
val RiverLight = Color(0xFF8FD2F4)
val RiverMist = Color(0xFFD2ECFB)

// meadow & forest
val Meadow = Color(0xFF9AD56A)
val Grass = Color(0xFF4F9E33)
val GrassDeep = Color(0xFF2C6B22)
val Forest = Color(0xFF1D5529)
val Foliage = Color(0xFFA5E27E)
val MeadowMist = Color(0xFFDDF3C9)

// earth & gold
val Earth = Color(0xFF6A4B2E)
val Bark = Color(0xFF8B6238)
val GoldLight = Color(0xFFFFF3B8)
val Gold = Color(0xFFE8C65C)
val GoldDeep = Color(0xFFA8832A)
val GoldShadow = Color(0xFF7C5E17)
val GoldMist = Color(0xFFFFF0BE)
/** Border golds: every stop is dark enough to read on parchment, so hairlines never fade out. */
val GoldEdge = Color(0xFFB08A2C)
val GoldEdgeLight = Color(0xFFD4AE45)

/** Wax-seal avatar colours (deep, gold-friendly); picked per person from their name. */
val SealColors: List<Pair<Color, Color>> = listOf(
    Color(0xFF2F6FB5) to Color(0xFF123E70), // river
    Color(0xFF3F8A3A) to Color(0xFF1B4D22), // forest
    Color(0xFF8A3F72) to Color(0xFF4E1B40), // plum
    Color(0xFFB0573A) to Color(0xFF6A2A17), // terracotta
    Color(0xFF1F8A86) to Color(0xFF0D4B49), // teal
    Color(0xFF4B4FA8) to Color(0xFF232766), // indigo
    Color(0xFF7C7A2E) to Color(0xFF45430F), // olive
    Color(0xFF9E3A46) to Color(0xFF5A1621), // garnet
)

// parchment & ink (light)
val Parchment = Color(0xFFFBF7EE)
val ParchmentBright = Color(0xFFFFFDF8)
val ParchmentDeep = Color(0xFFF1E9D8)
val Ink = Color(0xFF1F2A22)
val InkSoft = Color(0xFF4A5A4E)
val OutlineWarm = Color(0xFFB9AE93)

// night (dark)
val NightSky = Color(0xFF0B1B33)
val NightSurface = Color(0xFF10233F)
val NightSurfaceHigh = Color(0xFF1C324F)
val Moonlight = Color(0xFFE8EEF5)
val MoonlightSoft = Color(0xFFB7C4D6)
val OutlineNight = Color(0xFF5C6E86)

val ErrorLight = Color(0xFFB3261E)
val ErrorDark = Color(0xFFF2B8B5)

// chat bubbles
val BubbleMine = Color(0xFFD9EEFB)      // river mist
val BubbleMineDark = Color(0xFF1E4F80)
val BubbleTheirs = Color(0xFFFFFDF8)    // parchment
val BubbleTheirsDark = Color(0xFF1C324F)

/** Shared brushes — allocated once, never per frame. */
object Brushes {
    val gold: Brush = Brush.linearGradient(listOf(GoldLight, Gold, GoldDeep, Gold, GoldShadow))
    /** Borders & hairlines: a metallic shimmer whose every stop stays visible (no pale end). */
    val goldSoft: Brush = Brush.linearGradient(listOf(GoldEdge, GoldEdgeLight, GoldDeep, GoldEdgeLight, GoldEdge))
    /** Fills that want the full light→deep gold sweep (buttons, rings). */
    val goldSheen: Brush = Brush.linearGradient(listOf(GoldLight, Gold, GoldDeep))
    val sky: Brush = Brush.verticalGradient(listOf(SkyDeep, Sky, SkyPale))
    val skyBar: Brush = Brush.horizontalGradient(listOf(SkyDeep, River, Sky))
    val river: Brush = Brush.verticalGradient(listOf(RiverLight, River, RiverDeep))
    val meadow: Brush = Brush.verticalGradient(listOf(Meadow, Grass, GrassDeep))
    val parchment: Brush = Brush.verticalGradient(listOf(ParchmentBright, Parchment, ParchmentDeep))
    val night: Brush = Brush.verticalGradient(listOf(NightSky, NightSurface))
    val avatar: Brush = Brush.linearGradient(listOf(River, Grass))
}
