package com.guftugu.app.calls

import com.guftugu.app.protocol.Call
import com.guftugu.app.protocol.CallState as Wire
import com.guftugu.app.protocol.CallType
import com.guftugu.app.protocol.EndReason
import com.guftugu.app.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CallReducerTest {

    private fun call(id: String = "k_1", type: String = CallType.AUDIO, state: String = Wire.RINGING, calleeDevice: String? = null, endReason: String? = null) =
        Call(
            callId = id, convId = "c_1", type = type, callerId = "u_caller", callerDeviceId = "d_caller",
            calleeId = "u_callee", calleeDeviceId = calleeDevice, state = state, createdAt = 1_000L, endReason = endReason,
        )

    private val caller = User(userId = "u_caller", displayName = "Ammi", role = "member", status = "active", createdAt = 0L)

    private fun reduce(start: CallState, vararg events: CallEvent): CallState = events.fold(start) { s, e -> CallReducer.reduce(s, e) }

    // ---------- happy paths ----------

    @Test
    fun `callee - invite, answer, connect, remote hangup`() {
        val incoming = reduce(CallState.Idle, CallEvent.InviteReceived(call(), caller))
        assertEquals(CallState.Incoming(call(), caller), incoming)

        val answered = call(state = Wire.ACTIVE, calleeDevice = "d_callee")
        val active = reduce(incoming, CallEvent.Answered(answered))
        assertTrue(active is CallState.Active)
        active as CallState.Active
        assertEquals(answered, active.call)
        assertFalse(active.muted)
        assertFalse(active.cameraOn)   // audio call
        assertFalse(active.speaker)    // earpiece by default for audio
        assertNull(active.connectedAt)

        val connected = reduce(active, CallEvent.IceConnected(5_000L)) as CallState.Active
        assertEquals(5_000L, connected.connectedAt)
        // a second CONNECTED (after a reconnect) must not reset the timer
        assertEquals(5_000L, (reduce(connected, CallEvent.IceConnected(9_000L)) as CallState.Active).connectedAt)

        val ended = reduce(connected, CallEvent.RemoteEnded("k_1", EndReason.HANGUP))
        assertEquals(CallState.Ended(answered, EndReason.HANGUP), ended)

        assertEquals(CallState.Idle, reduce(ended, CallEvent.Reset))
    }

    @Test
    fun `caller - start, answered, active, local hangup`() {
        val outgoing = reduce(CallState.Idle, CallEvent.Started(call()))
        assertEquals(CallState.Outgoing(call()), outgoing)
        assertEquals(EndReason.CANCELLED, CallReducer.hangupReason(outgoing))

        val answered = call(state = Wire.ACTIVE, calleeDevice = "d_callee")
        val active = reduce(outgoing, CallEvent.Answered(answered)) as CallState.Active
        assertEquals(EndReason.HANGUP, CallReducer.hangupReason(active))

        val ended = reduce(active, CallEvent.LocalEnded(EndReason.HANGUP))
        assertEquals(CallState.Ended(answered, EndReason.HANGUP), ended)
    }

    @Test
    fun `video call defaults - camera on and speaker on, camera off when permission missing`() {
        val answered = call(type = CallType.VIDEO, state = Wire.ACTIVE, calleeDevice = "d_callee")
        val outgoing = CallState.Outgoing(call(type = CallType.VIDEO))

        val withCamera = reduce(outgoing, CallEvent.Answered(answered)) as CallState.Active
        assertTrue(withCamera.cameraOn)
        assertTrue(withCamera.speaker)

        val noCamera = reduce(outgoing, CallEvent.Answered(answered, cameraAvailable = false)) as CallState.Active
        assertFalse(noCamera.cameraOn)
        assertTrue(noCamera.speaker)
    }

    // ---------- unreachable / failures before ringing ----------

    @Test
    fun `POST calls answering ended-unreachable goes straight to Ended`() {
        val unreachable = call(state = Wire.ENDED, endReason = EndReason.UNREACHABLE)
        assertEquals(CallState.Ended(unreachable, EndReason.UNREACHABLE), reduce(CallState.Idle, CallEvent.Started(unreachable)))
        // an ended call without a reason is still "unreachable" from the caller's perspective
        val noReason = call(state = Wire.ENDED)
        assertEquals(CallState.Ended(noReason, EndReason.UNREACHABLE), reduce(CallState.Idle, CallEvent.Started(noReason)))
    }

    @Test
    fun `StartFailed only applies when not in a call`() {
        assertEquals(CallState.Ended(null, CallReducer.REASON_NO_KEY), reduce(CallState.Idle, CallEvent.StartFailed(CallReducer.REASON_NO_KEY)))
        val active = CallState.Active(call(state = Wire.ACTIVE))
        assertSame(active, reduce(active, CallEvent.StartFailed(CallReducer.REASON_NO_PERMISSION)))
    }

    // ---------- timeouts ----------

    @Test
    fun `ring timeout - caller gets timeout, callee gets missed, others unaffected`() {
        assertEquals(CallState.Ended(call(), EndReason.TIMEOUT), reduce(CallState.Outgoing(call()), CallEvent.RingTimeout))
        assertEquals(CallState.Ended(call(), CallReducer.REASON_MISSED), reduce(CallState.Incoming(call(), caller), CallEvent.RingTimeout))
        val active = CallState.Active(call(state = Wire.ACTIVE), connectedAt = 1L)
        assertSame(active, reduce(active, CallEvent.RingTimeout))
        assertSame(CallState.Idle, reduce(CallState.Idle, CallEvent.RingTimeout))
    }

    // ---------- answered elsewhere / foreign call ids ----------

    @Test
    fun `answered_elsewhere ends the incoming call on this device`() {
        val incoming = CallState.Incoming(call(), caller)
        val ended = reduce(incoming, CallEvent.RemoteEnded("k_1", EndReason.ANSWERED_ELSEWHERE))
        assertEquals(CallState.Ended(call(), EndReason.ANSWERED_ELSEWHERE), ended)
    }

    @Test
    fun `call ended for another callId is ignored`() {
        val active = CallState.Active(call(state = Wire.ACTIVE))
        assertSame(active, reduce(active, CallEvent.RemoteEnded("k_other", EndReason.HANGUP)))
        val incoming = CallState.Incoming(call(), caller)
        assertSame(incoming, reduce(incoming, CallEvent.RemoteEnded("k_other", EndReason.CANCELLED)))
    }

    @Test
    fun `answered for another callId or from the wrong state is ignored`() {
        val outgoing = CallState.Outgoing(call())
        assertSame(outgoing, reduce(outgoing, CallEvent.Answered(call(id = "k_other", state = Wire.ACTIVE, calleeDevice = "d"))))
        assertSame(CallState.Idle, reduce(CallState.Idle, CallEvent.Answered(call(state = Wire.ACTIVE, calleeDevice = "d"))))
        val ended = CallState.Ended(call(), EndReason.CANCELLED)
        assertSame(ended, reduce(ended, CallEvent.Answered(call(state = Wire.ACTIVE, calleeDevice = "d"))))
    }

    // ---------- busy ----------

    @Test
    fun `invites and starts are ignored while busy, accepted while Idle or lingering Ended`() {
        val invite = CallEvent.InviteReceived(call(id = "k_2"), caller)
        val active = CallState.Active(call(state = Wire.ACTIVE))
        assertSame(active, reduce(active, invite))
        assertSame(active, reduce(active, CallEvent.Started(call(id = "k_2"))))
        val outgoing = CallState.Outgoing(call())
        assertSame(outgoing, reduce(outgoing, invite))
        assertTrue(CallReducer.isBusy(active))
        assertTrue(CallReducer.isBusy(outgoing))
        assertTrue(CallReducer.isBusy(CallState.Incoming(call(), caller)))

        val lingering = CallState.Ended(call(), EndReason.HANGUP)
        assertFalse(CallReducer.isBusy(lingering))
        assertEquals(CallState.Incoming(call(id = "k_2"), caller), reduce(lingering, invite))
        assertEquals(CallState.Incoming(call(id = "k_2"), caller), reduce(CallState.Idle, invite))
    }

    // ---------- signal routing guards ----------

    @Test
    fun `signals are accepted only while Active on the same callId`() {
        assertTrue(CallReducer.acceptsSignal(CallState.Active(call(state = Wire.ACTIVE)), "k_1"))
        assertFalse(CallReducer.acceptsSignal(CallState.Active(call(state = Wire.ACTIVE)), "k_other"))
        assertFalse(CallReducer.acceptsSignal(CallState.Outgoing(call()), "k_1"))
        assertFalse(CallReducer.acceptsSignal(CallState.Incoming(call(), caller), "k_1"))
        assertFalse(CallReducer.acceptsSignal(CallState.Ended(call(), EndReason.HANGUP), "k_1"))
        assertFalse(CallReducer.acceptsSignal(CallState.Idle, "k_1"))
    }

    @Test
    fun `call answered is accepted only by the caller of that call`() {
        assertTrue(CallReducer.acceptsAnswered(CallState.Outgoing(call()), "k_1"))
        assertFalse(CallReducer.acceptsAnswered(CallState.Outgoing(call()), "k_2"))
        assertFalse(CallReducer.acceptsAnswered(CallState.Incoming(call(), caller), "k_1"))
        assertFalse(CallReducer.acceptsAnswered(CallState.Active(call(state = Wire.ACTIVE)), "k_1"))
    }

    // ---------- toggles ----------

    @Test
    fun `toggles only change Active and camera only in video calls`() {
        val audio = CallState.Active(call(state = Wire.ACTIVE))
        val muted = reduce(audio, CallEvent.ToggleMute) as CallState.Active
        assertTrue(muted.muted)
        assertFalse((reduce(muted, CallEvent.ToggleMute) as CallState.Active).muted)
        assertSame(audio, reduce(audio, CallEvent.ToggleCamera)) // audio call: no camera
        assertTrue((reduce(audio, CallEvent.ToggleSpeaker) as CallState.Active).speaker)

        val video = CallState.Active(call(type = CallType.VIDEO, state = Wire.ACTIVE), cameraOn = true, speaker = true)
        assertFalse((reduce(video, CallEvent.ToggleCamera) as CallState.Active).cameraOn)

        val outgoing = CallState.Outgoing(call())
        assertSame(outgoing, reduce(outgoing, CallEvent.ToggleMute))
        assertSame(CallState.Idle, reduce(CallState.Idle, CallEvent.ToggleSpeaker))
    }

    // ---------- terminal states ----------

    @Test
    fun `Ended and Idle ignore end events, Reset only leaves Ended`() {
        val ended = CallState.Ended(call(), EndReason.HANGUP)
        assertSame(ended, reduce(ended, CallEvent.LocalEnded(EndReason.FAILED)))
        assertSame(ended, reduce(ended, CallEvent.RemoteEnded("k_1", EndReason.TIMEOUT)))
        assertSame(CallState.Idle, reduce(CallState.Idle, CallEvent.LocalEnded(EndReason.HANGUP)))
        assertSame(CallState.Idle, reduce(CallState.Idle, CallEvent.Reset))
        val active = CallState.Active(call(state = Wire.ACTIVE))
        assertSame(active, reduce(active, CallEvent.Reset))
    }

    @Test
    fun `local end from any live state carries the call and the reason`() {
        assertEquals(CallState.Ended(call(), EndReason.REJECTED), reduce(CallState.Incoming(call(), caller), CallEvent.LocalEnded(EndReason.REJECTED)))
        assertEquals(CallState.Ended(call(), EndReason.CANCELLED), reduce(CallState.Outgoing(call()), CallEvent.LocalEnded(EndReason.CANCELLED)))
        assertEquals(CallState.Ended(call(), EndReason.FAILED), reduce(CallState.Active(call()), CallEvent.LocalEnded(EndReason.FAILED)))
        assertNull(CallReducer.hangupReason(CallState.Incoming(call(), caller)))
    }
}
