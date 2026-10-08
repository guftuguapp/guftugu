package com.guftugu.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guftugu.app.R

/**
 * The "craft kit": small, cheap decorations that give every screen the same
 * hand-made riverbank feel. Everything here is a single draw pass — no blur,
 * no nested layers, no per-frame allocations — so it stays fast on budget phones.
 */

/** Hairline gold frame around any shape. */
fun Modifier.goldBorder(width: Dp = 1.2.dp, shape: Shape = RoundedCornerShape(16.dp)): Modifier =
    this.border(width, Brushes.goldSoft, shape)

/** Page background: parchment fading from a hint of sky at the top (night gradient in dark mode). */
@Composable
fun RiverbankBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = GuftuguTheme.craft.isDark
    val brush = remember(dark) {
        if (dark) Brushes.night
        else Brush.verticalGradient(0f to Color(0xFFEAF5FD), 0.28f to Parchment, 1f to Parchment)
    }
    // The faint gold line-art riverbank rests along the bottom of every page (one bitmap).
    val art = ImageBitmap.imageResource(R.drawable.art_chat_footer)
    val tint = remember(dark) {
        androidx.compose.ui.graphics.ColorFilter.tint(if (dark) Gold.copy(alpha = 0.14f) else GoldDeep.copy(alpha = 0.18f), androidx.compose.ui.graphics.BlendMode.SrcIn)
    }
    Box(
        modifier
            .fillMaxSize()
            .background(brush)
            .drawBehind {
                val w = size.width
                val h = w * art.height / art.width
                drawImage(
                    art,
                    dstOffset = androidx.compose.ui.unit.IntOffset(0, (size.height - h).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
                    colorFilter = tint,
                )
            },
        content = content,
    )
}

/**
 * Sky-gradient top bar with a gold hairline underneath; title in the display serif, centred.
 * [alignStart] is the conversation header: name and status line left-aligned one under the other
 * in the chat's body font (the owner asked for this, WhatsApp-style).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    alignStart: Boolean = false,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier
            .background(Brushes.skyBar)
            .drawBehind {
                // gold edge at the bottom: dark rule under a bright line (reads on sky and parchment)
                drawLine(GoldShadow, Offset(0f, size.height - 1.5f), Offset(size.width, size.height - 1.5f), strokeWidth = 3f)
                drawLine(Brushes.goldSoft, Offset(0f, size.height - 4f), Offset(size.width, size.height - 4f), strokeWidth = 3f)
                drawLine(GoldLight, Offset(0f, size.height - 5.5f), Offset(size.width, size.height - 5.5f), strokeWidth = 1f)
            },
    ) {
        if (alignStart) {
            androidx.compose.material3.TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 22.sp),
                            color = Color.White,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                color = Color.White.copy(alpha = 0.88f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = navigationIcon,
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        } else {
            CenterAlignedTopAppBar(
                title = {
                    Box(contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
                            if (subtitle != null) {
                                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                            }
                        }
                    }
                },
                navigationIcon = navigationIcon,
                actions = actions,
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        }
    }
}

/** A parchment card with a gold hairline — the default container for content blocks. */
@Composable
fun ParchmentCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier.goldBorder(shape = shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) { Box(content = content) }
}

