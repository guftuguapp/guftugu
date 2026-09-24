package com.guftugu.app.data.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.room.withTransaction
import com.guftugu.app.core.util.Time
import com.guftugu.app.core.util.Ulid
import com.guftugu.app.data.api.ApiException
import com.guftugu.app.data.api.GuftuguApi
import com.guftugu.app.data.db.GuftuguDb
import com.guftugu.app.data.db.MessageEntity
import com.guftugu.app.data.db.MessageStatus
import com.guftugu.app.data.db.OutboxEntity
import com.guftugu.app.data.prefs.ServerConfigStore
import com.guftugu.app.data.sync.MessageIngest
import com.guftugu.app.data.sync.OutboxPolicy
import com.guftugu.app.data.sync.PreviewLabels
import com.guftugu.app.data.sync.ReadReceiptCoalescer
import com.guftugu.app.data.sync.SyncRules
import com.guftugu.app.data.ws.RealtimeClient
import com.guftugu.app.domain.Message
import com.guftugu.app.domain.toDomain
import com.guftugu.app.e2ee.ConversationKeyManager
import com.guftugu.app.notifications.Notifier
import com.guftugu.app.protocol.Attachment
import com.guftugu.app.protocol.ClientEvent
import com.guftugu.app.protocol.Content
import com.guftugu.app.protocol.ContentType
import com.guftugu.app.protocol.MessageKind
import com.guftugu.app.protocol.ProtocolJson
import com.guftugu.app.protocol.SendMessageRequest
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Messages (PROTOCOL §8), offline-first.
 *
 * - [messages]: Room → domain over a growing window (latest N rows; [loadOlder] widens it and
 *   fetches `?before=` from the server only when the local history runs out).
 * - Sending: an [OutboxEntity] plus a PENDING [MessageEntity] with the same `clientId` are written
 *   in one transaction so the bubble appears instantly; a background job uploads (attachments),
 *   encrypts, POSTs, and swaps the pending row for the server message. Failures keep the row
 *   PENDING with backoff 1 s → 4 s → 16 s, then wait for the next connect ([retryOutbox]);
 *   permanent rejections become FAILED. Duplicates are impossible: `clientId` is the server's
 *   idempotency key and the pending row is replaced by whichever arrives first (POST reply or
 *   `message.new`).
 * - Pending attachment rows carry a placeholder [Attachment] whose `key` is the local `content://`
 *   uri and whose `fileKey` is empty — the UI renders it straight from the uri while
 *   `status == PENDING` and must not hand it to `MediaRepository.openAttachment`.
 * - [markRead] updates Room at once and coalesces `PUT …/read` to one per conversation per 5 s.
 */
