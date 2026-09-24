package com.guftugu.app.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guftugu.app.R
import com.guftugu.app.ui.common.AvatarImage
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.CraftListRow
import com.guftugu.app.ui.theme.Forest
import com.guftugu.app.ui.theme.GoldLight
import com.guftugu.app.ui.theme.GuftuguTheme

private val TagShape = RoundedCornerShape(50)

/** One person: avatar, name, optional Admin tag; [selectable] draws a gold check ring when selected. */
@Composable
fun ContactRow(
    contact: ContactUi,
    resolveAvatar: (suspend (String) -> String?)?,
    onClick: () -> Unit,
    selectable: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        CraftListRow {
            AvatarImage(name = contact.name, avatarKey = contact.avatarKey, resolveUrl = resolveAvatar, size = 46.dp)
            Text(
                contact.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 14.dp, end = 8.dp),
            )
            if (contact.isAdmin) {
                Text(
                    stringResource(R.string.contacts_admin),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = GoldLight,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(TagShape)
                        .background(Forest)
                        .border(1.dp, Brushes.goldSoft, TagShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (selectable) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (contact.selected) MaterialTheme.colorScheme.secondary else Color.Transparent)
                        .border(1.5.dp, if (contact.selected) Brushes.goldSoft else androidx.compose.ui.graphics.SolidColor(GuftuguTheme.craft.hairline), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (contact.selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 76.dp, end = 16.dp), thickness = 1.dp, color = GuftuguTheme.craft.hairline)
    }
}
