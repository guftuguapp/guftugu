package com.guftugu.app.ui.passcode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guftugu.app.R
import com.guftugu.app.core.auth.AuthError
import com.guftugu.app.core.auth.PasscodePolicy
import com.guftugu.app.core.auth.PasscodePolicy.Prompt
import com.guftugu.app.core.auth.PasswordStrength
import com.guftugu.app.data.repo.AuthExtras
import com.guftugu.app.di.AppGraph
import com.guftugu.app.ui.join.CraftTextButton
import com.guftugu.app.ui.join.PasswordField
import com.guftugu.app.ui.join.UiMessage
import com.guftugu.app.ui.join.WarmBanner
import com.guftugu.app.ui.join.text
import com.guftugu.app.ui.theme.CraftHeading
import com.guftugu.app.ui.theme.GoldButton
import com.guftugu.app.ui.theme.GoldDeep
import com.guftugu.app.ui.theme.LogoMedallion
import com.guftugu.app.ui.theme.ParchmentCard
import kotlinx.coroutines.launch

/**
 * WhatsApp-style passcode moments over the unlocked app (PasscodePolicy):
 * create one about a week after first use ("Later" allowed for a while), then re-enter it every
 * 30 days so it isn't forgotten. A full-screen card; the app underneath is not usable meanwhile.
 */
@Composable
fun PasscodeGate(graph: AppGraph) {
    val extras = graph.authRepository as? AuthExtras ?: return
    val cfg by graph.serverConfig.config.collectAsStateWithLifecycle(initialValue = graph.serverConfig.current.value)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { now = System.currentTimeMillis() }
    // A passcode that predates this clock (or was set on another phone): start counting now.
    LaunchedEffect(cfg.hasPassword, cfg.lastPasscodeAt) {
        if (cfg.hasPassword && cfg.lastPasscodeAt == 0L) runCatching { graph.serverConfig.setLastPasscodeAt(System.currentTimeMillis()) }
    }
    val prompt = PasscodePolicy.decide(now, cfg.hasPassword, cfg.firstActivityAt, cfg.lastPasscodeAt, cfg.passcodeSnoozedUntil)
    if (prompt == Prompt.None) return

    val scope = rememberCoroutineScope()
    var pass by remember(prompt) { mutableStateOf("") }
    var confirm by remember(prompt) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember(prompt) { mutableStateOf<UiMessage?>(null) }
    var forgot by remember(prompt) { mutableStateOf(false) }
    val creating = prompt is Prompt.Create

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC0B1B33))
            .pointerInput(Unit) { /* swallow touches: the app underneath waits */ },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ParchmentCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LogoMedallion(size = 84.dp)
                    Spacer(Modifier.height(10.dp))
                    CraftHeading(
                        title = stringResource(if (creating) R.string.passcode_create_title else R.string.passcode_reenter_title),
                        caption = stringResource(if (creating) R.string.passcode_create_body else R.string.passcode_reenter_body),
                    )
                    Spacer(Modifier.height(16.dp))
                    PasswordField(
                        value = pass,
                        onValueChange = { pass = it; message = null },
                        label = stringResource(R.string.unlock_password),
                        enabled = !busy,
                        isError = creating && pass.isNotEmpty() && !PasswordStrength.meetsMinimum(pass),
                        supportingText = if (creating && pass.isNotEmpty() && !PasswordStrength.meetsMinimum(pass)) stringResource(R.string.enroll_password_hint) else null,
                    )
                    if (creating) {
                        Spacer(Modifier.height(10.dp))
                        PasswordField(
                            value = confirm,
                            onValueChange = { confirm = it; message = null },
                            label = stringResource(R.string.enroll_password_confirm),
                            enabled = !busy,
                            isError = confirm.isNotEmpty() && confirm != pass,
                            supportingText = if (confirm.isNotEmpty() && confirm != pass) stringResource(R.string.enroll_password_mismatch) else null,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    val ready = if (creating) PasswordStrength.meetsMinimum(pass) && confirm == pass else pass.isNotEmpty()
                    if (busy) {
                        CircularProgressIndicator(color = GoldDeep, strokeWidth = 2.5.dp)
                    } else {
                        GoldButton(
                            text = stringResource(if (creating) R.string.passcode_save else R.string.passcode_continue),
                            enabled = ready,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy = true
                                scope.launch {
                                    val result = if (creating) extras.setBackupPassword(null, pass) else graph.authRepository.unlockWithPassword(pass)
                                    busy = false
                                    result.onFailure { e ->
                                        message = UiMessage.Auth(e as? AuthError ?: AuthError.Local(e.message ?: "?", e))
                                        if (!creating) pass = ""
                                    }
                                }
                            },
                        )
                    }
                    if (creating && (prompt as Prompt.Create).canSnooze) {
                        CraftTextButton(
                            text = stringResource(R.string.passcode_later),
                            enabled = !busy,
                            onClick = { scope.launch { runCatching { graph.serverConfig.setPasscodeSnoozedUntil(System.currentTimeMillis() + PasscodePolicy.SNOOZE_MS) } } },
                        )
                    }
                    if (!creating) {
                        CraftTextButton(text = stringResource(R.string.passcode_forgot), onClick = { forgot = !forgot })
                        if (forgot) {
                            Text(
                                stringResource(R.string.passcode_forgot_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    message?.text()?.let {
                        Spacer(Modifier.height(10.dp))
                        WarmBanner(message = it, onDismiss = { message = null })
                    }
                }
            }
        }
    }
}
