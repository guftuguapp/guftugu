package com.guftugu.app.ui.calls

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.guftugu.app.R
import com.guftugu.app.calls.CallReducer
import com.guftugu.app.protocol.EndReason
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.Gold
import com.guftugu.app.ui.theme.GoldRingAvatar
import com.guftugu.app.ui.theme.Ink
import com.guftugu.app.ui.theme.Moonlight
import com.guftugu.app.ui.theme.MoonlightSoft
import com.guftugu.app.ui.theme.Parchment

/*
 * Shared pieces of the two call screens. Every decoration is a single draw pass on the night
 * gradient (DESIGN.md "Incoming call" / "In-call"); colours are allocated once, never per frame.
 */

private val RingFaint = Gold.copy(alpha = 0.10f)
private val RingFainter = Gold.copy(alpha = 0.06f)
private val RingFaintest = Gold.copy(alpha = 0.035f)
private val ControlIdle = Color.White.copy(alpha = 0.14f)
private val ControlHairline = Gold.copy(alpha = 0.45f)
private val PillScrim = Color.Black.copy(alpha = 0.32f)
private val BannerFill = Color.White.copy(alpha = 0.08f)

/** Night gradient with three faint gold rings (the "moon halo") behind the upper third. */
@Composable
fun NightBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brushes.night)
            .drawBehind {
                val c = Offset(size.width / 2f, size.height * 0.36f)
                val stroke = Stroke(width = 1.dp.toPx())
                drawCircle(RingFaint, radius = 150.dp.toPx(), center = c, style = stroke)
                drawCircle(RingFainter, radius = 230.dp.toPx(), center = c, style = stroke)
                drawCircle(RingFaintest, radius = 320.dp.toPx(), center = c, style = stroke)
            },
        content = content,
    )
}

/**
 * Gold-ringed avatar with an expanding halo (one infinite transition; the halo is drawn in a
 * Canvas so the animation only invalidates drawing, never recomposes the screen).
 */
@Composable
fun PulsingGoldAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 120.dp, pulsing: Boolean = true) {
    Box(modifier.size(size * 1.7f), contentAlignment = Alignment.Center) {
        if (pulsing) {
            val transition = rememberInfiniteTransition(label = "halo")
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), RepeatMode.Restart),
                label = "haloProgress",
            )
            Canvas(Modifier.fillMaxSize()) {
                val base = (size / 2).toPx()
                val stroke = Stroke(width = 2.dp.toPx())
                val p1 = progress
                val p2 = (progress + 0.5f) % 1f
                drawCircle(Gold, radius = base * (1f + 0.42f * p1), style = stroke, alpha = (1f - p1) * 0.55f)
                drawCircle(Gold, radius = base * (1f + 0.42f * p2), style = stroke, alpha = (1f - p2) * 0.35f)
            }
        }
        GoldRingAvatar(name = name, size = size, ring = 3.dp)
    }
}

/** Round in-call control: translucent on the night, parchment when [active] (muted / camera off / speaker on). */
@Composable
fun CallControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    size: Dp = 56.dp,
    container: Color = if (active) Parchment else ControlIdle,
    content: Color = if (active) Ink else Color.White,
    enabled: Boolean = true,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(container)
                .border(1.dp, ControlHairline, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (enabled) content else content.copy(alpha = 0.4f), modifier = Modifier.size(size * 0.46f))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MoonlightSoft, maxLines = 1)
    }
}

/** Small status capsule ("Ringing…", the timer) with a gold hairline. */
@Composable
fun CallStatusPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(PillScrim)
            .border(1.dp, Brushes.goldSoft, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Moonlight, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** "🔒 End-to-end encrypted" line under the caller's name. */
@Composable
fun EncryptedBadge(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = Gold, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.call_end_to_end), style = MaterialTheme.typography.labelSmall, color = MoonlightSoft)
    }
}

/** Warm inline banner on the night background (permission problems). Never a dialog. */
@Composable
fun NightBanner(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(BannerFill)
            .border(1.dp, Brushes.goldSoft, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Moonlight)
        if (action != null) {
            Spacer(Modifier.height(6.dp))
            action()
        }
    }
}

/** Human copy for an end reason (wire `EndReason` or the local reasons in [CallReducer]). */
@Composable
fun endReasonText(reason: String?): String = stringResource(
    when (reason) {
        CallReducer.REASON_MISSED -> R.string.call_ended_missed
        EndReason.REJECTED -> R.string.call_ended_rejected
        EndReason.CANCELLED -> R.string.call_ended_cancelled
        EndReason.TIMEOUT -> R.string.call_ended_timeout
        EndReason.UNREACHABLE -> R.string.call_ended_unreachable
        EndReason.FAILED -> R.string.call_ended_failed
        EndReason.ANSWERED_ELSEWHERE -> R.string.call_ended_answered_elsewhere
        CallReducer.REASON_NO_KEY -> R.string.call_ended_no_key
        CallReducer.REASON_NO_PERMISSION -> R.string.call_ended_no_permission
        CallReducer.REASON_BUSY -> R.string.call_ended_busy
        else -> R.string.call_ended
    },
)
