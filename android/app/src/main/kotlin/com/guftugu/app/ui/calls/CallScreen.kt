package com.guftugu.app.ui.calls

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.guftugu.app.GuftuguApp
import com.guftugu.app.R
import com.guftugu.app.calls.CallState
import com.guftugu.app.core.util.Time
import com.guftugu.app.notifications.Notifier
import com.guftugu.app.protocol.Call
import com.guftugu.app.protocol.CallType
import com.guftugu.app.ui.navigation.IntentBus
import com.guftugu.app.ui.theme.ErrorLight
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.Moonlight
import com.guftugu.app.ui.theme.MoonlightSoft
import com.guftugu.app.ui.theme.NightSky
import com.guftugu.app.ui.theme.Ornament
import com.guftugu.app.ui.theme.goldBorder
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * The in-call screen (DESIGN.md "In-call"): remote video full-bleed (or night gradient +
 * avatar for audio), local preview as a 110dp gold-hairlined card bottom-right, timer at the
 * top, controls (mute · camera · flip · speaker · hang up) that fade after 4 s without a touch
 * during video calls. Ended → outcome for 1.5 s → [onEnded].
 *
 * Back leaves the screen but keeps the call running (the ongoing-call notification brings you back).
 */
@Composable
fun CallScreen(callId: String, onEnded: () -> Unit) {
    val graph = GuftuguApp.graph(LocalContext.current)
    val vm: CallViewModel = viewModel(factory = CallViewModel.factory(graph))
    val state by vm.state.collectAsStateWithLifecycle()
    val peerName by vm.peerName.collectAsStateWithLifecycle()
    val localTrack by vm.localVideoTrack.collectAsStateWithLifecycle()
    val remoteTrack by vm.remoteVideoTrack.collectAsStateWithLifecycle()
    val frontCamera by vm.frontCamera.collectAsStateWithLifecycle()
    val currentOnEnded by rememberUpdatedState(onEnded)
    // onEnded fires at most once per screen instance (Ended → Idle would otherwise pop twice).
    var finished by remember { mutableStateOf(false) }
    val leave = { if (!finished) { finished = true; currentOnEnded() } }

    LaunchedEffect(state) {
        when (val st = state) {
            is CallState.Ended -> { delay(ENDED_LINGER_MS); leave() }
            is CallState.Idle -> leave()
            is CallState.Outgoing -> if (st.call.callId != callId) leave()
            is CallState.Active -> if (st.call.callId != callId) leave()
            is CallState.Incoming -> Unit // NavGraph shows incomingCall/{id} on top
        }
    }

    // "Hang up" on the ongoing-call notification.
    val action by IntentBus.callAction.collectAsStateWithLifecycle()
    LaunchedEffect(action) {
        val (name, id) = action ?: return@LaunchedEffect
        if (id == callId && name == Notifier.ACTION_HANGUP) {
            IntentBus.consumeCallAction()
            vm.hangup()
        }
    }

    // Screen stays on for the whole call.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    BackHandler { leave() }

    // Timer: one String per second, computed outside composition.
    val connectedAt = (state as? CallState.Active)?.connectedAt
    var elapsed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connectedAt) {
        if (connectedAt == null) { elapsed = null; return@LaunchedEffect }
        while (true) {
            elapsed = Time.formatDuration(Time.nowMs() - connectedAt)
            delay(1_000)
        }
    }

    // Controls auto-hide in video calls: any tap reveals them and restarts the 4 s countdown.
    var controlsVisible by remember { mutableStateOf(true) }
    var touchSerial by remember { mutableIntStateOf(0) }
    val autoHide = remoteTrack != null && state is CallState.Active
    LaunchedEffect(autoHide, touchSerial, controlsVisible) {
        if (autoHide && controlsVisible) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }

    CallContent(
        state = state,
        peerName = peerName,
        elapsed = elapsed,
        localTrack = localTrack,
        remoteTrack = remoteTrack,
        eglContext = vm.eglContext,
        mirrorLocal = frontCamera,
        controlsVisible = controlsVisible || !autoHide,
        onTap = { if (autoHide) { controlsVisible = !controlsVisible; touchSerial++ } },
        onMute = { vm.toggleMute(); touchSerial++ },
        onCamera = { vm.toggleCamera(); touchSerial++ },
        onFlip = { vm.switchCamera(); touchSerial++ },
        onSpeaker = { vm.toggleSpeaker(); touchSerial++ },
        onHangup = { vm.hangup() },
    )
}

