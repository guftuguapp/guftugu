package com.guftugu.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallMissed
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.guftugu.app.protocol.Attachment
import com.guftugu.app.protocol.ContentType
import com.guftugu.app.ui.media.AttachmentImage
import com.guftugu.app.ui.media.AttachmentVideo
import com.guftugu.app.ui.media.FileAttachment
import com.guftugu.app.ui.media.VoiceNotePlayer
import com.guftugu.app.ui.theme.Grass
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.Meadow
import com.guftugu.app.ui.theme.MineBubbleShape
import com.guftugu.app.ui.theme.TheirsBubbleShape
import kotlin.math.roundToInt

private val MediaShape = RoundedCornerShape(14.dp)
private val MineEdge = androidx.compose.ui.graphics.Color(0xFF9CC6EA)
private val TheirsEdge = com.guftugu.app.ui.theme.GoldEdge.copy(alpha = 0.5f)
private val PillShape = RoundedCornerShape(50)
private val QuoteShape = RoundedCornerShape(10.dp)
private val MediaWidth = 240.dp

/**
 * One message row. [animatedIds] remembers which "new" messages already slid in so a bubble that
 * scrolls off and back does not animate twice. Everything is a single draw pass: shape clip,
 * flat colour, hairline border, no shadows, no layers.
 */
@Composable
fun MessageBubble(
    model: MessageUi,
    maxWidth: Dp,
    animatedIds: MutableSet<String>,
    onOpenMedia: (msgId: String) -> Unit,
    onLongPress: (MessageUi) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = if (remember(model.msgId) { model.isNew && animatedIds.add(model.msgId) }) Modifier.slideIn() else Modifier
    when (val body = model.body) {
        is BubbleBody.System -> SystemPill(body.text, modifier.then(entry))
        is BubbleBody.CallLog -> CallRow(body, modifier.then(entry))
        else -> Bubble(model, maxWidth, modifier.then(entry), onOpenMedia, onLongPress, onRetry)
    }
}

/** 4 dp rise + fade over 120 ms, done with a layout offset and a colour veil (no alpha layer). */
@Composable
private fun Modifier.slideIn(): Modifier {
    val progress = remember { Animatable(0f) }
    val veil = MaterialTheme.colorScheme.background
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(120)) }
    return this
        .offset { IntOffset(0, ((1f - progress.value) * 4.dp.toPx()).roundToInt()) }
        .drawWithContent {
            drawContent()
            val p = progress.value
            if (p < 1f) drawRect(veil, alpha = 1f - p)
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    model: MessageUi,
    maxWidth: Dp,
    modifier: Modifier,
    onOpenMedia: (String) -> Unit,
    onLongPress: (MessageUi) -> Unit,
    onRetry: () -> Unit,
) {
    val craft = GuftuguTheme.craft
    val mine = model.isMine
    val shape: Shape = if (mine) MineBubbleShape else TheirsBubbleShape
    val container = if (mine) craft.bubbleMine else craft.bubbleTheirs
    val content = if (mine) craft.onBubbleMine else craft.onBubbleTheirs
    val isMedia = model.body is BubbleBody.Media && (model.body.type == ContentType.IMAGE || model.body.type == ContentType.VIDEO)
    val failed = model.tick == Tick.FAILED

    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = maxWidth)
                .clip(shape)
                .background(container)
                // A defined, even edge: river-blue for mine, soft gold for theirs.
                .border(1.dp, if (craft.isDark) craft.hairline else if (mine) MineEdge else TheirsEdge, shape)
                .combinedClickable(
                    onClick = { if (failed) onRetry() },
                    onLongClick = { onLongPress(model) },
                )
                .padding(
                    horizontal = if (isMedia) 4.dp else 12.dp,
                    vertical = if (isMedia) 4.dp else 7.dp,
                ),
        ) {
            if (model.showSender) {
                Text(
                    model.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (craft.isDark) Meadow else Grass,
                    modifier = Modifier.padding(start = if (isMedia) 8.dp else 0.dp, bottom = 2.dp),
                )
            }
            if (model.reply != null) {
                ReplyQuoteInset(model.reply, Modifier.padding(bottom = 6.dp))
            }
            when (val body = model.body) {
                is BubbleBody.Text -> LinkedText(body.text, body.links, content)
                is BubbleBody.Media -> MediaBody(model.msgId, body, mine, content, onOpenMedia)
                BubbleBody.Locked -> LockedBody()
                BubbleBody.Deleted -> Text(
                    androidx.compose.ui.res.stringResource(com.guftugu.app.R.string.chat_message_deleted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is BubbleBody.CallLog, is BubbleBody.System -> Unit
            }
            MetaRow(model.time, model.tick, Modifier.align(Alignment.End).padding(top = 2.dp, end = if (isMedia) 6.dp else 0.dp))
        }
    }
}

