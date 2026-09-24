package com.guftugu.app.e2ee

import com.guftugu.app.protocol.Content
import com.guftugu.app.protocol.Envelope
import com.guftugu.app.protocol.Signal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Per-conversation key lifecycle (PROTOCOL §7) and content/signal encryption (§8, §11).
 * Keys live in Room (`conv_keys`, wrapped by `guftugu_wrap`) with an in-memory cache.
 *
 * Failure contract: every method throws only [E2eeException] (or `CancellationException`).
 * A 403/404 from the server ("not a member any more") is handled quietly and never thrown.
 */
interface ConversationKeyManager {
    /**
     * Rotate if `keyRotationRequired`, else wrap the current key for any device lacking it.
     * Cheap to call before every send: the network is consulted at most once per 60 s per
     * conversation (use it on app start, on `conversation.updated`, and before sending).
     */
    suspend fun ensureKeys(convId: String)

    /** A `conversation.keys` event arrived: fetch `/keys`, verify + unwrap, store. Emits on [keyArrivals]. */
    suspend fun onKeysEvent(convId: String, keyId: String)

    /**
     * A `conversation.updated` event arrived (members/keys changed): same as [ensureKeys] but
     * bypasses the 60 s throttle. Default keeps older implementations working.
     */
    suspend fun onConversationUpdated(convId: String) = ensureKeys(convId)

    /** Encrypt with the current key; goes to the network only when no usable key is held locally. */
    suspend fun encrypt(convId: String, content: Content, clientId: String): Envelope

    /**
     * null = key not yet available (caller stores the envelope and waits for [keyArrivals] /
     * `conversation.keys`). Throws [E2eeException] with `AUTHENTICATION_FAILED` when the key is
     * held but the envelope does not authenticate — that message will never decrypt.
     */
    suspend fun decrypt(convId: String, envelope: Envelope, senderId: String, clientId: String?): Content?

    /** Call signalling: AAD = `convId:callId:signal` (PROTOCOL §12). */
    suspend fun encryptSignal(convId: String, callId: String, signal: Signal): Envelope

    /** null = key not yet available. */
    suspend fun decryptSignal(convId: String, callId: String, envelope: Envelope): Signal?

    @Deprecated("The signalling AAD includes the callId; use encryptSignal(convId, callId, signal)", ReplaceWith("encryptSignal(convId, \"\", signal)"))
    suspend fun encryptSignal(convId: String, signal: Signal): Envelope = encryptSignal(convId, "", signal)

    @Deprecated("The signalling AAD includes the callId; use decryptSignal(convId, callId, envelope)", ReplaceWith("decryptSignal(convId, \"\", envelope)"))
    suspend fun decryptSignal(convId: String, envelope: Envelope): Signal? = decryptSignal(convId, "", envelope)

    /** Whether this device holds the conversation's current key (local knowledge only, no network). */
    suspend fun hasCurrentKey(convId: String): Boolean

    /** Drop the in-memory key cache (lock / logout). */
    fun clearCache()

    /**
     * Emits a `convId` whenever a key for that conversation was imported (from a `conversation.keys`
     * event or during [ensureKeys]). The sync engine retries `undecryptable` messages on it.
     */
    val keyArrivals: Flow<String>
        get() = emptyFlow()
}

class StubConversationKeyManager : ConversationKeyManager {
    override suspend fun ensureKeys(convId: String) = throw NotImplementedError("implemented in feature phase")
    override suspend fun onKeysEvent(convId: String, keyId: String) = throw NotImplementedError("implemented in feature phase")
    override suspend fun encrypt(convId: String, content: Content, clientId: String): Envelope = throw NotImplementedError("implemented in feature phase")
    override suspend fun decrypt(convId: String, envelope: Envelope, senderId: String, clientId: String?): Content? = throw NotImplementedError("implemented in feature phase")
    override suspend fun encryptSignal(convId: String, callId: String, signal: Signal): Envelope = throw NotImplementedError("implemented in feature phase")
    override suspend fun decryptSignal(convId: String, callId: String, envelope: Envelope): Signal? = throw NotImplementedError("implemented in feature phase")
    override suspend fun hasCurrentKey(convId: String): Boolean = false
    override fun clearCache() = Unit
}
