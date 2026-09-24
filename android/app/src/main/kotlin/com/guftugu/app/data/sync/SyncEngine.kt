package com.guftugu.app.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Reconciliation (PROTOCOL §12 "after every (re)connect"): `GET /conversations`, then
 * `GET …/messages?after=<local latest>` only for conversations whose `lastMsgId` moved;
 * applies live [com.guftugu.app.protocol.ServerEvent]s to Room; retries the outbox.
 * Typical sync = 1 request.
 */
interface SyncEngine {
    /** Start observing the RealtimeClient (events + reconnects) and the periodic timer. */
    fun start()
    fun stop()
    /** One reconciliation pass now (pull-to-refresh, app foreground, WorkManager). */
    suspend fun syncNow()
    /**
     * Emits when the server answered 401 (session expired / device revoked) during a sync or the
     * WebSocket handshake was rejected. The auth layer / UI observes it and returns to the unlock screen.
     */
    val authExpired: SharedFlow<Unit>
}

class StubSyncEngine : SyncEngine {
    override fun start() = Unit
    override fun stop() = Unit
    override suspend fun syncNow() = throw NotImplementedError("implemented in feature phase")
    override val authExpired: SharedFlow<Unit> = MutableSharedFlow()
}