/** Circular avatar with initials on a river→meadow gradient and a gold ring. */
@Composable
fun GoldRingAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, ring: Dp = 2.dp, content: (@Composable BoxScope.() -> Unit)? = null) {
    val initials = remember(name) { initialsOf(name) }
    Box(
        modifier
            .size(size)
            .border(ring, Brushes.gold, CircleShape)
            .padding(ring + 1.dp)
            .clip(CircleShape)
            .background(Brushes.avatar),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else {
            Text(
                initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.38f).sp,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

/** A small gold flourish: line — diamond — line. Use between sections and under titles. */
@Composable
fun Ornament(modifier: Modifier = Modifier, width: Dp = 120.dp) {
    val gold = GuftuguTheme.craft.gold
    val deep = GuftuguTheme.craft.goldDeep
    Canvas(modifier.width(width).height(12.dp)) {
        val cy = size.height / 2f
        val cx = size.width / 2f
        val d = 5.dp.toPx()
        drawLine(deep, Offset(0f, cy), Offset(cx - d - 4.dp.toPx(), cy), strokeWidth = 1.5f)
        drawLine(deep, Offset(cx + d + 4.dp.toPx(), cy), Offset(size.width, cy), strokeWidth = 1.5f)
        val diamond = Path().apply {
            moveTo(cx, cy - d); lineTo(cx + d, cy); lineTo(cx, cy + d); lineTo(cx - d, cy); close()
        }
        drawPath(diamond, gold)
        drawPath(diamond, deep, style = Stroke(width = 1.2f))
        drawCircle(gold, radius = 1.8f, center = Offset(cx - d - 9.dp.toPx(), cy))
        drawCircle(gold, radius = 1.8f, center = Offset(cx + d + 9.dp.toPx(), cy))
    }
}



/** The logo medallion (pre-rendered PNG; cheap to draw). */
@Composable
fun LogoMedallion(modifier: Modifier = Modifier, size: Dp = 160.dp) {
    Image(
        painter = painterResource(R.drawable.logo_medallion),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

/** Primary call-to-action with a gold gradient (enroll, unlock, send). */
@Composable
fun GoldButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        // min height, not fixed: a label that wraps to two lines (e.g. "Send via WhatsApp, SMS, email…"
        // in a dialog) must grow the button instead of being cut off at the bottom
        modifier = modifier
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(if (enabled) Brushes.gold else Brush.linearGradient(listOf(OutlineWarm, OutlineWarm))),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Ink, disabledContainerColor = Color.Transparent, disabledContentColor = Ink.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(25.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** Chat bubble shapes: the sender's side gets a small square corner ("tail"). */
val MineBubbleShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
val TheirsBubbleShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)

/** Heading block used on the join/enroll/unlock screens: serif title + ornament + caption. */
@Composable
fun CraftHeading(title: String, caption: String? = null, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Ornament(Modifier.padding(top = 6.dp, bottom = 8.dp))
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** Unread count pill: gold on forest green. */
@Composable
fun GoldBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Forest)
            .border(1.dp, Brushes.goldSoft, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else count.toString(), color = GoldLight, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** Row helper used by list items: avatar | content | trailing. */
@Composable
fun CraftListRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

// ---------------------------------------------------------------------------------------------
// Illustrated pieces (pre-rendered art from design/: drawn as single bitmaps — cheap)
// ---------------------------------------------------------------------------------------------

/**
 * The chat-list header: the rendered riverbank (a low sun over a meadow, trees, the river with its paper
 * boat) with a gold serif title in its lower right, finished with a gold band. Draws the status-bar area too.
 */
@Composable
fun RiverbankHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(modifier.fillMaxWidth().height(androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 150.dp)) {
        Image(
            painter = painterResource(R.drawable.art_panorama),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            alignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxSize(),
        )
        // a light scrim at the top: the outlined title reads on its own, and the rendered sky and sun stay bright
        Box(Modifier.fillMaxSize().background(HeaderScrim))
        Row(
            Modifier.fillMaxWidth().statusBarsPaddingCompat().padding(top = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White, content = { actions() })
        }
        androidx.compose.foundation.layout.Column(
            Modifier.align(TitlePlacement).padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // Legible on any art (letters used to vanish against bright sky and clouds): a dark outline
            // drawn under the gold letters and a tight shadow.
            Box(contentAlignment = Alignment.Center) {
                Text(
                    title,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 7f, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                    ),
                    color = TitleOutline,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, shadow = TitleShadow),
                    color = GoldLight,
                )
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium.copy(shadow = TitleShadow), color = Color.White)
            }
        }
        GoldBand(Modifier.align(Alignment.BottomCenter))
    }
}

/** The title's place on the banner: at the end, its middle 70% of the way down (the owner's choice), clear of the sun and sky. */
private val TitlePlacement = Alignment { size, space, layoutDirection ->
    val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) space.width - size.width else 0
    val y = (space.height * 0.7f - size.height / 2f).toInt().coerceIn(0, maxOf(0, space.height - size.height))
    androidx.compose.ui.unit.IntOffset(x, y)
}
private val HeaderScrim = Brush.verticalGradient(0f to Color(0x40102A4C), 0.5f to Color(0x10102A4C), 1f to Color(0x00000000))
private val TitleShadow = androidx.compose.ui.graphics.Shadow(Color(0xCC1E1200), Offset(0f, 2f), blurRadius = 4f)
private val TitleOutline = Color(0xD93A2608)

/** A plain gold band: dark edges around a bright centre line (no ornament — it must not compete with section headers). */
@Composable
fun GoldBand(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val band = 4.dp.toPx()
        val y = size.height - band / 2 - 1f
        drawRect(Brushes.goldSoft, topLeft = Offset(0f, y - band / 2), size = androidx.compose.ui.geometry.Size(size.width, band))
        drawLine(GoldLight, Offset(0f, y - 0.5f), Offset(size.width, y - 0.5f), strokeWidth = 1.2f)
        drawLine(GoldShadow, Offset(0f, y + band / 2), Offset(size.width, y + band / 2), strokeWidth = 1.5f)
    }
}

