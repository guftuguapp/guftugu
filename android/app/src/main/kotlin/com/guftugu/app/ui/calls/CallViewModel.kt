package com.guftugu.app.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.guftugu.app.calls.CallManagerImpl
import com.guftugu.app.calls.CallState
import com.guftugu.app.calls.PeerConnectionFactoryProvider
import com.guftugu.app.di.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/** Shared by IncomingCallScreen and CallScreen: a thin view over [com.guftugu.app.calls.CallManager] plus name resolution. */
class CallViewModel(private val graph: AppGraph) : ViewModel() {

    private val manager get() = graph.callManager

    val state: StateFlow<CallState> = manager.state
    val localVideoTrack: StateFlow<VideoTrack?> = manager.localVideoTrack
    val remoteVideoTrack: StateFlow<VideoTrack?> = manager.remoteVideoTrack

    /** Mirror the local preview only for the front camera. */
    val frontCamera: StateFlow<Boolean> = (manager as? CallManagerImpl)?.frontCamera ?: MutableStateFlow(true)

    /** The other party's display name: from the invite for incoming calls, from Room otherwise. */
    val peerName: StateFlow<String> = state
        .flatMapLatest { st ->
            when (st) {
                is CallState.Incoming -> flowOf(st.caller.displayName)
                else -> {
                    val call = st.callOrNull
                    if (call == null) {
                        flowOf("")
                    } else {
                        val me = graph.serverConfig.current.value.userId
                        val peerId = if (call.callerId == me) call.calleeId else call.callerId
                        graph.db.users().observe(peerId).map { it?.displayName ?: "" }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Shared EGL context for the renderers (never null; creating it does not load the WebRTC native library). */
    val eglContext: EglBase.Context get() = PeerConnectionFactoryProvider.eglContext

    fun answer() = viewModelScope.launch { runCatching { manager.answer() } }
    fun reject() = viewModelScope.launch { runCatching { manager.reject() } }
    fun hangup() = viewModelScope.launch { runCatching { manager.hangup() } }
    fun toggleMute() = manager.toggleMute()
    fun toggleCamera() = manager.toggleCamera()
    fun toggleSpeaker() = manager.toggleSpeaker()
    fun switchCamera() = manager.switchCamera()

    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { CallViewModel(graph) }
        }
    }
}
