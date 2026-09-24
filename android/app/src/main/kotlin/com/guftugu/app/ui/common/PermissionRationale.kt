package com.guftugu.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import com.guftugu.app.ui.theme.GoldButton
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.ParchmentCard

/**
 * A small parchment card explaining why a runtime permission is needed, with an
 * "Allow" GoldButton and a quiet "Not now". Used before the system prompt.
 */
@Composable
fun PermissionRationale(
    icon: ImageVector,
    title: String,
    text: String,
    allowLabel: String,
    laterLabel: String,
    onAllow: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ParchmentCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onLater) { Text(laterLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                GoldButton(allowLabel, onClick = onAllow, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionRationalePreview() {
    GuftuguTheme {
        PermissionRationale(
            icon = Icons.Outlined.Notifications,
            title = "Hear about new messages",
            text = "Allow notifications so calls ring and messages show up when Guftugu is in the background.",
            allowLabel = "Allow",
            laterLabel = "Not now",
            onAllow = {},
            onLater = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
