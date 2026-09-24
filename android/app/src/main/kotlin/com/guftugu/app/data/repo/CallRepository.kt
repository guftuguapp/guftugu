package com.guftugu.app.data.repo

import com.guftugu.app.protocol.Call
import com.guftugu.app.protocol.Envelope

/** Thin wrapper over the call endpoints (PROTOCOL §11) and `call.signal` frames; the state machine is [com.guftugu.app.calls.CallManager]. */
interface CallRepository {
    suspend fun start(convId: String, video: Boolean): Call
    suspend fun answer(callId: String): Call
    suspend fun reject(callId: String): Call
    /** [reason] is one of [com.guftugu.app.protocol.EndReason] hangup|timeout|failed|cancelled. */
    suspend fun end(callId: String, reason: String): Call
    suspend fun get(callId: String): Call
    /** Sends an encrypted signal over the WebSocket. */
    fun sendSignal(callId: String, envelope: Envelope)
}

class StubCallRepository : CallRepository {
    override suspend fun start(convId: String, video: Boolean): Call = throw NotImplementedError("implemented in feature phase")
    override suspend fun answer(callId: String): Call = throw NotImplementedError("implemented in feature phase")
    override suspend fun reject(callId: String): Call = throw NotImplementedError("implemented in feature phase")
    override suspend fun end(callId: String, reason: String): Call = throw NotImplementedError("implemented in feature phase")
    override suspend fun get(callId: String): Call = throw NotImplementedError("implemented in feature phase")
    override fun sendSignal(callId: String, envelope: Envelope) = throw NotImplementedError("implemented in feature phase")
}