@Composable
fun CallContent(
    state: CallState,
    peerName: String,
    elapsed: String?,
    localTrack: VideoTrack?,
    remoteTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    mirrorLocal: Boolean,
    controlsVisible: Boolean,
    onTap: () -> Unit,
    onMute: () -> Unit,
    onCamera: () -> Unit,
    onFlip: () -> Unit,
    onSpeaker: () -> Unit,
    onHangup: () -> Unit,
) {
    val call = state.callOrNull
    val video = call?.type == CallType.VIDEO
    val active = state as? CallState.Active
    val ended = state as? CallState.Ended
    val name = peerName.ifBlank { stringResource(R.string.call_unknown_peer) }
    val status: String = when (state) {
        is CallState.Outgoing -> stringResource(R.string.call_ringing)
        is CallState.Active -> elapsed ?: stringResource(R.string.call_connecting)
        is CallState.Ended -> endReasonText(state.reason)
        else -> ""
    }
    val showRemote = remoteTrack != null && ended == null
    val showLocal = localTrack != null && ended == null && (active?.cameraOn ?: video)
    val currentOnTap by rememberUpdatedState(onTap) // pointerInput(Unit) must not capture a stale lambda

    Box(
        Modifier
            .fillMaxSize()
            .background(NightSky)
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnTap() }) },
    ) {
        if (showRemote) {
            VideoRendererView(track = remoteTrack, eglContext = eglContext, modifier = Modifier.fillMaxSize())
        } else {
            AudioBackdrop(name = name, pulsing = active?.connectedAt == null && ended == null)
        }

        // Top: name (over video) + status pill.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 14.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showRemote) {
                Text(name, style = MaterialTheme.typography.headlineSmall, color = Moonlight, maxLines = 1)
                Spacer(Modifier.height(6.dp))
            }
            if (status.isNotEmpty()) CallStatusPill(status)
        }

        // Local preview: 110dp card with a gold hairline, bottom-right above the controls.
        if (showLocal) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 128.dp)
                    .size(width = 110.dp, height = 146.dp)
                    .goldBorder(width = 1.5.dp, shape = LocalPreviewShape)
                    .padding(1.5.dp)
                    .clip(LocalPreviewShape),
            ) {
                VideoPreviewView(
                    track = localTrack,
                    eglContext = eglContext,
                    modifier = Modifier.fillMaxSize(),
                    mirror = mirrorLocal,
                )
            }
        }

        // Controls.
        AnimatedVisibility(
            visible = controlsVisible && ended == null,
            enter = fadeIn(tween(FADE_MS)),
            exit = fadeOut(tween(FADE_MS)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                val muted = active?.muted == true
                CallControlButton(
                    icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = stringResource(if (muted) R.string.call_unmute else R.string.call_mute),
                    onClick = onMute,
                    active = muted,
                    enabled = active != null,
                )
                if (video) {
                    val cameraOn = active?.cameraOn ?: true
                    CallControlButton(
                        icon = if (cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        label = stringResource(if (cameraOn) R.string.call_camera_on else R.string.call_camera_off),
                        onClick = onCamera,
                        active = !cameraOn,
                        enabled = active != null,
                    )
                    CallControlButton(
                        icon = Icons.Filled.Cameraswitch,
                        label = stringResource(R.string.call_flip_camera),
                        onClick = onFlip,
                        enabled = localTrack != null && cameraOn,
                    )
                }
                val speaker = active?.speaker ?: video
                CallControlButton(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    label = stringResource(if (speaker) R.string.call_speaker else R.string.call_earpiece),
                    onClick = onSpeaker,
                    active = speaker,
                    enabled = active != null,
                )
                CallControlButton(
                    icon = Icons.Filled.CallEnd,
                    label = stringResource(R.string.call_hang_up),
                    onClick = onHangup,
                    size = 64.dp,
                    container = ErrorLight,
                    content = Color.White,
                )
            }
        }
    }
}

/** Night gradient + halo + avatar + name for audio calls (and while video is still connecting). */
@Composable
private fun BoxScope.AudioBackdrop(name: String, pulsing: Boolean) {
    NightBackdrop {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
                .padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PulsingGoldAvatar(name = name, size = 120.dp, pulsing = pulsing)
            Spacer(Modifier.height(6.dp))
            Text(name, style = MaterialTheme.typography.displaySmall, color = Moonlight, textAlign = TextAlign.Center, maxLines = 2)
            Ornament(Modifier.padding(top = 8.dp, bottom = 10.dp), width = 140.dp)
            EncryptedBadge()
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelSmall, color = MoonlightSoft)
        }
    }
}

private val LocalPreviewShape = RoundedCornerShape(16.dp)
private const val ENDED_LINGER_MS = 1_500L
private const val CONTROLS_HIDE_MS = 4_000L
private const val FADE_MS = 200

@Preview(showBackground = true)
@Composable
private fun CallPreview() {
    val call = Call(
        callId = "k_preview", convId = "c_1", type = CallType.AUDIO, callerId = "u_1", callerDeviceId = "d_1",
        calleeId = "u_2", calleeDeviceId = "d_2", state = "active", createdAt = 0L,
    )
    GuftuguTheme {
        CallContent(
            state = CallState.Active(call, connectedAt = 0L),
            peerName = "Abbu",
            elapsed = "12:34",
            localTrack = null,
            remoteTrack = null,
            eglContext = null,
            mirrorLocal = true,
            controlsVisible = true,
            onTap = {}, onMute = {}, onCamera = {}, onFlip = {}, onSpeaker = {}, onHangup = {},
        )
    }
}
