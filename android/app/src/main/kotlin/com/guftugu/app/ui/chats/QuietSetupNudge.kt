package com.guftugu.app.ui.chats

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guftugu.app.GuftuguApp
import com.guftugu.app.R
import com.guftugu.app.service.QuietConnection
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.GoldDeep
import com.guftugu.app.ui.theme.GoldMist
import com.guftugu.app.ui.theme.GuftuguTheme
import kotlinx.coroutines.launch

/**
 * One-time card at the top of the chat list: hide the "Guftugu is connected" notice while keeping
 * message and call notifications (the owner's wish). Step 1 opens Guftugu's "Background connection"
 * notification category; step 2 opens this phone's keep-running page with a line saying what to switch.
 * Gone for good once both were opened or "Not now" was tapped; Settings keeps both rows.
 */
@Composable
fun QuietSetupNudge(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = remember(context) { GuftuguApp.graph(context) }
    val cfg by graph.serverConfig.config.collectAsStateWithLifecycle(initialValue = graph.serverConfig.current.value)
    var noticeHidden by remember { mutableStateOf(QuietConnection.isNoticeHidden(context)) }
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { noticeHidden = QuietConnection.isNoticeHidden(context) }
    if (!cfg.isEnrolled || !cfg.backgroundConnectionEnabled || cfg.quietSetupDone || (noticeHidden && cfg.keepRunningVisited)) return

    val hint = stringResource(QuietConnection.keepRunningHint())
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(GoldMist.copy(alpha = if (GuftuguTheme.craft.isDark) 0.16f else 0.75f))
            .border(1.2.dp, Brushes.goldSoft, shape)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(40.dp).border(1.5.dp, Brushes.gold, CircleShape).padding(3.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.NotificationsOff, contentDescription = null, tint = GoldDeep, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(stringResource(R.string.quiet_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(R.string.quiet_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            TextButton(enabled = !noticeHidden, onClick = { QuietConnection.open(context, QuietConnection.noticeSettingsIntent(context)) }) {
                Text(
                    stringResource(if (noticeHidden) R.string.quiet_step_hidden else R.string.quiet_step_hide),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (noticeHidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = {
                Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
                QuietConnection.open(context, QuietConnection.keepRunningIntent(context))
                scope.launch { runCatching { graph.serverConfig.setKeepRunningVisited() } }
            }) {
                Text(stringResource(R.string.quiet_step_keep), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, end = 8.dp))
            TextButton(onClick = { scope.launch { runCatching { graph.serverConfig.setQuietSetupDone(true) } } }) {
                Text(stringResource(R.string.nudge_not_now), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
