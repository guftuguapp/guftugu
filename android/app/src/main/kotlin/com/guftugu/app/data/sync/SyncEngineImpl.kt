package com.guftugu.app.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.guftugu.app.core.util.Time
import com.guftugu.app.data.api.ApiException
import com.guftugu.app.data.api.GuftuguApi
import com.guftugu.app.data.db.GuftuguDb
import com.guftugu.app.data.db.MessageEntity
import com.guftugu.app.data.db.MessageStatus
import com.guftugu.app.data.prefs.ServerConfigStore
import com.guftugu.app.data.repo.MessageRepository
import com.guftugu.app.data.ws.ConnectionState
import com.guftugu.app.data.ws.RealtimeClient
import com.guftugu.app.domain.toEntity
import com.guftugu.app.e2ee.ConversationKeyManager
import com.guftugu.app.notifications.Notifier
import com.guftugu.app.protocol.Conversation
import com.guftugu.app.protocol.ConversationType
import com.guftugu.app.protocol.ErrorCode
import com.guftugu.app.protocol.Message
import com.guftugu.app.protocol.MessageKind
import com.guftugu.app.protocol.ProtocolJson
import com.guftugu.app.protocol.ServerEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Offline-first reconciliation (ARCHITECTURE.md "Caching & sync", PROTOCOL §12).
 *
 * - [start]: connects the WebSocket; every (re)connect runs one [syncNow] then retries the outbox;
 *   live events are applied to Room as they arrive.
 * - [syncNow]: single-flight. `GET /users`, `GET /conversations` (upsert + detect removed), then
 *   `GET …/messages?after=` only for conversations whose `lastMsgId` moved (first time: latest 50).
 *   Decrypts, recomputes previews/unread, stamps `lastSyncAt`.
 * - Key work (forced `ensureKeys` after membership/epoch changes, `/keys` fetch after
 *   `conversation.keys`) runs on a sequential queue so a burst of events never fans out into
 *   parallel key-recipient requests; every key arrival re-decrypts that conversation's rows.
 * - A 401 anywhere → [authExpired].
 *
 * Errors are logged as counts/codes only; never message content, keys or tokens.
 */
