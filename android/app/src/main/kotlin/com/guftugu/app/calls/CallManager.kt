package com.guftugu.app.calls

import com.guftugu.app.protocol.Call
import com.guftugu.app.protocol.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.VideoTrack

sealed class CallState {
    data object Idle : CallState()
    data class Outgoing(val call: Call) : CallState()
    data class Incoming(val call: Call, val caller: User) : CallState()
    data class Active(
        val call: Call,
        val muted: Boolean = false,
        val cameraOn: Boolean = false,
        val speaker: Boolean = false,
        val connectedAt: Long? = null,
    ) : CallState()
    data class Ended(val call: Call?, val reason: String) : CallState()

    val callOrNull: Call?
        get() = when (this) {
            is Outgoing -> call
            is Incoming -> call
            is Active -> call
            is Ended -> call
            Idle -> null
        }
}

/**
 * 1:1 WebRTC call state machine (PROTOCOL §11). Signalling (SDP/ICE) is encrypted under the
 * conversation key and relayed as `call.signal`; media is DTLS-SRTP peer-to-peer.
 * After `call.answered` the caller creates the offer.
 */
interface CallManager {
    val state: StateFlow<CallState>
    suspend fun startCall(convId: String, video: Boolean)
    suspend fun answer()
    suspend fun reject()
    suspend fun hangup()
    fun toggleMute()
    fun toggleCamera()
    fun toggleSpeaker()
    fun switchCamera()
    val localVideoTrack: StateFlow<VideoTrack?>
    val remoteVideoTrack: StateFlow<VideoTrack?>
}

class StubCallManager : CallManager {
    override val state: StateFlow<CallState> = MutableStateFlow(CallState.Idle)
    override suspend fun startCall(convId: String, video: Boolean) = throw NotImplementedError("implemented in feature phase")
    override suspend fun answer() = throw NotImplementedError("implemented in feature phase")
    override suspend fun reject() = throw NotImplementedError("implemented in feature phase")
    override suspend fun hangup() = throw NotImplementedError("implemented in feature phase")
    override fun toggleMute() = throw NotImplementedError("implemented in feature phase")
    override fun toggleCamera() = throw NotImplementedError("implemented in feature phase")
    override fun toggleSpeaker() = throw NotImplementedError("implemented in feature phase")
    override fun switchCamera() = throw NotImplementedError("implemented in feature phase")
    override val localVideoTrack: StateFlow<VideoTrack?> = MutableStateFlow(null)
    override val remoteVideoTrack: StateFlow<VideoTrack?> = MutableStateFlow(null)
}
