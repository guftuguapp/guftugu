package com.guftugu.app.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.guftugu.app.R
import com.guftugu.app.core.util.Time
import com.guftugu.app.data.db.GuftuguDb
import com.guftugu.app.data.prefs.ServerConfigStore
import com.guftugu.app.data.repo.CallRepository
import com.guftugu.app.data.ws.RealtimeClient
import com.guftugu.app.e2ee.ConversationKeyManager
import com.guftugu.app.notifications.Notifier
import com.guftugu.app.protocol.Call
import com.guftugu.app.protocol.CallType
import com.guftugu.app.protocol.EndReason
import com.guftugu.app.protocol.ServerEvent
import com.guftugu.app.protocol.Signal
import com.guftugu.app.protocol.User
import com.guftugu.app.service.CallService
import com.guftugu.app.ui.navigation.IntentBus
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * 1:1 WebRTC calls (PROTOCOL §11–§12).
 *
 * Structure
 * - [CallReducer] owns the state transitions (pure, unit-tested); this class runs the side
 *   effects of each transition (REST, signalling, ringer, audio route, foreground service,
 *   notifications, WebRTC session).
 * - Every mutation goes through [dispatch] on a single-threaded dispatcher, so WebRTC
 *   callbacks, WebSocket events, timers and UI calls never race.
 * - Signalling is end-to-end encrypted through [CallSignaling]; the server relays opaque envelopes.
 *
 * Flow (caller):  startCall → POST /calls → Outgoing (PeerConnection + local media ready, 45 s timer)
 *                 → `call.answered` → Active → offer → (answer, ICE…) → ICE connected → timer runs
 * Flow (callee):  `call.invite` → Incoming (ringer, full-screen notification if in background)
 *                 → answer() → POST /answer → Active → offer arrives → answer → ICE…
 *
 * Nothing WebRTC is created before the first call (DESIGN.md perf rule 6); the factory and the
 * EGL context are then kept for reuse ([PeerConnectionFactoryProvider]).
 */
