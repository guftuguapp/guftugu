package com.guftugu.app.data.ws

import com.guftugu.app.core.util.Time
import com.guftugu.app.protocol.ClientEvent
import com.guftugu.app.protocol.ErrorCode
import com.guftugu.app.protocol.PROTOCOL_VERSION
import com.guftugu.app.protocol.ProtocolJson
import com.guftugu.app.protocol.ServerEvent
import com.guftugu.app.protocol.decodeServerEvent
import com.guftugu.app.protocol.encodeClientEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    /** Socket open; [connectionId] is known once the server's `hello` arrives. */
    data class Connected(val connectionId: String? = null) : ConnectionState()
}

/**
 * The WebSocket half of PROTOCOL.md (§12). Implementations keep one socket to `wsUrl`,
 * authenticate with the bearer header, send `hello` first, `ping` every 4 minutes, and
 * reconnect with backoff. Events are emitted as they arrive; reconciliation after a
 * (re)connect is the SyncEngine's job (it watches [state]).
 */
interface RealtimeClient {
    val state: StateFlow<ConnectionState>
    val events: SharedFlow<ServerEvent>
    fun connect()
    fun disconnect()
    /** Best effort: dropped when not connected. */
    fun send(event: ClientEvent)
}

/**
 * OkHttp implementation.
 *
 * - `Authorization: Bearer <token>` + `X-Guftugu-Protocol: 1` on the handshake.
 * - Sends `{ "type": "hello" }` on open (serverless gateways can't push before the first client frame).
 * - `ping` every [PING_INTERVAL_MS] (API Gateway idles out at 10 min).
 * - On close/failure while still wanted: exponential backoff 1 s → 60 s with ±50 % jitter.
 *   A 401/403 at the handshake means the session is dead: an `error` event with code
 *   `unauthorized` is emitted and reconnecting stops until [connect] is called again.
 * - `events` is a `SharedFlow(extraBufferCapacity = 64)`; slow collectors drop the oldest frames
 *   (the SyncEngine reconciles with REST on every reconnect anyway).
 */
class OkHttpRealtimeClient(
    private val client: OkHttpClient,
    private val wsUrl: () -> String?,
    private val token: () -> String?,
    private val json: Json = ProtocolJson,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pingIntervalMs: Long = PING_INTERVAL_MS,
    private val minBackoffMs: Long = MIN_BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
) : RealtimeClient {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val lock = Any()
    private val wanted = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var attempt = 0
    /** Guards against callbacks from a socket we already replaced. */
    private var generation = 0L

    override fun connect() {
        wanted.set(true)
        synchronized(lock) {
            reconnectJob?.cancel(); reconnectJob = null
            if (socket != null) return
            open()
        }
    }

    override fun disconnect() {
        wanted.set(false)
        synchronized(lock) {
            reconnectJob?.cancel(); reconnectJob = null
            pingJob?.cancel(); pingJob = null
            attempt = 0
            val s = socket
            socket = null
            generation++
            s?.close(1000, "bye")
        }
        _state.value = ConnectionState.Disconnected
    }

    override fun send(event: ClientEvent) {
        val s = synchronized(lock) { socket } ?: return
        runCatching { s.send(json.encodeClientEvent(event)) }
    }

    // ---------- internals ----------

    /** Must hold [lock]. */
    private fun open() {
        val url = wsUrl()
        val t = token()
        if (url == null || t == null) {
            // Nothing to connect to yet; stay Disconnected. connect() will be called again after login.
            _state.value = ConnectionState.Disconnected
            return
        }
        val gen = ++generation
        _state.value = ConnectionState.Connecting
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $t")
            .header("X-Guftugu-Protocol", PROTOCOL_VERSION.toString())
            .build()
        socket = client.newWebSocket(request, Listener(gen))
    }

    private inner class Listener(private val gen: Long) : WebSocketListener() {
        private fun current(): Boolean = synchronized(lock) { gen == generation }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!current()) { webSocket.close(1000, "stale"); return }
            synchronized(lock) { attempt = 0 }
            _state.value = ConnectionState.Connected(null)
            runCatching { webSocket.send(json.encodeClientEvent(ClientEvent.Hello)) }
            startPing()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!current()) return
            val event = try {
                json.decodeServerEvent(text)
            } catch (e: Exception) {
                return // malformed frame: ignore, never crash the socket loop
            }
            when (event) {
                is ServerEvent.Hello -> {
                    Time.observeServerTime(event.serverTime)
                    _state.value = ConnectionState.Connected(event.connectionId)
                }
                is ServerEvent.Pong -> Time.observeServerTime(event.serverTime)
                else -> Unit
            }
            _events.tryEmit(event)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!current()) return
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!current()) return
            onGone(unauthorized = false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!current()) return
            val code = response?.code ?: 0
            onGone(unauthorized = code == 401 || code == 403)
        }
    }

    private fun onGone(unauthorized: Boolean) {
        synchronized(lock) {
            pingJob?.cancel(); pingJob = null
            socket = null
            generation++
        }
        _state.value = ConnectionState.Disconnected
        if (unauthorized) {
            wanted.set(false)
            _events.tryEmit(ServerEvent.Error(ErrorCode.UNAUTHORIZED, "websocket handshake rejected"))
            return
        }
        if (wanted.get()) scheduleReconnect()
    }

    private fun startPing() {
        synchronized(lock) {
            pingJob?.cancel()
            pingJob = scope.launch {
                while (isActive) {
                    delay(pingIntervalMs)
                    send(ClientEvent.Ping)
                }
            }
        }
    }

    private fun scheduleReconnect() {
        synchronized(lock) {
            if (reconnectJob?.isActive == true) return
            val delayMs = backoffDelay(attempt)
            attempt = (attempt + 1).coerceAtMost(16)
            reconnectJob = scope.launch {
                delay(delayMs)
                if (!wanted.get()) return@launch
                synchronized(lock) {
                    reconnectJob = null
                    if (socket == null) open()
                }
            }
        }
    }

    /** `min(max, min * 2^attempt)` with ±50 % jitter. */
    internal fun backoffDelay(attempt: Int): Long {
        val base = (minBackoffMs shl attempt.coerceIn(0, 20)).coerceIn(minBackoffMs, maxBackoffMs)
        val jitter = 0.5 + Random.nextDouble() // 0.5 .. 1.5
        return (base * jitter).toLong().coerceIn(minBackoffMs / 2, maxBackoffMs)
    }

    companion object {
        const val PING_INTERVAL_MS = 4L * 60 * 1000
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val EVENT_BUFFER = 64
    }
}
