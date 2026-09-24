package com.guftugu.app.data.sync

import com.guftugu.app.data.db.MessageEntity
import com.guftugu.app.data.db.MessageStatus
import com.guftugu.app.protocol.Attachment
import com.guftugu.app.protocol.CallInfo
import com.guftugu.app.protocol.Content
import com.guftugu.app.protocol.Envelope
import com.guftugu.app.protocol.Message
import com.guftugu.app.protocol.MessageKind
import com.guftugu.app.protocol.ProtocolJson
import com.guftugu.app.protocol.SystemInfo
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Wire [Message] ⇄ Room [MessageEntity] mapping, independent of Room and the key manager (testable with a fake decrypt). */
object MessageRows {

    /** Wire → row without touching the envelope (call/system JSON encoded, status SENT). */
    fun base(m: Message, json: Json = ProtocolJson): MessageEntity = MessageEntity(
        msgId = m.msgId,
        convId = m.convId,
        senderId = m.senderId,
        senderDeviceId = m.senderDeviceId,
        clientId = m.clientId,
        kind = m.kind,
        sentAt = m.sentAt,
        createdAt = m.createdAt,
        deletedAt = m.deletedAt,
        callJson = m.call?.let { json.encodeToString(CallInfo.serializer(), it) },
        systemJson = m.system?.let { json.encodeToString(SystemInfo.serializer(), it) },
        status = MessageStatus.SENT,
    )

    /** Fill the decrypted columns of [base] from [content]; drops any stored envelope. */
    fun withContent(base: MessageEntity, content: Content, json: Json = ProtocolJson): MessageEntity = base.copy(
        contentType = content.type.wire,
        text = content.text,
        attachmentJson = content.attachment?.let { json.encodeToString(Attachment.serializer(), it) },
        replyTo = content.replyTo,
        envelopeJson = null,
        status = MessageStatus.SENT,
    )

    /**
     * Decrypt through [decrypt] when possible; `null` or an exception (missing key, bad tag)
     * yields an UNDECRYPTABLE row that keeps the envelope for a later retry.
     */
    suspend fun fromWire(
        m: Message,
        json: Json = ProtocolJson,
        decrypt: suspend (convId: String, envelope: Envelope, senderId: String, clientId: String?) -> Content?,
    ): MessageEntity {
        val row = base(m, json)
        if (m.kind != MessageKind.E2E || m.deletedAt != null) return row
        val envelope = m.envelope ?: return row
        val content = try {
            decrypt(m.convId, envelope, m.senderId, m.clientId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return if (content != null) {
            withContent(row, content, json)
        } else {
            row.copy(status = MessageStatus.UNDECRYPTABLE, envelopeJson = json.encodeToString(Envelope.serializer(), envelope))
        }
    }

    /**
     * Which row wins when a server message arrives for one we already hold: a readable local copy
     * is never downgraded to UNDECRYPTABLE (e.g. after the key cache was cleared).
     */
    fun reconcile(existing: MessageEntity?, incoming: MessageEntity): MessageEntity =
        if (existing != null && existing.status == MessageStatus.SENT && incoming.status == MessageStatus.UNDECRYPTABLE && incoming.deletedAt == null) existing else incoming

    /** Key epoch an undecryptable row is waiting for. */
    fun pendingKeyId(row: MessageEntity, json: Json = ProtocolJson): String? =
        row.envelopeJson?.let { runCatching { json.decodeFromString(Envelope.serializer(), it).keyId }.getOrNull() }
}
