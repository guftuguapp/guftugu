package com.guftugu.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.LogoMedallion
import com.guftugu.app.ui.theme.Ornament

/** Centred medallion + serif title + caption. Optional [action] slot (e.g. a GoldButton). */
@Composable
fun EmptyState(
    title: String,
    caption: String? = null,
    modifier: Modifier = Modifier,
    medallion: Boolean = true,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (medallion) LogoMedallion(size = 132.dp, modifier = Modifier.padding(bottom = 18.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
        Ornament(Modifier.padding(top = 6.dp, bottom = 8.dp))
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Column(Modifier.padding(top = 20.dp)) { action() }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStatePreview() {
    GuftuguTheme { EmptyState("No conversations yet", "Invite your family from the admin CLI") }
}