/** A gold rule that fills the rest of its row, starting with a small diamond (section headers). */
@Composable
fun GoldRule(modifier: Modifier = Modifier) {
    val gold = GuftuguTheme.craft.gold
    val deep = GuftuguTheme.craft.goldDeep
    Canvas(modifier.height(12.dp)) {
        val cy = size.height / 2f
        val d = 4.5.dp.toPx()
        val diamond = Path().apply { moveTo(d, cy - d); lineTo(d * 2, cy); lineTo(d, cy + d); lineTo(0f, cy); close() }
        drawPath(diamond, gold)
        drawPath(diamond, deep, style = Stroke(width = 1.2f))
        drawLine(deep, Offset(d * 2 + 4.dp.toPx(), cy), Offset(size.width, cy), strokeWidth = 1.5f)
    }
}

@Composable
private fun Modifier.statusBarsPaddingCompat(): Modifier = this.then(Modifier.statusBarsPadding())

/**
 * Chat background: parchment with the gold line-art riverbank resting faintly along the bottom
 * (one pre-rendered bitmap, drawn once behind the list; night mode uses a moonlit tint).
 *
 * [leaves]: the chat room instead fills the whole background with one floral line drawing in gold
 * (design/make_art.py `chat_leaves`: ornate leaves and petalled flowers, every shape unique, as the
 * owner asked). The background doesn't scroll, so it is a single screen-sized picture, centre-cropped;
 * decoded once off the main thread and kept as an 8-bit alpha mask (~2.6 MB), tinted when drawn.
 */
@Composable
fun ChatWallpaper(modifier: Modifier = Modifier, bottomInset: Dp = 58.dp, leaves: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val dark = GuftuguTheme.craft.isDark
    val bg = MaterialTheme.colorScheme.background
    if (leaves) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val art by androidx.compose.runtime.produceState(LeafArt.cached, context) {
            if (value == null) value = LeafArt.load(context)
        }
        val tint = remember(dark) {
            androidx.compose.ui.graphics.ColorFilter.tint(if (dark) Gold.copy(alpha = 0.2f) else GoldDeep.copy(alpha = 0.32f), androidx.compose.ui.graphics.BlendMode.SrcIn)
        }
        Box(
            modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(bg)
                    val img = art ?: return@drawBehind
                    val scale = maxOf(size.width / img.width, size.height / img.height)
                    val srcW = (size.width / scale).toInt().coerceIn(1, img.width)
                    val srcH = (size.height / scale).toInt().coerceIn(1, img.height)
                    drawImage(
                        img,
                        srcOffset = androidx.compose.ui.unit.IntOffset((img.width - srcW) / 2, (img.height - srcH) / 2),
                        srcSize = androidx.compose.ui.unit.IntSize(srcW, srcH),
                        dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                        colorFilter = tint,
                    )
                },
            content = content,
        )
    } else {
        GoldFooterWallpaper(modifier, bottomInset, dark, bg, content)
    }
}

