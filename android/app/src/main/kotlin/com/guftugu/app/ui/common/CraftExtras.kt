package com.guftugu.app.ui.common

import com.guftugu.app.ui.theme.SealAvatar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.guftugu.app.R
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.GoldRingAvatar
import com.guftugu.app.ui.theme.Grass
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.Ornament

/**
 * [GoldRingAvatar] that shows the user's photo when an avatar key resolves to a URL, initials
 * otherwise. [resolveUrl] is the repository's presigned-URL lookup (null → initials). Coil gets a
 * size hint and a 120 ms crossfade per DESIGN.md.
 */
@Composable
fun AvatarImage(
    name: String,
    avatarKey: String?,
    resolveUrl: (suspend (String) -> String?)?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    ring: Dp = 2.dp,
    groupRing: Boolean = false,
) {
    val url by produceState<String?>(initialValue = null, key1 = avatarKey) {
        value = if (avatarKey != null && resolveUrl != null) runCatching { resolveUrl(avatarKey) }.getOrNull() else null
    }
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    SealAvatar(
        name = name,
        modifier = modifier,
        size = size,
        group = groupRing,
        content = if (url == null) null else {
            {
                val request = remember(url, px) {
                    ImageRequest.Builder(context).data(url).size(px).crossfade(120).build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            }
        },
    )
}

private val PillShape = RoundedCornerShape(50)

/** Parchment search pill with a gold hairline on focus; used by the chats and contacts screens. */
@Composable
fun SearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hairline = GuftuguTheme.craft.hairline
    val borderBrush = if (focused) Brushes.goldSoft else remember(hairline) { SolidColor(hairline) }
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderBrush, PillShape)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                interactionSource = interaction,
                textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge).copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (onClose != null || query.isNotEmpty()) {
            IconButton(onClick = { if (query.isNotEmpty()) onQueryChange("") else onClose?.invoke() }) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.chats_close_search), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Section header used above lists: small-caps label with a thin ornament on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val upper = remember(title) { title.uppercase() }
        Text(
            upper,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
        )
        com.guftugu.app.ui.theme.GoldRule(Modifier.padding(start = 12.dp).weight(1f))
    }
}
