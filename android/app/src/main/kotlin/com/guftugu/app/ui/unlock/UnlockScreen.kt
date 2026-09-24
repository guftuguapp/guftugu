package com.guftugu.app.ui.unlock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guftugu.app.R
import com.guftugu.app.core.auth.AuthError
import com.guftugu.app.ui.join.CraftTextButton
import com.guftugu.app.ui.join.GlowMedallion
import com.guftugu.app.ui.join.PasswordField
import com.guftugu.app.ui.join.UiMessage
import com.guftugu.app.ui.join.WarmBanner
import com.guftugu.app.ui.join.findFragmentActivity
import com.guftugu.app.ui.join.graphViewModel
import com.guftugu.app.ui.join.text
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.GoldButton
import com.guftugu.app.ui.theme.GoldDeep
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.Ornament
import com.guftugu.app.ui.theme.ParchmentCard
import com.guftugu.app.ui.theme.RiverbankBackground
import com.guftugu.app.ui.unlock.UnlockViewModel.Phase
import com.guftugu.app.ui.unlock.UnlockViewModel.UiState

/** Biometric prompt → password fallback → offline unlock (PROTOCOL §4). */
@Composable
fun UnlockScreen(onUnlocked: () -> Unit) {
    val vm: UnlockViewModel = graphViewModel { UnlockViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findFragmentActivity()

    LaunchedEffect(Unit) { vm.autoLaunch(activity) }
    LaunchedEffect(state.unlocked) { if (state.unlocked) onUnlocked() }

    UnlockContent(
        state = state,
        onBiometric = { activity?.let(vm::unlockWithBiometric) },
        onOffline = { activity?.let(vm::unlockOffline) },
        onPassword = vm::setPassword,
        onSubmitPassword = vm::unlockWithPassword,
        onUsePassword = vm::usePassword,
        onUseBiometric = vm::useBiometric,
        onDismissMessage = vm::dismissMessage,
        onStartOver = vm::startOver,
        onDevice = vm::unlockWithDevice,
    )
}

/** No fingerprint, no passcode: the phone signs itself in; on trouble offer a retry (or start over). */
@Composable
private fun DeviceCard(state: UiState, onRetry: () -> Unit, onStartOver: () -> Unit) {
    ParchmentCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                state.busy -> {
                    CircularProgressIndicator(color = GoldDeep, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
                    Text(
                        stringResource(R.string.unlock_opening),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                state.deviceFailed -> {
                    Text(stringResource(R.string.unlock_no_fingerprint_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.unlock_no_fingerprint_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                    GoldButton(text = stringResource(R.string.unlock_start_over), onClick = onStartOver, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    GoldButton(text = stringResource(R.string.unlock_try_again), onClick = onRetry, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Stateless body (previewable). */
@Composable
fun UnlockContent(
    state: UiState,
    onBiometric: () -> Unit,
    onOffline: () -> Unit,
    onPassword: (String) -> Unit,
    onSubmitPassword: () -> Unit,
    onUsePassword: () -> Unit,
    onUseBiometric: () -> Unit,
    onDismissMessage: () -> Unit,
    onStartOver: () -> Unit,
    onDevice: () -> Unit = {},
) {
    // Short shake on every failure: ×3 over 300 ms (DESIGN.md "Unlock").
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state.failures) {
        if (state.failures == 0) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(
            0f,
            animationSpec = keyframes {
                durationMillis = 300
                0f at 0; -10f at 50; 10f at 100; -8f at 150; 8f at 200; -4f at 250; 0f at 300
            },
        )
    }

    RiverbankBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            GlowMedallion(size = 220.dp, glow = 32.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                state.serverName ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Ornament(Modifier.padding(top = 4.dp, bottom = 6.dp))
            Text(
                state.displayName?.let { stringResource(R.string.unlock_welcome) + ", " + it } ?: stringResource(R.string.unlock_welcome),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            val shaken = Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) }
            when {
                state.revokedReason != null -> RevokedCard(state, onStartOver)
                state.deviceMode -> DeviceCard(state, onRetry = onDevice, onStartOver = onStartOver)
                state.passwordMode -> PasswordCard(state, shaken, onPassword, onSubmitPassword, onUseBiometric, onOffline, onStartOver)
                else -> FingerprintBlock(state, shaken, onBiometric, onUsePassword, onOffline)
            }

            val message = state.message?.text()
            if (message != null) {
                Spacer(Modifier.height(16.dp))
                WarmBanner(message = message, onDismiss = onDismissMessage)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FingerprintBlock(
    state: UiState,
    shaken: Modifier,
    onBiometric: () -> Unit,
    onUsePassword: () -> Unit,
    onOffline: () -> Unit,
) {
    val prompting = state.phase == Phase.PROMPTING
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FingerprintRing(pulsing = prompting, enabled = !state.busy, modifier = shaken, onClick = {
            if (state.online) onBiometric() else if (state.offlineAvailable) onOffline() else onBiometric()
        })
        Spacer(Modifier.height(14.dp))
        Text(
            when {
                state.phase == Phase.VERIFYING -> stringResource(R.string.unlock_verifying)
                prompting -> stringResource(R.string.unlock_touch)
                else -> stringResource(R.string.unlock_tap_fingerprint)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!state.online) {
            Spacer(Modifier.height(12.dp))
            OfflineHint(state.offlineAvailable, onOffline)
        }
        if (state.hasPassword) {
            Spacer(Modifier.height(8.dp))
            CraftTextButton(text = stringResource(R.string.unlock_use_password), onClick = onUsePassword, enabled = !state.busy)
        }
    }
}

@Composable
private fun PasswordCard(
    state: UiState,
    shaken: Modifier,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onUseBiometric: () -> Unit,
    onOffline: () -> Unit,
    onStartOver: () -> Unit,
) {
    ParchmentCard(shaken.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!state.hasBiometricKey && !state.hasPassword) {
                // Fingerprint key reset (fingerprints changed) and no backup passcode: say what happened.
                Text(
                    stringResource(R.string.unlock_no_fingerprint_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.unlock_no_fingerprint_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
            } else if (!state.hasBiometricKey) {
                Text(
                    stringResource(R.string.unlock_password_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            PasswordField(
                value = state.password,
                onValueChange = onPassword,
                label = stringResource(R.string.unlock_password),
                enabled = !state.busy,
                imeAction = ImeAction.Go,
                onImeAction = { if (state.canSubmitPassword) onSubmit() },
            )
            Spacer(Modifier.height(16.dp))
            if (state.phase == Phase.VERIFYING) {
                Row(Modifier.fillMaxWidth().height(50.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = GoldDeep, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                    Text(
                        stringResource(R.string.unlock_verifying),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            } else {
                GoldButton(text = stringResource(R.string.unlock_button), onClick = onSubmit, enabled = state.canSubmitPassword, modifier = Modifier.fillMaxWidth())
            }
            if (!state.online) {
                Spacer(Modifier.height(12.dp))
                OfflineHint(state.offlineAvailable, onOffline, passwordMode = true)
            }
            if (state.hasBiometricKey) {
                CraftTextButton(text = stringResource(R.string.unlock_use_fingerprint), onClick = onUseBiometric, enabled = !state.busy, modifier = Modifier.padding(top = 4.dp))
            } else if (!state.hasPassword) {
                CraftTextButton(text = stringResource(R.string.unlock_start_over), onClick = onStartOver, enabled = !state.busy, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun OfflineHint(offlineAvailable: Boolean, onOffline: () -> Unit, passwordMode: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(
                stringResource(if (offlineAvailable) R.string.unlock_offline_hint else R.string.unlock_offline_password_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        if (offlineAvailable) {
            Spacer(Modifier.height(8.dp))
            if (passwordMode) {
                CraftTextButton(text = stringResource(R.string.unlock_offline), onClick = onOffline)
            } else {
                GoldButton(text = stringResource(R.string.unlock_offline), onClick = onOffline, modifier = Modifier.fillMaxWidth(0.7f))
            }
        }
    }
}

@Composable
private fun RevokedCard(state: UiState, onStartOver: () -> Unit) {
    ParchmentCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.unlock_revoked_title, state.serverName ?: stringResource(R.string.app_name)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.unlock_revoked_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            if (state.phase == Phase.RESETTING) {
                CircularProgressIndicator(color = GoldDeep, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
            } else {
                GoldButton(text = stringResource(R.string.unlock_start_over), onClick = onStartOver, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Gold-ringed fingerprint glyph. While the prompt is up it breathes: one infinite transition,
 * scale 1.0 → 1.06 over 1.6 s, applied in `graphicsLayer` so nothing recomposes per frame.
 */
@Composable
private fun FingerprintRing(pulsing: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Only animate while the prompt is up: an idle ring costs zero frames.
    val scale: State<Float> = if (pulsing) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1600), RepeatMode.Reverse),
            label = "scale",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier
            .size(104.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .border(3.dp, Brushes.gold, CircleShape)
            .padding(6.dp)
            .background(surface, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Fingerprint,
            contentDescription = stringResource(R.string.unlock_tap_fingerprint),
            tint = if (enabled) GoldDeep else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(52.dp),
        )
    }
}

// ---------- previews ----------

@Preview(showBackground = true)
@Composable
private fun UnlockPreview() {
    GuftuguTheme {
        UnlockContent(UiState(serverName = "Our family", displayName = "Ammi", hasBiometricKey = true, phase = Phase.PROMPTING), {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun UnlockPasswordPreview() {
    GuftuguTheme(darkTheme = true) {
        UnlockContent(
            UiState(serverName = "Our family", hasBiometricKey = true, passwordMode = true, online = false, hasStoredSession = true, message = UiMessage.Auth(AuthError.BadCredentials)),
            {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UnlockRevokedPreview() {
    GuftuguTheme {
        UnlockContent(UiState(serverName = "Our family", revokedReason = "device_revoked"), {}, {}, {}, {}, {}, {}, {}, {})
    }
}
