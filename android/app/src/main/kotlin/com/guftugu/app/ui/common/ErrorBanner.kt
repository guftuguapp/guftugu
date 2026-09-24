package com.guftugu.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.guftugu.app.R
import com.guftugu.app.ui.theme.GuftuguTheme

private val BannerShape = RoundedCornerShape(14.dp)

/**
 * Warm inline error banner (never a dialog, per DESIGN.md). Renders nothing when [message] is null.
 * [onRetry] adds a text action; [onDismiss] a close glyph.
 */
@Composable
fun ErrorBanner(
    message: String?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val text = message ?: ""
        val container = MaterialTheme.colorScheme.errorContainer
        val content = MaterialTheme.colorScheme.onErrorContainer
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(BannerShape)
                .background(container)
                .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), BannerShape)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry), color = content) }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel), tint = content) }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ErrorBannerPreview() {
    GuftuguTheme { ErrorBanner("Could not refresh. Check your connection.", onRetry = {}, onDismiss = {}) }
}
