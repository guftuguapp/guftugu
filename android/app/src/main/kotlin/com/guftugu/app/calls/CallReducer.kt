package com.guftugu.app.calls

import com.guftugu.app.protocol.Call
import com.guftugu.app.protocol.CallState as WireCallState
import com.guftugu.app.protocol.CallType
import com.guftugu.app.protocol.EndReason
import com.guftugu.app.protocol.User

/**
 * Everything that can move the call state machine. Produced by REST results, WebSocket events,
 * WebRTC callbacks, timers and the user; consumed by [CallReducer.reduce].
 */
sealed class CallEvent {
    /** `POST /calls` returned (state `ringing`, or `ended`/`unreachable` when the callee has no live connection). */
    data class Started(val call: Call) : CallEvent()

    /** Could not even place the call (no microphone permission, no conversation key, REST failure). */
    data class StartFailed(val reason: String) : CallEvent()

    /** `call.invite` frame. */
    data class InviteReceived(val call: Call, val caller: User) : CallEvent()

    /**
     * Caller: `call.answered` frame. Callee: `POST /calls/{id}/answer` returned.
     * [cameraAvailable] is false when the CAMERA permission is missing (video calls start with the camera off).
     */
    data class Answered(val call: Call, val cameraAvailable: Boolean = true) : CallEvent()

    /** ICE reached CONNECTED/COMPLETED — starts the timer. */
    data class IceConnected(val at: Long) : CallEvent()

    /** `call.ended` frame (hangup by the peer, `answered_elsewhere`, timeout, rejected…). */
    data class RemoteEnded(val callId: String, val reason: String) : CallEvent()

    /** A decision taken on this phone: hangup, reject, cancel, failed, no key, no permission. */
    data class LocalEnded(val reason: String) : CallEvent()

    /** 45 s without an answer (caller: `timeout`; callee: `missed`). */
    data object RingTimeout : CallEvent()

    data object ToggleMute : CallEvent()
    data object ToggleCamera : CallEvent()
    data object ToggleSpeaker : CallEvent()

    /** Ended → Idle once the UI has shown the outcome. */
    data object Reset : CallEvent()
}

/**
 * Pure state machine for 1:1 calls (PROTOCOL §11). No I/O, no Android — the
 * [CallManagerImpl] runs side effects by comparing the state before and after a [reduce].
 *
 * ```
 * Idle ─Started(ringing)─▶ Outgoing ─Answered─▶ Active ─Remote/LocalEnded─▶ Ended ─Reset─▶ Idle
 *   │                          └─RingTimeout─▶ Ended(timeout)
 *   └─InviteReceived─▶ Incoming ─Answered─▶ Active
 *                          └─RingTimeout─▶ Ended(missed)
 * ```
 */
object CallReducer {
    const val RING_TIMEOUT_MS = 45_000L
    /** How long [CallState.Ended] stays visible before the manager resets to Idle. */
    const val ENDED_LINGER_MS = 3_000L

    /** Local end reasons that are not on the wire (`EndReason`) but shown by the UI. */
    const val REASON_MISSED = "missed"
    const val REASON_NO_KEY = "no_key"
    const val REASON_NO_PERMISSION = "no_permission"
    const val REASON_BUSY = "busy"

    fun reduce(state: CallState, event: CallEvent): CallState = when (event) {
        is CallEvent.Started -> when {
            !acceptsNewCall(state) -> state
            event.call.state == WireCallState.ENDED -> CallState.Ended(event.call, event.call.endReason ?: EndReason.UNREACHABLE)
            else -> CallState.Outgoing(event.call)
        }

        is CallEvent.StartFailed ->
            if (acceptsNewCall(state)) CallState.Ended(null, event.reason) else state

        is CallEvent.InviteReceived ->
            if (acceptsNewCall(state)) CallState.Incoming(event.call, event.caller) else state

        is CallEvent.Answered -> when {
            state is CallState.Outgoing && state.call.callId == event.call.callId -> active(event)
            state is CallState.Incoming && state.call.callId == event.call.callId -> active(event)
            else -> state
        }

        is CallEvent.IceConnected ->
            if (state is CallState.Active) state.copy(connectedAt = state.connectedAt ?: event.at) else state

        is CallEvent.RemoteEnded -> when {
            state is CallState.Idle || state is CallState.Ended -> state
            state.callOrNull?.callId != event.callId -> state
            else -> CallState.Ended(state.callOrNull, event.reason)
        }

        is CallEvent.LocalEnded -> when (state) {
            is CallState.Idle, is CallState.Ended -> state
            else -> CallState.Ended(state.callOrNull, event.reason)
        }

        CallEvent.RingTimeout -> when (state) {
            is CallState.Outgoing -> CallState.Ended(state.call, EndReason.TIMEOUT)
            is CallState.Incoming -> CallState.Ended(state.call, REASON_MISSED)
            else -> state
        }

        CallEvent.ToggleMute -> if (state is CallState.Active) state.copy(muted = !state.muted) else state

        CallEvent.ToggleCamera ->
            if (state is CallState.Active && state.call.type == CallType.VIDEO) state.copy(cameraOn = !state.cameraOn) else state

        CallEvent.ToggleSpeaker -> if (state is CallState.Active) state.copy(speaker = !state.speaker) else state

        CallEvent.Reset -> if (state is CallState.Ended) CallState.Idle else state
    }

    private fun active(event: CallEvent.Answered): CallState.Active {
        val video = event.call.type == CallType.VIDEO
        return CallState.Active(
            call = event.call,
            muted = false,
            cameraOn = video && event.cameraAvailable,
            speaker = video,
            connectedAt = null,
        )
    }

    /** A new call (outgoing or an invite) may replace Idle or a lingering Ended. */
    fun acceptsNewCall(state: CallState): Boolean = state is CallState.Idle || state is CallState.Ended

    /** Signals are only meaningful while negotiating; anything else (stale, foreign callId) is dropped. */
    fun acceptsSignal(state: CallState, callId: String): Boolean =
        state is CallState.Active && state.call.callId == callId

    /** `call.answered` only matters to the caller of that very call. */
    fun acceptsAnswered(state: CallState, callId: String): Boolean =
        state is CallState.Outgoing && state.call.callId == callId

    /** Whether the phone is in a call (or ringing) and should ignore new invites. */
    fun isBusy(state: CallState): Boolean = !acceptsNewCall(state)

    /**
     * What to tell the server when the user ends the call from this state:
     * `cancelled` while ringing out, `hangup` once active, null for Incoming (use the reject endpoint).
     */
    fun hangupReason(state: CallState): String? = when (state) {
        is CallState.Outgoing -> EndReason.CANCELLED
        is CallState.Active -> EndReason.HANGUP
        else -> null
    }
}
