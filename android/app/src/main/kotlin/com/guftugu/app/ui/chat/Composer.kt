package com.guftugu.app.ui.chat

import androidx.compose.ui.res.painterResource
import com.guftugu.app.ui.theme.GoldLight
import com.guftugu.app.ui.theme.GoldShadow
import com.guftugu.app.ui.theme.Ink

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guftugu.app.R
import com.guftugu.app.ui.media.VoiceRecorderButton
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.Grass
import com.guftugu.app.ui.theme.GuftuguTheme

private val PillShape = RoundedCornerShape(24.dp)
private val StripShape = RoundedCornerShape(12.dp)

/**
 * Parchment pill composer: attach (+) · multi-line text (≤ 5 lines) · mic ⇄ send (120 ms crossfade).
 * The draft lives in the ViewModel ([draft] is its collected state); [replyTo] shows a strip above.
 */
@Composable
fun Composer(
    draft: State<String>,
    replyTo: ReplyQuote?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onVoiceRecorded: (Uri, Long) -> Unit,
    onCancelReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasText by remember { derivedStateOf { draft.value.isNotBlank() } }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val craft = GuftuguTheme.craft
    val hairlineBrush = remember(craft.hairline) { SolidColor(craft.hairline) }

    // Transparent: the chat wallpaper's gold line-art runs behind the composer.
    Column(modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
        if (replyTo != null) {
            ReplyStrip(replyTo, onCancelReply, Modifier.padding(bottom = 6.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(PillShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, if (focused) Brushes.goldSoft else hairlineBrush, PillShape),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.composer_attach), tint = MaterialTheme.colorScheme.primary)
                }
                Box(Modifier.weight(1f).padding(end = 12.dp, top = 12.dp, bottom = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (draft.value.isEmpty()) {
                        Text(stringResource(R.string.composer_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = draft.value,
                        onValueChange = onDraftChange,
                        maxLines = 5,
                        interactionSource = interaction,
                        textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge).copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Box(Modifier.padding(start = 6.dp).size(48.dp), contentAlignment = Alignment.Center) {
                Crossfade(targetState = hasText, animationSpec = tween(120), label = "micSend") { showSend ->
                    if (showSend) SendButton(onSend) else VoiceRecorderButton(onRecorded = onVoiceRecorded, modifier = Modifier.size(48.dp))
                }
            }
        }
    }
}

@Composable
private fun SendButton(onSend: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Brushes.gold)
            .border(1.5.dp, GoldShadow, CircleShape)
            .padding(2.5.dp)
            .border(0.8.dp, GoldLight, CircleShape)
            .clickable(onClick = onSend),
        contentAlignment = Alignment.Center,
    ) {
        // Send = a paper boat setting off down the stream (the logo's motif).
        Icon(painterResource(R.drawable.ic_paper_boat), contentDescription = stringResource(R.string.composer_send), tint = Ink, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun ReplyStrip(quote: ReplyQuote, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val gold = GuftuguTheme.craft.goldDeep
    Row(
        modifier
            .fillMaxWidth()
            .clip(StripShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, GuftuguTheme.craft.hairline, StripShape)
            .drawWithContent {
                drawContent()
                drawLine(gold, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 3.dp.toPx())
            }
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.composer_replying_to, quote.sender),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Grass,
                maxLines = 1,
            )
            Text(quote.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.composer_reply_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
