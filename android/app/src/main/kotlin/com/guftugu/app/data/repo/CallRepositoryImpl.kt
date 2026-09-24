package com.guftugu.app.data.repo

import com.guftugu.app.data.api.GuftuguApi
import com.guftugu.app.data.ws.RealtimeClient
import com.guftugu.app.protocol.Call
import com.guftugu.app.protocol.CallType
import com.guftugu.app.protocol.ClientEvent
import com.guftugu.app.protocol.Envelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** REST half of PROTOCOL §11 plus the `call.signal` client frame (§12). Throws [com.guftugu.app.data.api.ApiException]. */
class CallRepositoryImpl(
    private val api: GuftuguApi,
    private val realtime: RealtimeClient,
) : CallRepository {

    override suspend fun start(convId: String, video: Boolean): Call = withContext(Dispatchers.IO) {
        api.startCall(convId, if (video) CallType.VIDEO else CallType.AUDIO)
    }

    override suspend fun answer(callId: String): Call = withContext(Dispatchers.IO) { api.answerCall(callId) }

    override suspend fun reject(callId: String): Call = withContext(Dispatchers.IO) { api.rejectCall(callId) }

    override suspend fun end(callId: String, reason: String): Call = withContext(Dispatchers.IO) { api.endCall(callId, reason) }

    override suspend fun get(callId: String): Call = withContext(Dispatchers.IO) { api.call(callId) }

    override fun sendSignal(callId: String, envelope: Envelope) {
        realtime.send(ClientEvent.CallSignal(callId, envelope))
    }
}
