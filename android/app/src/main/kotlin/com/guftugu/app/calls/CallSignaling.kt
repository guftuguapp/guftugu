package com.guftugu.app.calls

import com.guftugu.app.data.repo.CallRepository
import com.guftugu.app.e2ee.ConversationKeyManager
import com.guftugu.app.protocol.Envelope
import com.guftugu.app.protocol.Signal

/**
 * Encrypted call signalling (PROTOCOL §11–§12): SDP offers/answers and ICE candidates travel as
 * envelopes under the conversation key, relayed by the server as `call.signal`.
 *
 * This is the single place that touches [ConversationKeyManager] for calls; the AAD
 * `convId:callId:signal` binding is applied by the key manager's callId-aware methods. The server never sees SDP, and therefore cannot swap DTLS fingerprints.
 */
class CallSignaling(
    private val keys: ConversationKeyManager,
    private val repo: CallRepository,
) {
    /** Encrypts and sends one signal. Returns false when encryption failed (no key) — the call must end with [CallReducer.REASON_NO_KEY]. */
    suspend fun send(convId: String, callId: String, signal: Signal): Boolean {
        val envelope = try {
            keys.encryptSignal(convId, callId, signal)
        } catch (e: Exception) {
            return false
        }
        repo.sendSignal(callId, envelope)
        return true
    }

    /** Decrypts an incoming `call.signal` envelope; null when the key is missing or the tag fails (dropped, never trusted). */
    suspend fun receive(convId: String, callId: String, envelope: Envelope): Signal? = try {
        keys.decryptSignal(convId, callId, envelope)
    } catch (e: Exception) {
        null
    }

    suspend fun hasKey(convId: String): Boolean = try {
        keys.hasCurrentKey(convId)
    } catch (e: Exception) {
        false
    }

    /** Best effort: rotate/wrap so the peer's devices can open our signals; never throws. */
    suspend fun ensureKeys(convId: String) {
        try {
            keys.ensureKeys(convId)
        } catch (e: Exception) {
            // Offline or the peer has no devices yet; the call will fail with no_key if we still lack a key.
        }
    }
}