class SyncEngineImpl(
    private val api: GuftuguApi,
    private val realtime: RealtimeClient,
    private val db: GuftuguDb,
    private val keyManager: ConversationKeyManager,
    private val serverConfig: ServerConfigStore,
    private val notifier: Notifier,
    private val appScope: CoroutineScope,
    private val json: Json = ProtocolJson,
    private val labels: PreviewLabels = PreviewLabels(),
    /** Bound lazily (`{ graph.messageRepository }`) because the message repository is built after the engine. */
    private val messages: () -> MessageRepository? = { null },
    /** `{ authRepository.sessionToken() != null }`: a sync while locked is skipped instead of being mistaken for an expired session. */
    private val hasSession: () -> Boolean = { true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SyncEngine {

    private val _authExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val authExpired: SharedFlow<Unit> = _authExpired.asSharedFlow()

    val ingest = MessageIngest(db, keyManager, json, labels) { myUserId() }

    private val lifecycleLock = Any()
    private var scope: CoroutineScope? = null

    private val syncLock = Any()
    private var inFlight: Deferred<Unit>? = null
    private val errors = AtomicInteger()

    private sealed class KeyWork(val convId: String) {
        /** [forced] bypasses the manager's throttle (epoch/membership changed); otherwise it is the cheap "app start" check. */
        class Ensure(convId: String, val forced: Boolean) : KeyWork(convId)
        class Fetch(convId: String, val keyId: String) : KeyWork(convId)
    }

    private val keyQueue = Channel<KeyWork>(Channel.UNLIMITED)
    private val queuedEnsure = HashSet<String>()
    private val requestedKeys = HashSet<String>()

    /** PROTOCOL §7 rule 2 "on app start": the first sync after [start] checks every conversation once. */
    @Volatile private var ensureAllOnNextSync = true

    /** Last time the user directory was refetched because an unknown member/sender appeared. */
    @Volatile private var lastUsersRefreshAt = 0L

    /**
     * A member or sender we have no profile for (someone who just joined): refetch the user
     * directory once, throttled, so names appear instead of "Someone".
     */
    private fun refreshUsersIfUnknown(userIds: Collection<String>) {
        val s = scope ?: return
        s.launch {
            try {
                if (userIds.all { db.users().get(it) != null }) return@launch
                val now = System.currentTimeMillis()
                if (now - lastUsersRefreshAt < 10_000L) return@launch
                lastUsersRefreshAt = now
                db.users().upsertAll(api.users().map { it.toEntity() })
                // system messages ("X joined") and previews are derived from names: recompute them
                for (convId in db.conversations().ids()) ingest.refreshDerived(convId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                countError("users.refresh", e)
            }
        }
    }

    // ---------- lifecycle ----------

    override fun start() {
        synchronized(lifecycleLock) {
            if (scope == null) {
                val s = CoroutineScope(SupervisorJob() + ioDispatcher)
                scope = s
                ensureAllOnNextSync = true
                s.launch {
                    realtime.state.map { it is ConnectionState.Connected }.distinctUntilChanged().filter { it }.collect { onConnected() }
                }
                s.launch {
                    realtime.events.collect { event ->
                        try {
                            apply(event)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            countError("event", e)
                        }
                    }
                }
                s.launch { for (work in keyQueue) runKeyWork(work) }
                s.launch {
                    keyManager.keyArrivals.collect { convId ->
                        try {
                            ingest.redecrypt(convId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            countError("redecrypt", e)
                        }
                    }
                }
            }
        }
        realtime.connect()
    }

    override fun stop() {
        realtime.disconnect()
        synchronized(lifecycleLock) {
            scope?.cancel()
            scope = null
            queuedEnsure.clear()
        }
    }

    val isStarted: Boolean get() = synchronized(lifecycleLock) { scope != null }

    private suspend fun onConnected() {
        try {
            syncNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // already counted
        }
        try {
            messages()?.retryOutbox()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            countError("outbox", e)
        }
    }

    // ---------- reconciliation ----------

    /** Single-flight: concurrent callers await the run in progress instead of starting another. */
    override suspend fun syncNow() {
        val deferred: Deferred<Unit>
        val owner: Boolean
        synchronized(syncLock) {
            val current = inFlight
            if (current != null && current.isActive) {
                deferred = current
                owner = false
            } else {
                deferred = appScope.async(ioDispatcher, start = CoroutineStart.LAZY) { runSync() }
                inFlight = deferred
                owner = true
            }
        }
        if (owner) deferred.start()
        deferred.await()
    }

    private suspend fun runSync() {
        val cfg = serverConfig.snapshot()
        if (!cfg.isEnrolled || !hasSession()) return
        val me = cfg.userId
        try {
            db.users().upsertAll(api.users().map { it.toEntity() })

            val convs = api.conversations()
            val removed = SyncRules.removedConversationIds(db.conversations().ids(), convs.map { it.convId })
            val keyAttention = ArrayList<String>()
            db.withTransaction {
                for (c in convs) {
                    val existing = db.conversations().get(c.convId)
                    val localMemberIds = if (existing == null) emptyList() else db.members().members(c.convId).map { it.userId }
                    if (SyncRules.needsKeyAttention(c, existing, localMemberIds)) keyAttention += c.convId
                    db.conversations().upsert(SyncRules.mergeConversation(c, existing, me))
                    db.members().replaceForConversation(c.convId, c.members.map { it.toEntity(c.convId) })
                }
                for (id in removed) removeConversationLocally(id)
            }

            var failed = 0
            for (c in convs) {
                try {
                    syncMessages(c.convId, c.lastMsgId)
                } catch (e: ApiException) {
                    if (e.isUnauthorized) throw e
                    failed++
                }
            }
            if (failed > 0) countError("messages($failed)", null)

            for (cc in db.messages().undecryptableCounts()) ingest.redecrypt(cc.convId)
            for (c in convs) ingest.refreshDerived(c.convId)

            serverConfig.setLastSyncAt(Time.nowMs())
            keyAttention.forEach { enqueueEnsure(it, forced = true) }
            if (ensureAllOnNextSync) {
                // Throttled inside the manager (one GET key-recipients per conversation per minute):
                // wraps the current key for member devices that still lack it (e.g. a phone that
                // joined while we were offline) and mints one where none exists.
                ensureAllOnNextSync = false
                for (c in convs) if (c.convId !in keyAttention) enqueueEnsure(c.convId, forced = false)
            }
        } catch (e: ApiException) {
            if (e.isUnauthorized) _authExpired.tryEmit(Unit)
            countError("sync", e)
            throw e
        }
    }

    /** Pull what the server has beyond our local latest for one conversation. */
    private suspend fun syncMessages(convId: String, serverLastMsgId: String?) {
        val local = db.messages().latestMsgId(convId)
        when (val plan = SyncRules.planMessageSync(serverLastMsgId, local)) {
            SyncRules.MessagePlan.None -> Unit
            is SyncRules.MessagePlan.Initial -> {
                val page = api.messages(convId, limit = plan.limit)
                afterStore(ingest.store(page.items))
            }
            is SyncRules.MessagePlan.After -> {
                var cursor = plan.cursor
                var guard = 0
                while (guard++ < MAX_PAGES) {
                    val page = api.messages(convId, after = cursor, limit = plan.limit)
                    afterStore(ingest.store(page.items))
                    cursor = page.items.lastOrNull()?.msgId ?: break
                    if (!page.hasMore) break
                }
            }
        }
    }

    /** After storing rows: ask for keys we evidently lack (once per key epoch). */
    private fun afterStore(stored: List<MessageIngest.Stored>) {
        for (s in stored) {
            val e = s.entity
            if (e.status != MessageStatus.UNDECRYPTABLE) continue
            val keyId = MessageRows.pendingKeyId(e, json) ?: continue
            enqueueFetch(e.convId, keyId)
        }
    }

    private suspend fun removeConversationLocally(convId: String) {
        db.messages().deleteForConversation(convId)
        db.members().deleteForConversation(convId)
        db.convKeys().deleteForConversation(convId)
        db.conversations().delete(convId)
        notifier.cancelMessageNotification(convId)
    }

    // ---------- live events ----------

    private suspend fun apply(event: ServerEvent) {
        when (event) {
            is ServerEvent.MessageNew -> onMessageNew(event.message)
            is ServerEvent.MessageDeleted -> {
                db.messages().markDeleted(event.msgId, event.deletedAt)
                ingest.refreshDerived(event.convId)
            }
            is ServerEvent.ConversationUpdated -> onConversationUpdated(event.conversation)
            is ServerEvent.ConversationKeys -> enqueueFetch(event.convId, event.keyId, force = true)
            is ServerEvent.ConversationRead -> onRead(event)
            is ServerEvent.Typing -> if (event.userId != myUserId()) TypingTracker.onTyping(event.convId, event.userId)
            is ServerEvent.UserUpdated -> {
                db.users().upsert(event.user.toEntity())
                if (event.user.userId == myUserId()) serverConfig.setDisplayName(event.user.displayName)
            }
            is ServerEvent.Error -> if (event.code == ErrorCode.UNAUTHORIZED) _authExpired.tryEmit(Unit)
            // hello/pong are handled by the client; call.* belong to CallManager; unknown types are ignored.
            else -> Unit
        }
    }

    private suspend fun onMessageNew(m: Message) {
        val me = myUserId()
        if (db.conversations().get(m.convId) == null) {
            // A conversation we have never seen (e.g. created while offline): fetch its metadata first.
            runCatching { api.conversation(m.convId) }.getOrNull()?.let { upsertConversation(it) }
        }
        refreshUsersIfUnknown(listOf(m.senderId))
        val stored = ingest.store(listOf(m)).firstOrNull() ?: return
        afterStore(stored.let(::listOf))
        val e = stored.entity
        val mine = e.senderId == me
        if (!mine) TypingTracker.onMessageFrom(e.convId, e.senderId)
        val readable = e.kind != MessageKind.E2E || e.status == MessageStatus.SENT
        val decision = SyncRules.decideIncoming(
            isMine = mine,
            alreadyKnown = !stored.isNew,
            chatOpen = ChatOpenTracker.isOpen(e.convId),
            appVisible = AppVisibility.isVisible,
            decrypted = readable && e.deletedAt == null,
        )
        if (decision.markRead) {
            val repo = messages()
            if (repo != null) repo.markRead(e.convId) else db.conversations().setMyLastRead(e.convId, e.msgId)
        }
        ingest.refreshDerived(e.convId)
        if (decision.notify) notifyMessage(e)
    }

    private suspend fun notifyMessage(e: MessageEntity) {
        val conv = db.conversations().get(e.convId) ?: return
        val sender = db.users().get(e.senderId)
        val isGroup = conv.type == ConversationType.GROUP
        val title = if (isGroup) conv.name?.takeIf { it.isNotBlank() } ?: labels.unknownUser else sender?.displayName ?: labels.unknownUser
        val text = SyncRules.notificationText(e, isGroup, sender?.displayName, labels)
        notifier.showMessageNotification(e.convId, title, text, conv.unreadCount.coerceAtLeast(1))
    }

    private suspend fun onConversationUpdated(c: Conversation) {
        val me = myUserId()
        if (me != null && c.members.isNotEmpty() && c.members.none { it.userId == me }) {
            db.withTransaction { removeConversationLocally(c.convId) }
            return
        }
        upsertConversation(c)
        refreshUsersIfUnknown(c.members.map { it.userId })
        // Membership/system messages may already be ahead of what we hold.
        scope?.launch {
            try {
                syncMessages(c.convId, c.lastMsgId)
                ingest.refreshDerived(c.convId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                countError("conv.messages", e)
            }
        }
    }

    /** Upsert conversation + members preserving local derived columns; queue key work when needed. */
    private suspend fun upsertConversation(c: Conversation) {
        val me = myUserId()
        val existing = db.conversations().get(c.convId)
        val localMemberIds = if (existing == null) emptyList() else db.members().members(c.convId).map { it.userId }
        db.withTransaction {
            db.conversations().upsert(SyncRules.mergeConversation(c, existing, me))
            db.members().replaceForConversation(c.convId, c.members.map { it.toEntity(c.convId) })
        }
        ingest.refreshDerived(c.convId)
        // Epoch/rotation changes AND membership changes (a new member's devices need the key wrapped for them).
        if (SyncRules.needsKeyAttention(c, existing, localMemberIds)) enqueueEnsure(c.convId, forced = true)
    }

    private suspend fun onRead(event: ServerEvent.ConversationRead) {
        db.members().setLastRead(event.convId, event.userId, event.msgId)
        if (event.userId == myUserId()) {
            // Read on another of my devices: catch up the local unread count.
            val conv = db.conversations().get(event.convId) ?: return
            db.conversations().setMyLastReadMsgId(event.convId, SyncRules.maxOfNullable(conv.myLastReadMsgId, event.msgId))
            ingest.refreshDerived(event.convId)
            if (ingest.unreadCount(event.convId) == 0) notifier.cancelMessageNotification(event.convId)
        }
    }

    // ---------- key work (sequential) ----------

    private fun enqueueEnsure(convId: String, forced: Boolean) {
        synchronized(lifecycleLock) {
            if (scope == null) return
            if (!queuedEnsure.add(convId)) return
        }
        keyQueue.trySend(KeyWork.Ensure(convId, forced))
    }

    private fun enqueueFetch(convId: String, keyId: String, force: Boolean = false) {
        synchronized(lifecycleLock) {
            if (scope == null) return
            if (!force && !requestedKeys.add("$convId/$keyId")) return
        }
        keyQueue.trySend(KeyWork.Fetch(convId, keyId))
    }

    private suspend fun runKeyWork(work: KeyWork) {
        try {
            when (work) {
                is KeyWork.Ensure -> {
                    synchronized(lifecycleLock) { queuedEnsure.remove(work.convId) }
                    // Forced (bypasses the manager's 60 s throttle) when the epoch/membership
                    // changed, rotation is pending, or no key exists yet; throttled for the
                    // once-per-start sweep.
                    if (work.forced) keyManager.onConversationUpdated(work.convId) else keyManager.ensureKeys(work.convId)
                }
                is KeyWork.Fetch -> keyManager.onKeysEvent(work.convId, work.keyId)
            }
            ingest.redecrypt(work.convId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.isUnauthorized) _authExpired.tryEmit(Unit)
            countError("keys", e)
        } catch (e: Exception) {
            countError("keys", e)
        }
    }

    // ---------- helpers ----------

    private fun myUserId(): String? = serverConfig.current.value.userId

    private fun countError(stage: String, e: Exception?) {
        val n = errors.incrementAndGet()
        val code = when (e) {
            is ApiException -> "${e.status} ${e.code}"
            null -> ""
            else -> e.javaClass.simpleName
        }
        Log.w(TAG, "sync error #$n at $stage $code")
    }

    companion object {
        private const val TAG = "SyncEngine"
        private const val MAX_PAGES = 50
    }
}
