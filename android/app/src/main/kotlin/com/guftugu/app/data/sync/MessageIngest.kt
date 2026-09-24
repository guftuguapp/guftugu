package com.guftugu.app.data.sync

import androidx.room.withTransaction
import com.guftugu.app.data.db.GuftuguDb
import com.guftugu.app.data.db.MemberEntity
import com.guftugu.app.data.db.MessageEntity
import com.guftugu.app.data.db.MessageStatus
import com.guftugu.app.e2ee.ConversationKeyManager
import com.guftugu.app.protocol.Content
import com.guftugu.app.protocol.ConversationType
import com.guftugu.app.protocol.Envelope
import com.guftugu.app.protocol.Member
import com.guftugu.app.protocol.Message
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Turns wire [Message]s into Room rows: decrypts e2e envelopes through the key manager (keeping
 * the envelope when the key is not here yet), de-duplicates my own sends by `clientId`, and
 * recomputes the per-conversation derived columns (preview, unread count, last message).
 * Shared by [SyncEngineImpl] and `MessageRepositoryImpl`.
 */
class MessageIngest(
    private val db: GuftuguDb,
    private val keyManager: ConversationKeyManager,
    private val json: Json,
    private val labels: PreviewLabels,
    private val myUserId: () -> String?,
) {
    data class Stored(val entity: MessageEntity, val isNew: Boolean)

    /** Wire → row without touching the envelope (call/system JSON encoded, status SENT). */
    fun baseEntity(m: Message): MessageEntity = MessageRows.base(m, json)

    /** Fill the decrypted columns of [base] from [content]; drops any stored envelope. */
    fun withContent(base: MessageEntity, content: Content): MessageEntity = MessageRows.withContent(base, content, json)

    /** Decrypts when possible; a missing key (or a broken envelope) yields an UNDECRYPTABLE row that keeps the envelope. */
    suspend fun toEntity(m: Message): MessageEntity = MessageRows.fromWire(m, json) { convId, envelope, senderId, clientId ->
        keyManager.decrypt(convId, envelope, senderId, clientId)
    }

    /**
     * Store server messages. My own messages replace the PENDING row with the same `clientId`
     * (and clear its outbox entry); an already-decrypted row is never downgraded to UNDECRYPTABLE.
     * Does not touch the conversation's derived columns — call [refreshDerived] afterwards.
     */
    suspend fun store(messages: List<Message>): List<Stored> {
        if (messages.isEmpty()) return emptyList()
        val me = myUserId()
        val entities = messages.map { toEntity(it) } // crypto outside the transaction
        return db.withTransaction {
            val out = ArrayList<Stored>(entities.size)
            for (e in entities) {
                val existing = db.messages().get(e.msgId)
                if (existing == null && e.clientId != null && e.senderId == me) {
                    val pending = db.messages().getByClientId(e.clientId)
                    if (pending != null && pending.msgId != e.msgId) db.messages().delete(pending.msgId)
                    db.outbox().delete(e.clientId)
                }
                val row = MessageRows.reconcile(existing, e)
                db.messages().upsert(row)
                out += Stored(row, existing == null)
            }
            out
        }
    }

    /** Retry every UNDECRYPTABLE row of [convId] (after keys arrived). Returns how many became readable. */
    suspend fun redecrypt(convId: String): Int {
        val rows = db.messages().undecryptable(convId)
        if (rows.isEmpty()) return 0
        var n = 0
        for (row in rows) {
            val envelope = row.envelopeJson?.let { runCatching { json.decodeFromString(Envelope.serializer(), it) }.getOrNull() } ?: continue
            val content = try {
                keyManager.decrypt(convId, envelope, row.senderId, row.clientId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            db.messages().upsert(withContent(row, content))
            n++
        }
        if (n > 0) refreshDerived(convId)
        return n
    }

    /** Recompute preview, unread count and the last-message marker of [convId] from its rows. */
    suspend fun refreshDerived(convId: String) {
        val conv = db.conversations().get(convId) ?: return
        val me = myUserId()
        val latest = db.messages().latestForPreview(convId)
        val preview = latest?.let { m ->
            val mine = m.senderId == me
            val isGroup = conv.type == ConversationType.GROUP
            val senderName = if (isGroup && !mine) db.users().get(m.senderId)?.displayName else null
            SyncRules.previewOf(m, mine, isGroup, senderName, labels, json)
        }
        val unread = db.messages().countUnread(convId, me ?: "", conv.myLastReadMsgId)
        db.conversations().setDerived(convId, preview, latest?.createdAt, unread)
        if (latest != null && !OutboxPolicy.isPendingMsgId(latest.msgId)) {
            db.conversations().advanceLastMessage(convId, latest.msgId, latest.createdAt)
        }
    }

    /** Current unread count as stored (after [refreshDerived]). */
    suspend fun unreadCount(convId: String): Int = db.conversations().get(convId)?.unreadCount ?: 0
}

fun Member.toEntity(convId: String): MemberEntity =
    MemberEntity(convId = convId, userId = userId, role = role, joinedAt = joinedAt, lastReadMsgId = lastReadMsgId)
