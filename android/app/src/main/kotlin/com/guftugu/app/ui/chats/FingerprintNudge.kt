package com.guftugu.app.ui.chats

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
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.guftugu.app.core.auth.BiometricGate
import com.guftugu.app.data.repo.AuthExtras
import com.guftugu.app.ui.join.findFragmentActivity
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.GoldDeep
import com.guftugu.app.ui.theme.GoldMist
import com.guftugu.app.ui.theme.GuftuguTheme
import kotlinx.coroutines.launch

/**
 * "Unlock with your fingerprint" card at the top of the chat list, shown when this phone has a
 * sensor but Guftugu isn't using it yet (enrolled with a passcode, or the fingerprint key was reset).
 * AVAILABLE → one tap turns it on; NONE_ENROLLED → opens the phone's fingerprint settings.
 */
@Composable
fun FingerprintNudge(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = remember(context) { GuftuguApp.graph(context) }
    val extras = graph.authRepository as? AuthExtras ?: return
    var availability by remember { mutableStateOf(BiometricGate.availability(context)) }
    var hasKey by remember { mutableStateOf(extras.hasBiometricKey()) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hiddenUntil by graph.serverConfig.config.collectAsStateWithLifecycle(initialValue = graph.serverConfig.current.value)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        availability = BiometricGate.availability(context)
        hasKey = extras.hasBiometricKey()
    }
    // Only when it's one tap away (a fingerprint is already registered on the phone), and never
    // again once dismissed — Settings keeps the switch for later.
    if (dismissed || hasKey || availability != BiometricGate.Availability.AVAILABLE) return
    if (System.currentTimeMillis() < hiddenUntil.fingerprintNudgeHiddenUntil) return

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
            Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = GoldDeep, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(stringResource(R.string.nudge_fp_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(
                    when {
                        failed -> R.string.settings_fingerprint_failed
                        availability == BiometricGate.Availability.AVAILABLE -> R.string.nudge_fp_body
                        else -> R.string.nudge_fp_body_add
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        if (availability == BiometricGate.Availability.AVAILABLE) {
                            val activity = context.findFragmentActivity() ?: return@TextButton
                            busy = true
                            failed = false
                            scope.launch {
                                val r = extras.enableBiometric(activity)
                                busy = false
                                hasKey = extras.hasBiometricKey()
                                failed = r.isFailure && r.exceptionOrNull() !is com.guftugu.app.core.auth.AuthError.Cancelled
                            }
                        } else {
                            BiometricGate.openFingerprintSettings(context)
                        }
                    },
                ) {
                    Text(
                        stringResource(if (availability == BiometricGate.Availability.AVAILABLE) R.string.nudge_fp_turn_on else R.string.nudge_open_settings),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = {
                    dismissed = true
                    scope.launch { runCatching { graph.serverConfig.setFingerprintNudgeHiddenUntil(Long.MAX_VALUE) } }
                }) {
                    Text(stringResource(R.string.nudge_not_now), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