@Composable
private fun GoldFooterWallpaper(modifier: Modifier, bottomInset: Dp, dark: Boolean, bg: Color, content: @Composable BoxScope.() -> Unit) {
    val art = ImageBitmap.imageResource(R.drawable.art_chat_footer)
    val tint = remember(dark) {
        androidx.compose.ui.graphics.ColorFilter.tint(if (dark) Gold.copy(alpha = 0.16f) else GoldDeep.copy(alpha = 0.22f), androidx.compose.ui.graphics.BlendMode.SrcIn)
    }
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(bg)
                val w = size.width
                val h = w * art.height / art.width
                drawRect(bg, topLeft = Offset(0f, size.height - bottomInset.toPx()))
                drawImage(
                    art,
                    dstOffset = androidx.compose.ui.unit.IntOffset(0, (size.height - h - bottomInset.toPx()).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
                    colorFilter = tint,
                )
            },
        content = content,
    )
}

/** Gold round button with a dark-gold rim — the "new conversation" quill. */
@Composable
fun GoldFab(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, icon: Int = R.drawable.ic_quill) {
    Box(
        modifier
            .size(60.dp)
            .shadow(3.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(Brushes.gold)
            .border(1.5.dp, GoldShadow, CircleShape)
            .padding(3.dp)
            .border(1.dp, GoldLight.copy(alpha = 0.9f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = Ink,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * Wax-seal monogram: a deep per-person colour, gold serif initials, a double gold ring. Stable per
 * name (hash → [SealColors]); groups get a meadow-green outer ring.
 */
@Composable
fun SealAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 48.dp, group: Boolean = false, content: (@Composable BoxScope.() -> Unit)? = null) {
    val initials = remember(name) { initialsOf(name) }
    val colors = remember(name) { SealColors[(name.hashCode() and 0x7FFFFFFF) % SealColors.size] }
    val fill = remember(colors) { Brush.linearGradient(listOf(colors.first, colors.second)) }
    Box(
        modifier
            .size(size)
            .border(if (group) 2.dp else 1.5.dp, if (group) SolidColorBrush(Grass) else Brushes.goldSoft, CircleShape)
            .padding(2.dp)
            .border(1.5.dp, Brushes.gold, CircleShape)
            .padding(1.5.dp)
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else {
            Text(
                initials,
                color = GoldLight,
                fontFamily = Display,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
                style = MaterialTheme.typography.titleLarge.copy(shadow = SealShadow),
            )
        }
    }
}

private val SealShadow = androidx.compose.ui.graphics.Shadow(Color(0x66000000), Offset(0f, 1.5f), blurRadius = 3f)
private fun SolidColorBrush(c: Color): Brush = androidx.compose.ui.graphics.SolidColor(c)

/** The chat-room floral drawing, decoded once per process and kept as an alpha mask (1 byte per pixel). */
private object LeafArt {
    @Volatile var cached: ImageBitmap? = null

    suspend fun load(context: android.content.Context): ImageBitmap? = cached ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        cached ?: runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inScaled = false }
            val full = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.art_chat_leaves, opts)
            val mask = full.extractAlpha()
            if (mask !== full) full.recycle()
            mask.asImageBitmap()
        }.getOrNull()?.also { cached = it }
    }
}
