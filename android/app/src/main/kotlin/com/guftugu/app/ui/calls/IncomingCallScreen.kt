package com.guftugu.app.ui.calls

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.guftugu.app.GuftuguApp
import com.guftugu.app.R
import com.guftugu.app.calls.CallState
import com.guftugu.app.notifications.Notifier
import com.guftugu.app.protocol.Call
import com.guftugu.app.protocol.CallType
import com.guftugu.app.protocol.User
import com.guftugu.app.ui.navigation.IntentBus
import com.guftugu.app.ui.theme.ErrorLight
import com.guftugu.app.ui.theme.Gold
import com.guftugu.app.ui.theme.Grass
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.Moonlight
import com.guftugu.app.ui.theme.MoonlightSoft
import com.guftugu.app.ui.theme.Ornament
import kotlinx.coroutines.delay

/**
 * Full-screen ringing UI (DESIGN.md "Incoming call"): night gradient, pulsing gold-ringed
 * avatar, caller name in the display serif, green answer / red decline.
 *
 * Reacts to the manager's state: Active → [onAnswered], Ended (missed, cancelled,
 * answered elsewhere) → short outcome, then [onDismissed]. Notification actions delivered via
 * [IntentBus.callAction] (Answer / Decline) are consumed here, so answering from the lock
 * screen goes through the same permission check as the on-screen button.
 */
@Composable
fun IncomingCallScreen(callId: String, onAnswered: () -> Unit, onDismissed: () -> Unit) {
    val graph = GuftuguApp.graph(LocalContext.current)
    val vm: CallViewModel = viewModel(factory = CallViewModel.factory(graph))
    val state by vm.state.collectAsStateWithLifecycle()
    val peerName by vm.peerName.collectAsStateWithLifecycle()
    val currentOnAnswered by rememberUpdatedState(onAnswered)
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    // Each navigation callback fires at most once per screen instance (Ended → Idle would otherwise pop twice).
    var finished by remember { mutableStateOf(false) }
    val leave: (() -> Unit) -> Unit = { cb -> if (!finished) { finished = true; cb() } }

    var micDenied by remember { mutableStateOf(false) }
    val requestPermissions = rememberCallPermissionRequester { mic, _ ->
        if (mic) vm.answer() else micDenied = true
    }
    val video = (state as? CallState.Incoming)?.call?.type == CallType.VIDEO

    LaunchedEffect(state) {
        when (val st = state) {
            is CallState.Active -> if (st.call.callId == callId) leave(currentOnAnswered) else leave(currentOnDismissed)
            is CallState.Ended -> { delay(ENDED_LINGER_MS); leave(currentOnDismissed) }
            is CallState.Idle -> leave(currentOnDismissed)
            is CallState.Incoming -> if (st.call.callId != callId) leave(currentOnDismissed)
            is CallState.Outgoing -> leave(currentOnDismissed)
        }
    }

    // Answer / Decline tapped on the notification (routed through MainActivity → IntentBus).
    val action by IntentBus.callAction.collectAsStateWithLifecycle()
    LaunchedEffect(action, state) {
        val (name, id) = action ?: return@LaunchedEffect
        if (id != callId || state !is CallState.Incoming) return@LaunchedEffect
        when (name) {
            Notifier.ACTION_ANSWER -> { IntentBus.consumeCallAction(); requestPermissions(video) }
            Notifier.ACTION_DECLINE -> { IntentBus.consumeCallAction(); vm.reject() }
        }
    }

    // Back does nothing while ringing (no accidental declines); the buttons decide.
    BackHandler { }

    IncomingCallContent(
        state = state,
        peerName = peerName,
        micDenied = micDenied,
        onAnswer = { requestPermissions(video) },
        onDecline = { vm.reject() },
    )
}

@Composable
fun IncomingCallContent(
    state: CallState,
    peerName: String,
    micDenied: Boolean,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    val call = state.callOrNull
    val video = call?.type == CallType.VIDEO
    val ended = state as? CallState.Ended
    val context = LocalContext.current

    NightBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            Text(
                stringResource(if (video) R.string.call_incoming_video else R.string.call_incoming_audio),
                style = MaterialTheme.typography.labelLarge,
                color = Gold,
            )
            Spacer(Modifier.height(20.dp))
            PulsingGoldAvatar(name = peerName.ifBlank { "?" }, size = 120.dp, pulsing = ended == null)
            Spacer(Modifier.height(8.dp))
            Text(
                peerName.ifBlank { stringResource(R.string.call_unknown_peer) },
                style = MaterialTheme.typography.displaySmall,
                color = Moonlight,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Ornament(Modifier.padding(top = 8.dp, bottom = 10.dp), width = 140.dp)
            Text(
                if (ended != null) endReasonText(ended.reason) else stringResource(if (video) R.string.call_video_caption else R.string.call_audio_caption),
                style = MaterialTheme.typography.bodyMedium,
                color = MoonlightSoft,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            EncryptedBadge()

            Spacer(Modifier.weight(1f))

            if (micDenied) {
                NightBanner(
                    text = stringResource(R.string.call_permission_denied),
                    modifier = Modifier.fillMaxWidth(),
                    action = {
                        TextButton(onClick = { runCatching { context.startActivity(CallPermissions.appSettingsIntent(context)) } }) {
                            Text(stringResource(R.string.call_permission_settings), color = Gold)
                        }
                    },
                )
                Spacer(Modifier.height(20.dp))
            } else if (video) {
                Text(
                    stringResource(R.string.call_permission_rationale_video),
                    style = MaterialTheme.typography.bodySmall,
                    color = MoonlightSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(20.dp))
            }

            Row(
                Modifier.fillMaxWidth().padding(bottom = 40.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                CallControlButton(
                    icon = Icons.Filled.CallEnd,
                    label = stringResource(R.string.call_decline),
                    onClick = onDecline,
                    size = 72.dp,
                    container = ErrorLight,
                    content = Color.White,
                    enabled = ended == null,
                )
                CallControlButton(
                    icon = Icons.Filled.Call,
                    label = stringResource(R.string.call_answer),
                    onClick = onAnswer,
                    size = 72.dp,
                    container = Grass,
                    content = Color.White,
                    enabled = ended == null,
                )
            }
        }
    }
}

private const val ENDED_LINGER_MS = 1_500L

@Preview(showBackground = true)
@Composable
private fun IncomingCallPreview() {
    val call = Call(
        callId = "k_preview", convId = "c_1", type = CallType.VIDEO, callerId = "u_2", callerDeviceId = "d_2",
        calleeId = "u_1", state = "ringing", createdAt = 0L,
    )
    val caller = User(userId = "u_2", displayName = "Ammi Jaan", role = "member", status = "active", createdAt = 0L)
    GuftuguTheme {
        IncomingCallContent(
            state = CallState.Incoming(call, caller),
            peerName = caller.displayName,
            micDenied = false,
            onAnswer = {},
            onDecline = {},
        )
    }
}
