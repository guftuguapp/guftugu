package com.guftugu.app.calls

import com.guftugu.app.data.repo.CallRepository
import com.guftugu.app.e2ee.ConversationKeyManager
import com.guftugu.app.protocol.Call
import com.guftugu.app.protocol.Content
import com.guftugu.app.protocol.Envelope
import com.guftugu.app.protocol.Signal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignalingTest {

    /** Fake key manager: "encrypts" by tagging the envelope with conv/call ids; fails when [hasKey] is false. */
    private class FakeKeys(var hasKey: Boolean = true) : ConversationKeyManager {
        var ensureCalls = 0
        override suspend fun ensureKeys(convId: String) { ensureCalls++ }
        override suspend fun onKeysEvent(convId: String, keyId: String) = Unit
        override suspend fun encrypt(convId: String, content: Content, clientId: String): Envelope = error("unused")
        override suspend fun decrypt(convId: String, envelope: Envelope, senderId: String, clientId: String?): Content? = null
        override suspend fun encryptSignal(convId: String, callId: String, signal: Signal): Envelope {
            if (!hasKey) throw IllegalStateException("no key")
            val kind = when (signal) { is Signal.Offer -> "offer"; is Signal.Answer -> "answer"; is Signal.Ice -> "ice" }
            return Envelope(keyId = "x_1", iv = "$convId:$callId", ct = kind)
        }
        override suspend fun decryptSignal(convId: String, callId: String, envelope: Envelope): Signal? {
            if (!hasKey) return null
            if (envelope.iv != "$convId:$callId") throw IllegalStateException("aad mismatch")
            return when (envelope.ct) {
                "offer" -> Signal.Offer("sdp")
                "answer" -> Signal.Answer("sdp")
                "ice" -> Signal.Ice("cand", "0", 0)
                else -> null
            }
        }
        override suspend fun hasCurrentKey(convId: String): Boolean = hasKey
        override fun clearCache() = Unit
    }

    private class FakeRepo : CallRepository {
        val sent = ArrayList<Pair<String, Envelope>>()
        override suspend fun start(convId: String, video: Boolean): Call = error("unused")
        override suspend fun answer(callId: String): Call = error("unused")
        override suspend fun reject(callId: String): Call = error("unused")
        override suspend fun end(callId: String, reason: String): Call = error("unused")
        override suspend fun get(callId: String): Call = error("unused")
        override fun sendSignal(callId: String, envelope: Envelope) { sent += callId to envelope }
    }

    @Test
    fun `send encrypts under conv and call id and relays on the socket`() = runTest {
        val keys = FakeKeys()
        val repo = FakeRepo()
        val signaling = CallSignaling(keys, repo)

        assertTrue(signaling.send("c_1", "k_1", Signal.Offer("sdp")))
        assertEquals(1, repo.sent.size)
        val (callId, envelope) = repo.sent.single()
        assertEquals("k_1", callId)
        assertEquals("c_1:k_1", envelope.iv)
        assertEquals("offer", envelope.ct)
    }

    @Test
    fun `send fails softly without a key and sends nothing`() = runTest {
        val repo = FakeRepo()
        val signaling = CallSignaling(FakeKeys(hasKey = false), repo)
        assertFalse(signaling.send("c_1", "k_1", Signal.Ice("cand", "0", 0)))
        assertTrue(repo.sent.isEmpty())
    }

    @Test
    fun `receive decrypts with the same binding and drops anything it cannot open`() = runTest {
        val signaling = CallSignaling(FakeKeys(), FakeRepo())
        val ok = signaling.receive("c_1", "k_1", Envelope(keyId = "x_1", iv = "c_1:k_1", ct = "answer"))
        assertEquals(Signal.Answer("sdp"), ok)
        // wrong call id → AAD mismatch → dropped, never thrown into the state machine
        assertNull(signaling.receive("c_1", "k_2", Envelope(keyId = "x_1", iv = "c_1:k_1", ct = "answer")))
        // missing key → null
        assertNull(CallSignaling(FakeKeys(hasKey = false), FakeRepo()).receive("c_1", "k_1", Envelope(keyId = "x_1", iv = "c_1:k_1", ct = "ice")))
    }

    @Test
    fun `hasKey and ensureKeys never throw`() = runTest {
        val keys = FakeKeys(hasKey = false)
        val signaling = CallSignaling(keys, FakeRepo())
        assertFalse(signaling.hasKey("c_1"))
        signaling.ensureKeys("c_1")
        assertEquals(1, keys.ensureCalls)
    }
}
