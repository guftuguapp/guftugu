package com.guftugu.app.data.repo

import android.net.Uri
import com.guftugu.app.domain.Message
import com.guftugu.app.protocol.ContentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Messages (PROTOCOL §8). Sends go through the Room outbox and are retried with the
 * message's `clientId` as the idempotency key; the UI observes Room.
 */
interface MessageRepository {
    /** Decrypted, ascending by msgId, from Room. */
    fun messages(convId: String): Flow<List<Message>>
    suspend fun sendText(convId: String, text: String, replyTo: String? = null)
    /** Encrypt + upload + send via the outbox. */
    suspend fun sendAttachment(convId: String, local: Uri, type: ContentType, caption: String?)
    suspend fun delete(convId: String, msgId: String)
    /** Coalesced `PUT …/read` (on leaving the chat or every 5 s). */
    suspend fun markRead(convId: String)
    /** `GET …/messages?before=` for the oldest local message. */
    suspend fun loadOlder(convId: String)
    suspend fun retryOutbox()
    /** ≤ 1 per 3 s per conversation; best effort over the WebSocket. */
    fun sendTyping(convId: String)
}

class StubMessageRepository : MessageRepository {
    override fun messages(convId: String): Flow<List<Message>> = flowOf(emptyList())
    override suspend fun sendText(convId: String, text: String, replyTo: String?) = throw NotImplementedError("implemented in feature phase")
    override suspend fun sendAttachment(convId: String, local: Uri, type: ContentType, caption: String?) = throw NotImplementedError("implemented in feature phase")
    override suspend fun delete(convId: String, msgId: String) = throw NotImplementedError("implemented in feature phase")
    override suspend fun markRead(convId: String) = throw NotImplementedError("implemented in feature phase")
    override suspend fun loadOlder(convId: String) = throw NotImplementedError("implemented in feature phase")
    override suspend fun retryOutbox() = throw NotImplementedError("implemented in feature phase")
    override fun sendTyping(convId: String) = Unit
}