class MessageRepositoryImpl(
    private val context: Context,
    private val api: GuftuguApi,
    private val db: GuftuguDb,
    private val keyManager: ConversationKeyManager,
    private val mediaRepository: MediaRepository,
    private val serverConfig: ServerConfigStore,
    private val realtime: RealtimeClient,
    private val appScope: CoroutineScope,
    private val json: Json = ProtocolJson,
    private val notifier: Notifier? = null,
    private val labels: PreviewLabels = PreviewLabels(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = Time::nowMs,
) : MessageRepository {

    private val ingest = MessageIngest(db, keyManager, json, labels) { myUserId() }
    private val appContext = context.applicationContext

    private val windows = ConcurrentHashMap<String, MutableStateFlow<Int>>()
    private val olderExhausted = ConcurrentHashMap.newKeySet<String>()
    private val olderLocks = ConcurrentHashMap<String, Mutex>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val readStates = ConcurrentHashMap<String, ReadReceiptCoalescer>()
    private val typingSentAt = ConcurrentHashMap<String, Long>()

    // ---------- reading ----------

    override fun messages(convId: String): Flow<List<Message>> {
        val window = windowOf(convId)
        val rows = window.flatMapLatest { limit -> db.messages().observeLatest(convId, limit) }
        val me = serverConfig.config.map { it.userId }.distinctUntilChanged()
        return combine(rows, me) { list, myId ->
            val out = ArrayList<Message>(list.size)
            for (i in list.indices.reversed()) out += list[i].toDomain(myId) // DESC → ascending
            out
        }.distinctUntilChanged()
    }

    override suspend fun loadOlder(convId: String) {
        val lock = olderLocks.getOrPut(convId) { Mutex() }
        if (!lock.tryLock()) return
        try {
            val window = windowOf(convId)
            val target = window.value + SyncRules.OLDER_LIMIT
            val synced = db.messages().countSynced(convId)
            if (synced < target && convId !in olderExhausted) {
                val oldest = db.messages().oldestMsgId(convId)
                val page = if (oldest == null) {
                    api.messages(convId, limit = SyncRules.INITIAL_LIMIT)
                } else {
                    api.messages(convId, before = oldest, limit = SyncRules.OLDER_LIMIT)
                }
                ingest.store(page.items)
                if (!page.hasMore) olderExhausted += convId
                if (oldest == null) ingest.refreshDerived(convId)
            }
            window.value = target
        } finally {
            lock.unlock()
        }
    }

    // ---------- sending ----------

    override suspend fun sendText(convId: String, text: String, replyTo: String?) {
        val body = text.trim()
        if (body.isEmpty()) return
        enqueue(convId, Content.text(body, replyTo), localUri = null)
    }

    override suspend fun sendAttachment(convId: String, local: Uri, type: ContentType, caption: String?) {
        require(type != ContentType.TEXT) { "attachments need a media content type" }
        val mime = withContext(ioDispatcher) { mimeOf(local, type) }
        val placeholder = Attachment(key = local.toString(), mime = mime, sizeBytes = 0L, fileKey = "", baseIv = "", sha256 = "")
        val content = Content(type = type, text = caption?.trim()?.takeIf { it.isNotEmpty() }, attachment = placeholder)
        enqueue(convId, content, localUri = local.toString())
    }

    private suspend fun enqueue(convId: String, content: Content, localUri: String?) {
        val me = myUserId() ?: throw IllegalStateException("not enrolled")
        val clientId = Ulid.generate()
        val now = nowMs()
        // First message sent = first activity: starts the one-week passcode clock (PasscodePolicy).
        runCatching { serverConfig.markFirstActivity(now) }
        val pending = MessageEntity(
            msgId = OutboxPolicy.pendingMsgId(clientId),
            convId = convId,
            senderId = me,
            senderDeviceId = serverConfig.current.value.deviceId,
            clientId = clientId,
            kind = MessageKind.E2E,
            sentAt = now,
            createdAt = now,
            contentType = content.type.wire,
            text = content.text,
            attachmentJson = content.attachment?.let { json.encodeToString(Attachment.serializer(), it) },
            replyTo = content.replyTo,
            status = MessageStatus.PENDING,
        )
        val preview = SyncRules.previewOf(pending, mine = true, isGroup = false, senderName = null, labels = labels, json = json)
        db.withTransaction {
            db.outbox().insert(OutboxEntity(clientId = clientId, convId = convId, createdAt = now, contentJson = json.encodeToString(Content.serializer(), content), localUri = localUri))
            db.messages().upsert(pending)
            db.conversations().setLocalPreview(convId, preview, now)
            db.conversations().bumpLastMessageAt(convId, now)
        }
        appScope.launch(ioDispatcher) { process(clientId) }
    }

    override suspend fun retryOutbox() {
        val items = db.outbox().pending()
        if (items.isEmpty()) return
        for (item in items) {
            if (item.clientId in inFlight) continue
            appScope.launch(ioDispatcher) { process(item.clientId) }
        }
    }

    /** Drives one outbox item to completion (or to WAIT/FAIL). Safe to call repeatedly: single-flight per clientId. */
    internal suspend fun process(clientId: String) {
        if (!inFlight.add(clientId)) return
        try {
            while (true) {
                val item = db.outbox().get(clientId) ?: return // acknowledged meanwhile (message.new dedupe)
                val failure = try {
                    attempt(item)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
                val outcome = classify(failure)
                db.outbox().recordFailure(clientId, describe(failure))
                val attempts = item.attempts + 1
                when (outcome) {
                    OutboxPolicy.Outcome.FAIL -> {
                        db.withTransaction {
                            db.messages().setStatus(OutboxPolicy.pendingMsgId(clientId), MessageStatus.FAILED)
                            db.outbox().delete(clientId)
                        }
                        Log.w(TAG, "send failed permanently: ${describe(failure)}")
                        return
                    }
                    OutboxPolicy.Outcome.WAIT -> return
                    OutboxPolicy.Outcome.RETRY -> {
                        val wait = OutboxPolicy.backoffAfterFailure(attempts) ?: return // next connect retries
                        delay(wait)
                    }
                }
            }
        } finally {
            inFlight.remove(clientId)
        }
    }

    private suspend fun attempt(item: OutboxEntity) {
        var content = json.decodeFromString(Content.serializer(), item.contentJson)
        val pendingId = OutboxPolicy.pendingMsgId(item.clientId)
        val uriString = item.localUri
        if (uriString != null && (content.attachment == null || content.attachment.fileKey.isEmpty())) {
            val uri = Uri.parse(uriString)
            val mime = content.attachment?.mime ?: mimeOf(uri, content.type)
            val uploaded = mediaRepository.uploadEncrypted(item.convId, uri, mime)
            content = content.copy(attachment = uploaded)
            db.withTransaction {
                db.outbox().setUploaded(item.clientId, json.encodeToString(Content.serializer(), content))
                db.messages().setAttachmentJson(pendingId, json.encodeToString(Attachment.serializer(), uploaded))
            }
        }
        keyManager.ensureKeys(item.convId)
        val envelope = keyManager.encrypt(item.convId, content, item.clientId)
        val message = api.sendMessage(item.convId, SendMessageRequest(clientId = item.clientId, sentAt = item.createdAt, envelope = envelope))
        db.withTransaction {
            db.messages().delete(pendingId)
            val existing = db.messages().get(message.msgId)
            if (existing == null || existing.status != MessageStatus.SENT) {
                db.messages().upsert(ingest.withContent(ingest.baseEntity(message), content))
            }
            db.outbox().delete(item.clientId)
        }
        ingest.refreshDerived(item.convId)
    }

    private fun classify(e: Exception): OutboxPolicy.Outcome = when (e) {
        is ApiException -> OutboxPolicy.classify(e.status, e.code)
        is FileNotFoundException, is SecurityException -> OutboxPolicy.Outcome.FAIL // picker grant gone / file deleted
        is IllegalArgumentException, is IllegalStateException -> OutboxPolicy.Outcome.FAIL
        else -> OutboxPolicy.Outcome.RETRY
    }

    /** Error label for the outbox row / logs: codes and class names only, never content. */
    private fun describe(e: Exception): String = when (e) {
        is ApiException -> "${e.status} ${e.code}"
        else -> e.javaClass.simpleName
    }

    // ---------- deleting ----------

    override suspend fun delete(convId: String, msgId: String) {
        if (OutboxPolicy.isPendingMsgId(msgId)) {
            // Cancel a message that never reached the server.
            val row = db.messages().get(msgId)
            db.withTransaction {
                db.messages().delete(msgId)
                row?.clientId?.let { db.outbox().delete(it) }
            }
            ingest.refreshDerived(convId)
            return
        }
        val message = api.deleteMessage(convId, msgId)
        db.messages().markDeleted(msgId, message.deletedAt ?: nowMs())
        ingest.refreshDerived(convId)
    }

    // ---------- read receipts ----------

    override suspend fun markRead(convId: String) {
        val latest = db.messages().latestMsgId(convId)
        val me = myUserId()
        db.withTransaction {
            db.conversations().setMyLastRead(convId, SyncRules.maxOfNullable(db.conversations().get(convId)?.myLastReadMsgId, latest))
            if (latest != null && me != null) db.members().setLastRead(convId, me, latest)
        }
        notifier?.cancelMessageNotification(convId)
        if (latest == null) return
        val state = readStates.getOrPut(convId) { ReadReceiptCoalescer() }
        when (val action = state.onRead(latest, nowMs())) {
            is ReadReceiptCoalescer.Action.SendNow -> putRead(convId, action.msgId, state)
            is ReadReceiptCoalescer.Action.ScheduleIn -> appScope.launch(ioDispatcher) {
                delay(action.delayMs)
                state.onTimerFired(nowMs())?.let { putRead(convId, it, state) }
            }
            ReadReceiptCoalescer.Action.Nothing -> Unit
        }
    }

    private suspend fun putRead(convId: String, msgId: String, state: ReadReceiptCoalescer) {
        try {
            api.markRead(convId, msgId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.onSendFailed(msgId) // the next markRead retries
        }
    }

    // ---------- typing ----------

    override fun sendTyping(convId: String) {
        val now = nowMs()
        val last = typingSentAt[convId] ?: 0L
        if (now - last < OutboxPolicy.TYPING_INTERVAL_MS) return
        typingSentAt[convId] = now
        realtime.send(ClientEvent.Typing(convId))
    }

    // ---------- helpers ----------

    private fun windowOf(convId: String): MutableStateFlow<Int> = windows.getOrPut(convId) { MutableStateFlow(INITIAL_WINDOW) }

    private fun myUserId(): String? = serverConfig.current.value.userId

    private fun mimeOf(uri: Uri, type: ContentType): String {
        val resolved = when (uri.scheme) {
            "content" -> runCatching { appContext.contentResolver.getType(uri) }.getOrNull()
            else -> MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.takeIf { it.isNotEmpty() }
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
        }
        return resolved ?: when (type) {
            ContentType.IMAGE -> "image/jpeg"
            ContentType.VIDEO -> "video/mp4"
            ContentType.AUDIO -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "MessageRepo"
        /** Rows kept in the chat window before the user scrolls up. */
        const val INITIAL_WINDOW = 80
    }
}