class CallManagerImpl(
    context: Context,
    private val repo: CallRepository,
    private val realtime: RealtimeClient,
    keyManager: ConversationKeyManager,
    private val serverConfig: ServerConfigStore,
    private val db: GuftuguDb,
    private val notifier: Notifier,
    appScope: CoroutineScope,
) : CallManager {

    private val appContext = context.applicationContext
    private val signaling = CallSignaling(keyManager, repo)

    /** All state transitions + WebRTC session mutations run here, one at a time. */
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob(appScope.coroutineContext[Job]) + dispatcher)

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    override val state: StateFlow<CallState> = _state.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    override val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    override val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _frontCamera = MutableStateFlow(true)
    /** Whether the local preview should be mirrored (front camera). Not part of the interface; the UI reads it when present. */
    val frontCamera: StateFlow<Boolean> = _frontCamera.asStateFlow()

    private val audioRouter by lazy { AudioRouter(appContext) }
    private val ringer by lazy { Ringer(appContext) }

    private var session: Session? = null
    private var ringJob: Job? = null
    private var lingerJob: Job? = null
    private var serviceRunning = false

    /** Signals that arrived for a call we are still answering (POST /answer in flight). Replayed on Active. */
    private val earlySignals = ArrayList<ServerEvent.CallSignal>()

    init {
        AppVisibility.install(appContext)
        scope.launch { realtime.events.collect { onServerEvent(it) } }
    }

    // ---------- CallManager ----------

    override suspend fun startCall(convId: String, video: Boolean) {
        val busy = withContext(dispatcher) { CallReducer.isBusy(_state.value) }
        if (busy) return
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            dispatch(CallEvent.StartFailed(CallReducer.REASON_NO_PERMISSION))
            return
        }
        if (!signaling.hasKey(convId)) {
            signaling.ensureKeys(convId)
            if (!signaling.hasKey(convId)) {
                dispatch(CallEvent.StartFailed(CallReducer.REASON_NO_KEY))
                return
            }
        }
        val call = try {
            repo.start(convId, video)
        } catch (e: Exception) {
            dispatch(CallEvent.StartFailed(EndReason.FAILED))
            return
        }
        dispatch(CallEvent.Started(call))
    }

    override suspend fun answer() {
        val incoming = withContext(dispatcher) { _state.value as? CallState.Incoming } ?: return
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            endLocally(CallReducer.REASON_NO_PERMISSION, report = true)
            return
        }
        ringer.stop()
        val answered = try {
            repo.answer(incoming.call.callId)
        } catch (e: Exception) {
            endLocally(EndReason.FAILED, report = false)
            return
        }
        dispatch(CallEvent.Answered(answered, cameraAvailable = hasPermission(Manifest.permission.CAMERA)))
    }

    override suspend fun reject() {
        val st = withContext(dispatcher) { _state.value }
        if (st !is CallState.Incoming) return
        endLocally(EndReason.REJECTED, report = true)
    }

    override suspend fun hangup() {
        val st = withContext(dispatcher) { _state.value }
        when (st) {
            is CallState.Incoming -> endLocally(EndReason.REJECTED, report = true)
            is CallState.Outgoing -> endLocally(EndReason.CANCELLED, report = true)
            is CallState.Active -> endLocally(EndReason.HANGUP, report = true)
            else -> Unit
        }
    }

    override fun toggleMute() {
        scope.launch {
            val before = _state.value as? CallState.Active ?: return@launch
            dispatch(CallEvent.ToggleMute)
            val muted = !before.muted
            session?.setMuted(muted)
        }
    }

    override fun toggleCamera() {
        scope.launch {
            val before = _state.value as? CallState.Active ?: return@launch
            if (before.call.type != CallType.VIDEO) return@launch
            if (!before.cameraOn && !hasPermission(Manifest.permission.CAMERA)) return@launch
            dispatch(CallEvent.ToggleCamera)
            session?.setCameraOn(!before.cameraOn)
        }
    }

    override fun toggleSpeaker() {
        scope.launch {
            val before = _state.value as? CallState.Active ?: return@launch
            dispatch(CallEvent.ToggleSpeaker)
            audioRouter.setSpeaker(!before.speaker)
        }
    }

    override fun switchCamera() {
        scope.launch { session?.switchCamera() }
    }

    // ---------- server events ----------

    private suspend fun onServerEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.CallInvite -> onInvite(event.call, event.caller)
            is ServerEvent.CallAnswered -> {
                if (CallReducer.acceptsAnswered(_state.value, event.call.callId) && event.call.calleeDeviceId != null) {
                    dispatch(CallEvent.Answered(event.call, cameraAvailable = hasPermission(Manifest.permission.CAMERA)))
                }
            }
            is ServerEvent.CallEnded -> {
                earlySignals.removeAll { it.callId == event.call.callId }
                dispatch(CallEvent.RemoteEnded(event.call.callId, event.reason))
            }
            is ServerEvent.CallSignal -> onSignal(event)
            else -> Unit
        }
    }

    private suspend fun onInvite(call: Call, caller: User) {
        if (CallReducer.isBusy(_state.value)) return // another device of ours may still answer; the server handles it
        if (call.state != com.guftugu.app.protocol.CallState.RINGING) return
        dispatch(CallEvent.InviteReceived(call, caller))
    }

    private suspend fun onSignal(event: ServerEvent.CallSignal) {
        val st = _state.value
        if (st is CallState.Incoming && st.call.callId == event.callId) {
            // Offer racing our POST /answer response: keep it until we are Active.
            if (earlySignals.size < MAX_EARLY_SIGNALS) earlySignals += event
            return
        }
        if (!CallReducer.acceptsSignal(st, event.callId)) return
        val s = session ?: return
        if (s.call.callId != event.callId) return
        val signal = signaling.receive(s.call.convId, s.call.callId, event.envelope) ?: return
        try {
            s.handle(signal)
        } catch (e: Exception) {
            endLocally(EndReason.FAILED, report = true)
        }
    }

    // ---------- state machine plumbing ----------

    private suspend fun dispatch(event: CallEvent) = withContext(dispatcher) {
        val old = _state.value
        val new = CallReducer.reduce(old, event)
        if (new === old) return@withContext
        _state.value = new
        try {
            onTransition(old, new)
        } catch (e: Exception) {
            // A failing side effect (camera, factory, audio) must not leave the machine stuck.
            if (new !is CallState.Ended && new !is CallState.Idle) {
                val ended = CallReducer.reduce(new, CallEvent.LocalEnded(EndReason.FAILED))
                _state.value = ended
                report(new, EndReason.FAILED)
                onTransition(new, ended)
            }
        }
    }

    /** Must run on [dispatcher]. */
    private suspend fun onTransition(old: CallState, new: CallState) {
        when (new) {
            is CallState.Outgoing -> {
                startRingTimer()
                IntentBus.publishOpenCall(new.call.callId)
                audioRouter.start(speaker = new.call.type == CallType.VIDEO)
                openSession(new.call, isCaller = true, cameraOn = new.call.type == CallType.VIDEO && hasPermission(Manifest.permission.CAMERA))
                startService(new.call, null) // last: it suspends on a Room lookup
            }

            is CallState.Incoming -> {
                startRingTimer()
                ringer.start()
                // The NavGraph shows incomingCall/{id} as soon as it is (or becomes) visible; when the
                // app is in the background the full-screen notification brings it to the front.
                IntentBus.publishIncomingCall(new.call.callId)
                if (!AppVisibility.isVisible) {
                    notifier.showIncomingCall(new.call.callId, new.caller.displayName, new.call.type == CallType.VIDEO)
                }
            }

            is CallState.Active -> {
                if (old is CallState.Active) return // toggles / connectedAt: no lifecycle work
                cancelRingTimer()
                ringer.stop()
                notifier.cancelIncomingCall()
                val callerUser = (old as? CallState.Incoming)?.caller
                audioRouter.start(speaker = new.speaker)
                val s = session?.takeIf { it.call.callId == new.call.callId }
                    ?: openSession(new.call, isCaller = old is CallState.Outgoing, cameraOn = new.cameraOn)
                startService(new.call, callerUser) // suspends on a Room lookup; re-checks the state afterwards
                if (old is CallState.Outgoing) {
                    s.sendOffer()
                } else {
                    val replay = earlySignals.filter { it.callId == new.call.callId }
                    earlySignals.clear()
                    for (ev in replay) onSignal(ev)
                }
            }

            is CallState.Ended -> {
                cancelRingTimer()
                ringer.stop()
                notifier.cancelIncomingCall()
                // Drop navigation hints nobody consumed (app never came to the front while ringing).
                val id = new.call?.callId
                if (id != null && IntentBus.incomingCall.value == id) IntentBus.consumeIncomingCall()
                if (id != null && IntentBus.openCall.value == id) IntentBus.consumeOpenCall()
                earlySignals.clear()
                closeSession()
                audioRouter.stop()
                stopService()
                lingerJob?.cancel()
                lingerJob = scope.launch {
                    delay(CallReducer.ENDED_LINGER_MS)
                    dispatch(CallEvent.Reset)
                }
            }

            CallState.Idle -> Unit
        }
    }

    /** Ends the call from this phone; optionally tells the server (reject / end with the wire reason). */
    private suspend fun endLocally(reason: String, report: Boolean) {
        val before = withContext(dispatcher) { _state.value }
        if (before is CallState.Idle || before is CallState.Ended) return
        dispatch(CallEvent.LocalEnded(reason))
        if (report) report(before, reason)
    }

    /** Fire-and-forget REST; the state has already moved on. */
    private fun report(before: CallState, reason: String) {
        val call = before.callOrNull ?: return
        scope.launch(Dispatchers.IO) {
            try {
                when (before) {
                    is CallState.Incoming -> repo.reject(call.callId)
                    is CallState.Outgoing, is CallState.Active -> repo.end(call.callId, wireReason(before, reason))
                    else -> Unit
                }
            } catch (e: Exception) {
                // Offline: the server times the call out on its own; nothing else to do.
            }
        }
    }

    private fun wireReason(before: CallState, reason: String): String = when (reason) {
        EndReason.HANGUP, EndReason.TIMEOUT, EndReason.FAILED, EndReason.CANCELLED -> reason
        else -> CallReducer.hangupReason(before) ?: EndReason.HANGUP
    }

    private fun startRingTimer() {
        ringJob?.cancel()
        ringJob = scope.launch {
            delay(CallReducer.RING_TIMEOUT_MS)
            val before = _state.value
            dispatch(CallEvent.RingTimeout)
            if (before is CallState.Outgoing) report(before, EndReason.TIMEOUT)
        }
    }

    private fun cancelRingTimer() {
        ringJob?.cancel()
        ringJob = null
    }

    // ---------- service / names ----------

    /** Must run on [dispatcher]: after the (suspending) name lookup it re-checks that the call is still live. */
    private suspend fun startService(call: Call, knownPeer: User?) {
        val name = peerName(call, knownPeer)
        val now = _state.value
        if (now is CallState.Ended || now is CallState.Idle || now.callOrNull?.callId != call.callId) return
        try {
            CallService.start(appContext, call.callId, name, call.type == CallType.VIDEO)
            serviceRunning = true
        } catch (e: Exception) {
            // Background-start restriction: the call still works while the UI is visible.
        }
    }

    private fun stopService() {
        if (!serviceRunning) return
        serviceRunning = false
        try {
            CallService.stop(appContext)
        } catch (e: Exception) {
            // Already stopped.
        }
    }

    /** The other party's display name (Room cache; the invite carries the caller). */
    suspend fun peerName(call: Call, knownPeer: User? = null): String {
        val me = serverConfig.current.value.userId
        val peerId = if (call.callerId == me) call.calleeId else call.callerId
        knownPeer?.takeIf { it.userId == peerId }?.let { return it.displayName }
        return try {
            withContext(Dispatchers.IO) { db.users().get(peerId)?.displayName }
        } catch (e: Exception) {
            null
        } ?: appContext.getString(R.string.call_unknown_peer)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    // ---------- WebRTC session ----------

    /** Must run on [dispatcher]. */
    private fun openSession(call: Call, isCaller: Boolean, cameraOn: Boolean): Session {
        closeSession()
        val s = Session(call, isCaller)
        session = s
        s.open(cameraOn)
        return s
    }

    /** Must run on [dispatcher]. */
    private fun closeSession() {
        val s = session ?: return
        session = null
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        s.close()
    }

    /**
     * One PeerConnection with its local media. Callbacks from WebRTC threads hop onto [scope];
     * every method here is invoked on [dispatcher].
     */
    private inner class Session(val call: Call, val isCaller: Boolean) {
        private val video = call.type == CallType.VIDEO
        private val factory = PeerConnectionFactoryProvider.factory(appContext)

        private var pc: PeerConnection? = null
        private var audioSource: AudioSource? = null
        private var audioTrack: AudioTrack? = null
        private var videoSource: VideoSource? = null
        private var videoTrack: VideoTrack? = null
        private var capturer: CameraVideoCapturer? = null
        private var surfaceHelper: SurfaceTextureHelper? = null
        private var capturing = false
        private var remoteDescriptionSet = false
        private val pendingRemoteIce = ArrayList<IceCandidate>()
        @Volatile private var closed = false

        fun open(cameraOn: Boolean) {
            val config = PeerConnectionFactoryProvider.rtcConfiguration(
                PeerConnectionFactoryProvider.iceServers(serverConfig.current.value.iceServers),
            )
            val connection = factory.createPeerConnection(config, observer) ?: error("createPeerConnection returned null")
            pc = connection

            val aSource = factory.createAudioSource(MediaConstraints())
            val aTrack = factory.createAudioTrack("audio0", aSource)
            aTrack.setEnabled(true)
            connection.addTrack(aTrack, listOf(STREAM_ID))
            audioSource = aSource
            audioTrack = aTrack
            PeerConnectionFactoryProvider.setMicrophoneMute(false)

            if (video) {
                val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(appContext)) Camera2Enumerator(appContext) else Camera1Enumerator(true)
                val names = enumerator.deviceNames
                val front = names.firstOrNull { enumerator.isFrontFacing(it) }
                val device = front ?: names.firstOrNull()
                if (device != null) {
                    _frontCamera.value = front != null
                    val cap = enumerator.createCapturer(device, null)
                    val helper = SurfaceTextureHelper.create("GuftuguCapture", PeerConnectionFactoryProvider.eglContext)
                    val vSource = factory.createVideoSource(cap.isScreencast)
                    cap.initialize(helper, appContext, vSource.capturerObserver)
                    val vTrack = factory.createVideoTrack("video0", vSource)
                    connection.addTrack(vTrack, listOf(STREAM_ID))
                    capturer = cap
                    surfaceHelper = helper
                    videoSource = vSource
                    videoTrack = vTrack
                    if (cameraOn) {
                        cap.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
                        capturing = true
                        vTrack.setEnabled(true)
                    } else {
                        vTrack.setEnabled(false)
                    }
                    _localVideoTrack.value = vTrack
                }
            }
        }

        /**
         * Negotiation suspends on WebRTC callbacks; the session may be closed meanwhile (peer hung
         * up), so every step re-checks [closed] before touching the native PeerConnection again.
         */
        suspend fun sendOffer() {
            val connection = pc ?: return
            val offer = connection.createSdp(offer = true, constraints())
            if (closed) return
            connection.setLocal(offer)
            if (closed) return
            if (!signaling.send(call.convId, call.callId, Signal.Offer(offer.description))) {
                throw IllegalStateException("no conversation key")
            }
        }

        suspend fun handle(signal: Signal) {
            val connection = pc ?: return
            when (signal) {
                is Signal.Offer -> {
                    connection.setRemote(SessionDescription(SessionDescription.Type.OFFER, signal.sdp))
                    if (closed) return
                    onRemoteDescriptionSet(connection)
                    val answer = connection.createSdp(offer = false, constraints())
                    if (closed) return
                    connection.setLocal(answer)
                    if (closed) return
                    if (!signaling.send(call.convId, call.callId, Signal.Answer(answer.description))) {
                        throw IllegalStateException("no conversation key")
                    }
                }
                is Signal.Answer -> {
                    connection.setRemote(SessionDescription(SessionDescription.Type.ANSWER, signal.sdp))
                    if (closed) return
                    onRemoteDescriptionSet(connection)
                }
                is Signal.Ice -> {
                    val candidate = IceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.candidate)
                    if (remoteDescriptionSet) connection.addIceCandidate(candidate) else pendingRemoteIce += candidate
                }
            }
        }

        private fun onRemoteDescriptionSet(connection: PeerConnection) {
            remoteDescriptionSet = true
            pendingRemoteIce.forEach { connection.addIceCandidate(it) }
            pendingRemoteIce.clear()
        }

        fun setMuted(muted: Boolean) {
            audioTrack?.setEnabled(!muted)
            PeerConnectionFactoryProvider.setMicrophoneMute(muted)
        }

        fun setCameraOn(on: Boolean) {
            val cap = capturer ?: return
            val track = videoTrack ?: return
            if (on && !capturing) {
                runCatching { cap.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS) }
                capturing = true
            } else if (!on && capturing) {
                runCatching { cap.stopCapture() }
                capturing = false
            }
            track.setEnabled(on)
        }

        fun switchCamera() {
            val cap = capturer ?: return
            cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) { _frontCamera.value = isFrontCamera }
                override fun onCameraSwitchError(errorDescription: String?) = Unit
            })
        }

        fun close() {
            closed = true
            runCatching { if (capturing) capturer?.stopCapture() }
            capturing = false
            runCatching { capturer?.dispose() }
            runCatching { surfaceHelper?.dispose() }
            runCatching { pc?.close() }
            runCatching { pc?.dispose() }
            runCatching { videoTrack?.dispose() }
            runCatching { videoSource?.dispose() }
            runCatching { audioTrack?.dispose() }
            runCatching { audioSource?.dispose() }
            pc = null; videoTrack = null; videoSource = null; audioTrack = null; audioSource = null
            capturer = null; surfaceHelper = null
            pendingRemoteIce.clear()
        }

        private fun constraints() = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", video.toString()))
        }

        private val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (closed) return
                scope.launch {
                    if (closed || session !== this@Session) return@launch
                    signaling.send(call.convId, call.callId, Signal.Ice(candidate.sdp, candidate.sdpMid ?: "", candidate.sdpMLineIndex))
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                if (closed) return
                scope.launch {
                    if (closed || session !== this@Session) return@launch
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> dispatch(CallEvent.IceConnected(Time.nowMs()))
                        PeerConnection.IceConnectionState.FAILED -> endLocally(EndReason.FAILED, report = true)
                        else -> Unit
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (closed || newState != PeerConnection.PeerConnectionState.FAILED) return
                scope.launch {
                    if (closed || session !== this@Session) return@launch
                    endLocally(EndReason.FAILED, report = true)
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                if (closed) return
                val track = transceiver.receiver?.track() as? VideoTrack ?: return
                scope.launch {
                    if (closed || session !== this@Session) return@launch
                    track.setEnabled(true)
                    _remoteVideoTrack.value = track
                }
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                if (closed) return
                val track = receiver.track() as? VideoTrack ?: return
                scope.launch {
                    if (closed || session !== this@Session) return@launch
                    if (_remoteVideoTrack.value == null) {
                        track.setEnabled(true)
                        _remoteVideoTrack.value = track
                    }
                }
            }

            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
        }
    }

    // ---------- SDP helpers (callback → suspend) ----------

    private suspend fun PeerConnection.createSdp(offer: Boolean, constraints: MediaConstraints): SessionDescription =
        withTimeout(SDP_TIMEOUT_MS) { suspendCancellableCoroutine { cont ->
            val obs = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
                override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "sdp create failed")) }
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            }
            if (offer) createOffer(obs, constraints) else createAnswer(obs, constraints)
        } }

    private suspend fun PeerConnection.setLocal(sdp: SessionDescription) = withTimeout(SDP_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont -> setLocalDescription(SetObserver(cont), sdp) }
    }

    private suspend fun PeerConnection.setRemote(sdp: SessionDescription) = withTimeout(SDP_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont -> setRemoteDescription(SetObserver(cont), sdp) }
    }

    private class SetObserver(private val cont: kotlinx.coroutines.CancellableContinuation<Unit>) : SdpObserver {
        override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
        override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "sdp set failed")) }
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onCreateFailure(error: String?) = Unit
    }

    companion object {
        private const val STREAM_ID = "guftugu"
        private const val CAPTURE_WIDTH = 640
        private const val CAPTURE_HEIGHT = 480
        private const val CAPTURE_FPS = 30
        private const val MAX_EARLY_SIGNALS = 64
        private const val SDP_TIMEOUT_MS = 10_000L
    }
}
