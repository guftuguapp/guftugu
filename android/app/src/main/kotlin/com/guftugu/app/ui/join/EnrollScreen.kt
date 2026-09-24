package com.guftugu.app.ui.join

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guftugu.app.R
import com.guftugu.app.core.auth.BiometricGate
import com.guftugu.app.ui.join.EnrollViewModel.UiState
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.CraftHeading
import com.guftugu.app.ui.theme.GoldMist
import com.guftugu.app.ui.theme.goldBorder
import com.guftugu.app.ui.theme.Gold
import com.guftugu.app.ui.theme.GoldButton
import com.guftugu.app.ui.theme.GoldDeep
import com.guftugu.app.ui.theme.Grass
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.Meadow
import com.guftugu.app.ui.theme.ParchmentCard
import com.guftugu.app.ui.theme.RiverbankBackground

/** Name + fingerprint (password only as an optional backup) → POST /enroll (PROTOCOL §3). */
@Composable
fun EnrollScreen(apiUrl: String, code: String, onBack: () -> Unit, onEnrolled: () -> Unit) {
    val vm: EnrollViewModel = graphViewModel(key = "enroll:$apiUrl:$code") { EnrollViewModel(it, apiUrl, code) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findFragmentActivity()

    LaunchedEffect(state.done) { if (state.done) onEnrolled() }
    // Back from the phone's fingerprint settings: re-check.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshBiometrics() }

    EnrollContent(
        state = state,
        onBack = onBack,
        onName = vm::setName,
        onDeviceName = vm::setDeviceName,
        onOpenFingerprintSettings = { BiometricGate.openFingerprintSettings(context) },
        onSubmit = { vm.submit(activity) },
        onDismissMessage = vm::dismissMessage,
    )
}

/** Stateless body (previewable). */
@Composable
fun EnrollContent(
    state: UiState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onDeviceName: (String) -> Unit,
    onOpenFingerprintSettings: () -> Unit,
    onSubmit: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    RiverbankBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = !state.busy) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back), tint = MaterialTheme.colorScheme.onBackground)
                }
            }
            GlowMedallion(size = 110.dp, glow = 18.dp)
            Spacer(Modifier.height(10.dp))
            CraftHeading(
                title = stringResource(R.string.title_enroll),
                caption = state.serverName?.let { stringResource(R.string.enroll_joining, it) },
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(20.dp))

            ParchmentCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
                    CraftTextField(
                        value = state.name,
                        onValueChange = onName,
                        label = stringResource(R.string.enroll_name),
                        enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(10.dp))
                    CraftTextField(
                        value = state.deviceName,
                        onValueChange = onDeviceName,
                        label = stringResource(R.string.enroll_device_name),
                        enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done,
                        ),
                    )
                    Spacer(Modifier.height(16.dp))
                    SecuritySection(state = state, onOpenFingerprintSettings = onOpenFingerprintSettings)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = GoldDeep, modifier = Modifier.size(16.dp))
                        Text(
                            stringResource(R.string.enroll_keys_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    if (state.busy) {
                        Row(Modifier.fillMaxWidth().height(50.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = GoldDeep, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                            Text(
                                stringResource(R.string.enroll_working),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    } else {
                        GoldButton(
                            text = stringResource(if (state.fingerprintMode) R.string.enroll_join_fingerprint else R.string.enroll_join),
                            onClick = onSubmit,
                            enabled = state.canSubmit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val message = state.message?.text()
                    if (message != null) {
                        Spacer(Modifier.height(14.dp))
                        WarmBanner(message = message, onDismiss = onDismissMessage)
                    }
                }
            }
        }
    }
}

/**
 * Fingerprint when the phone has one registered; otherwise just join — no passcode now (the app
 * asks the person to create one about a week later, WhatsApp-style). A phone with a sensor but no
 * fingerprint gets an optional hint with a button into its settings; nothing is required.
 */
@Composable
private fun SecuritySection(state: UiState, onOpenFingerprintSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        when {
            state.fingerprintMode -> FingerprintCard(
                title = stringResource(R.string.enroll_fp_title),
                body = stringResource(R.string.enroll_fp_body),
                ready = true,
            )
            state.biometrics == BiometricGate.Availability.NONE_ENROLLED && !state.fingerprintBroken -> {
                FingerprintCard(
                    title = stringResource(R.string.enroll_fp_optional_title),
                    body = stringResource(R.string.enroll_fp_optional_body),
                    ready = false,
                )
                CraftTextButton(
                    text = stringResource(R.string.enroll_fp_open_settings),
                    onClick = onOpenFingerprintSettings,
                    enabled = !state.busy,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            else -> Unit
        }
        Text(
            stringResource(R.string.enroll_passcode_later),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Gold-ringed fingerprint medallion with a title and explanation. */
@Composable
private fun FingerprintCard(title: String, body: String, ready: Boolean) {
    val craft = GuftuguTheme.craft
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (ready) craft.bubbleMine.copy(alpha = 0.55f) else GoldMist.copy(alpha = if (craft.isDark) 0.18f else 0.7f))
            .goldBorder(shape = MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .border(2.dp, Brushes.gold, CircleShape)
                .padding(4.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ready) Icons.Rounded.Fingerprint else Icons.Rounded.Fingerprint,
                contentDescription = null,
                tint = if (ready) GoldDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
                if (ready) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Grass, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                }
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ---------- previews ----------

@Preview(showBackground = true)
@Composable
private fun EnrollPreview() {
    GuftuguTheme {
        EnrollContent(
            UiState(serverName = "Our family", name = "Ammi", biometrics = BiometricGate.Availability.AVAILABLE, deviceName = "Google Pixel 8"),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EnrollDarkPreview() {
    GuftuguTheme(darkTheme = true) {
        EnrollContent(
            UiState(serverName = "Our family", biometrics = BiometricGate.Availability.NONE_ENROLLED, deviceName = "Huawei AQM-LX1", message = UiMessage.Res(R.string.auth_err_invite_expired)),
            {}, {}, {}, {}, {}, {},
        )
    }
}