@Composable
private fun LinkedText(text: String, links: List<LinkSpan>, color: Color) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, links, linkColor) {
        if (links.isEmpty()) null else buildAnnotatedString {
            append(text)
            val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            for (l in links) addLink(LinkAnnotation.Url(l.url, styles), l.start, l.end)
        }
    }
    if (annotated == null) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    } else {
        Text(annotated, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
private fun MediaBody(msgId: String, body: BubbleBody.Media, mine: Boolean, color: Color, onOpenMedia: (String) -> Unit) {
    val att = body.attachment
    when (body.type) {
        ContentType.IMAGE, ContentType.VIDEO -> {
            val height = remember(att.width, att.height) { mediaHeight(att) }
            val frame = Modifier
                .width(MediaWidth)
                .height(height)
                .clip(MediaShape)
                .combinedClickableNoLong { onOpenMedia(msgId) }
            if (body.type == ContentType.IMAGE) AttachmentImage(att, frame, thumbnailOnly = true) else AttachmentVideo(att, frame)
            if (body.caption != null) {
                Box(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp)) { LinkedText(body.caption, body.captionLinks, color) }
            }
        }
        ContentType.AUDIO -> VoiceNotePlayer(att, Modifier.width(MediaWidth), isMine = mine)
        else -> {
            FileAttachment(att, Modifier.widthIn(max = MediaWidth))
            if (body.caption != null) {
                Box(Modifier.padding(top = 6.dp)) { LinkedText(body.caption, body.captionLinks, color) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoLong(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

/** 240 dp wide; height follows the attachment's aspect ratio, clamped so a panorama or a tall shot stays sane. */
private fun mediaHeight(att: Attachment): Dp {
    val w = att.width ?: return 180.dp
    val h = att.height ?: return 180.dp
    if (w <= 0 || h <= 0) return 180.dp
    val aspect = (w.toFloat() / h.toFloat()).coerceIn(0.6f, 1.8f)
    return (MediaWidth.value / aspect).dp
}

@Composable
private fun LockedBody() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
        Text(
            androidx.compose.ui.res.stringResource(com.guftugu.app.R.string.chat_waiting_for_keys),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** Quoted message inside a bubble: gold left border, sender in Grass, one-line preview. */
@Composable
private fun ReplyQuoteInset(quote: ReplyQuote, modifier: Modifier = Modifier) {
    val gold = GuftuguTheme.craft.goldDeep
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    Column(
        modifier
            .fillMaxWidth()
            .clip(QuoteShape)
            .background(bg)
            .drawWithContent {
                drawContent()
                drawLine(gold, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 3.dp.toPx())
            }
            .padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
    ) {
        if (quote.sender.isNotEmpty()) {
            Text(quote.sender, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Grass, maxLines = 1)
        }
        Text(
            quote.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetaRow(time: String, tick: Tick, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val craft = GuftuguTheme.craft
        when (tick) {
            Tick.NONE -> Unit
            Tick.PENDING -> TickIcon(Icons.Outlined.Schedule, MaterialTheme.colorScheme.onSurfaceVariant)
            Tick.SENT -> TickIcon(Icons.Outlined.Done, MaterialTheme.colorScheme.onSurfaceVariant)
            Tick.READ -> TickIcon(Icons.Outlined.DoneAll, if (craft.isDark) craft.gold else craft.goldDeep)
            Tick.FAILED -> TickIcon(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TickIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(start = 4.dp).size(14.dp))
}

/** Centred pill for membership events. */
@Composable
private fun SystemPill(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .clip(PillShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** Compact centred call-log row: phone glyph + "Audio call · 3:21". */
@Composable
private fun CallRow(body: BubbleBody.CallLog, modifier: Modifier = Modifier) {
    val craft = GuftuguTheme.craft
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .clip(PillShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, craft.hairline, PillShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = when {
                body.missed -> Icons.Outlined.CallMissed
                body.video -> Icons.Outlined.Videocam
                else -> Icons.Outlined.Phone
            }
            Icon(
                icon,
                contentDescription = null,
                tint = if (body.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                body.text,
                style = MaterialTheme.typography.labelMedium,
                color = if (body.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
