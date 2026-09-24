package com.guftugu.app.e2ee

import com.guftugu.app.core.crypto.Ecies
import com.guftugu.app.core.util.Base64Url
import com.guftugu.app.protocol.Attachment
import com.guftugu.app.protocol.Content
import com.guftugu.app.protocol.ContentType
import com.guftugu.app.protocol.ProtocolJson
import com.guftugu.app.protocol.PublicDevice
import com.guftugu.app.protocol.Signal
import com.guftugu.app.protocol.WrappedKey
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM coverage of the codec the key manager delegates to (real P-256 keys, no Android). */
class E2eeCodecTest {
    private val json = ProtocolJson
    private val convKey = Ecies.newConversationKey()

    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun signer(kp: KeyPair): Signature = Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private) }

    private fun assertReason(reason: E2eeException.Reason, block: () -> Unit) {
        val e = assertThrows(E2eeException::class.java) { block() }
        assertEquals(reason, e.reason)
    }

    // ---------- AAD ----------

    @Test
    fun `content aad is convId senderId clientId and null clientId means empty string`() {
        assertEquals("c_1:u_1:cl_1", String(E2eeCodec.contentAad("c_1", "u_1", "cl_1")))
        assertEquals("c_1:u_1:", String(E2eeCodec.contentAad("c_1", "u_1", null)))
        assertArrayEquals(E2eeCodec.contentAad("c_1", "u_1", ""), E2eeCodec.contentAad("c_1", "u_1", null))
    }

    @Test
    fun `signal aad is convId callId signal`() {
        assertEquals("c_1:k_9:signal", String(E2eeCodec.signalAad("c_1", "k_9")))
    }

    // ---------- content ----------

    @Test
    fun `content round trip with attachment metadata`() {
        val content = Content(
            type = ContentType.IMAGE,
            text = "caption ✓ گفتگو",
            attachment = Attachment(
                key = "conv/c_1/a.bin", mime = "image/jpeg", sizeBytes = 1234, width = 640, height = 480,
                fileKey = Base64Url.encode(ByteArray(32) { 7 }), baseIv = Base64Url.encode(ByteArray(8) { 1 }),
                sha256 = Base64Url.encode(ByteArray(32)), thumbKey = "conv/c_1/t.bin",
            ),
            replyTo = "m_5",
        )
        val aad = E2eeCodec.contentAad("c_1", "u_me", "cl_1")
        val env = E2eeCodec.encryptContent(json, convKey, "x_1", content, aad)
        assertEquals(1, env.v)
        assertEquals("x_1", env.keyId)
        assertEquals(12, Base64Url.decode(env.iv).size)
        assertEquals(content, E2eeCodec.decryptContent(json, convKey, env, aad))
    }

    @Test
    fun `content rejects any change to conversation sender or client id`() {
        val aad = E2eeCodec.contentAad("c_1", "u_1", "cl_1")
        val env = E2eeCodec.encryptContent(json, convKey, "x_1", Content.text("hi"), aad)
        for (bad in listOf(
            E2eeCodec.contentAad("c_2", "u_1", "cl_1"),
            E2eeCodec.contentAad("c_1", "u_2", "cl_1"),
            E2eeCodec.contentAad("c_1", "u_1", "cl_2"),
            E2eeCodec.contentAad("c_1", "u_1", null),
        )) {
            assertReason(E2eeException.Reason.AUTHENTICATION_FAILED) { E2eeCodec.decryptContent(json, convKey, env, bad) }
        }
    }

    @Test
    fun `wrong key tampered ciphertext and malformed envelope fail with a typed exception`() {
        val aad = E2eeCodec.contentAad("c_1", "u_1", "cl_1")
        val env = E2eeCodec.encryptContent(json, convKey, "x_1", Content.text("hi"), aad)
        assertReason(E2eeException.Reason.AUTHENTICATION_FAILED) { E2eeCodec.decryptContent(json, Ecies.newConversationKey(), env, aad) }
        val ct = Base64Url.decode(env.ct).also { it[3] = (it[3].toInt() xor 0x40).toByte() }
        assertReason(E2eeException.Reason.AUTHENTICATION_FAILED) { E2eeCodec.decryptContent(json, convKey, env.copy(ct = Base64Url.encode(ct)), aad) }
        assertReason(E2eeException.Reason.AUTHENTICATION_FAILED) { E2eeCodec.decryptContent(json, convKey, env.copy(iv = "!!!"), aad) }
        assertReason(E2eeException.Reason.AUTHENTICATION_FAILED) { E2eeCodec.decryptContent(json, convKey, env.copy(v = 2), aad) }
    }

    @Test
    fun `plaintext that is not content json is MALFORMED not a crash`() {
        val aad = E2eeCodec.contentAad("c_1", "u_1", "cl_1")
        val env = com.guftugu.app.core.crypto.ContentCipher.encrypt(convKey, "x_1", "{\"nope\":true}", aad)
        assertReason(E2eeException.Reason.MALFORMED) { E2eeCodec.decryptContent(json, convKey, env, aad) }
    }

    @Test
    fun `oversized content is refused before it reaches the server`() {
        val aad = E2eeCodec.contentAad("c_1", "u_1", "cl_1")
        val big = Content.text("x".repeat(64 * 1024))
        assertReason(E2eeException.Reason.TOO_LARGE) { E2eeCodec.encryptContent(json, convKey, "x_1", big, aad) }
        // just under the limit is fine
        val ok = Content.text("x".repeat(40 * 1024))
        assertNotNull(E2eeCodec.encryptContent(json, convKey, "x_1", ok, aad))
    }

    // ---------- signals ----------

    @Test
    fun `signal round trip for every kind and the call id is bound`() {
        val aad = E2eeCodec.signalAad("c_1", "k_1")
        val signals = listOf<Signal>(
            Signal.Offer("v=0\r\no=- 1 1 IN IP4 0.0.0.0"),
            Signal.Answer("v=0"),
            Signal.Ice("candidate:1 1 udp 2 10.0.0.1 5000 typ host", "0", 0),
        )
        for (s in signals) {
            val env = E2eeCodec.encryptSignal(json, convKey, "x_1", s, aad)
            assertEquals(s, E2eeCodec.decryptSignal(json, convKey, env, aad))
            assertReason(E2eeException.Reason.AUTHENTICATION_FAILED) { E2eeCodec.decryptSignal(json, convKey, env, E2eeCodec.signalAad("c_1", "k_2")) }
            // a signal envelope can never be replayed as a message and vice versa
            assertReason(E2eeException.Reason.AUTHENTICATION_FAILED) { E2eeCodec.decryptContent(json, convKey, env, E2eeCodec.contentAad("c_1", "u_1", "k_1")) }
        }
    }

    @Test
    fun `signal wire shape uses the kind discriminator`() {
        val aad = E2eeCodec.signalAad("c_1", "k_1")
        val env = E2eeCodec.encryptSignal(json, convKey, "x_1", Signal.Answer("sdp"), aad)
        val plain = com.guftugu.app.core.crypto.ContentCipher.decrypt(convKey, env, aad)
        assertTrue(plain, plain.contains("\"kind\":\"answer\""))
        assertTrue(plain, plain.contains("\"sdp\":\"sdp\""))
    }

    // ---------- wrapping / importing ----------

    private class Dev(val id: String, val user: String) {
        val device: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val ecdh: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val public = PublicDevice(
            deviceId = id, userId = user, name = id,
            devicePublicKey = Base64Url.encode(device.public.encoded),
            encryptionPublicKey = Base64Url.encode(ecdh.public.encoded),
            enrolledAt = 1_758_500_000_000L,
        )
    }

    private val me = Dev("d_me", "u_me")
    private val ammi = Dev("d_ammi", "u_ammi")
    private val abbu = Dev("d_abbu", "u_abbu")

    @Test
    fun `wrap for devices produces one verified wrap per distinct device including myself`() {
        val devices = listOf(me.public, ammi.public, abbu.public, ammi.public)
        val wraps = E2eeCodec.wrapForDevices(convKey, "c_1", "x_1", devices, me.id, signer(me.device), 1_758_500_000_000L)
        assertEquals(3, wraps.size)
        assertEquals(listOf("d_me", "d_ammi", "d_abbu"), wraps.map { it.recipientDeviceId })
        assertTrue(wraps.all { it.senderDeviceId == me.id && it.convId == "c_1" && it.keyId == "x_1" && it.createdAt == 1_758_500_000_000L })
        // each recipient can unwrap its own copy with the sender's device key
        for ((wrap, dev) in wraps.zip(listOf(me, ammi, abbu))) {
            val raw = Ecies.unwrapConversationKey(wrap, dev.ecdh.private, me.device.public.encoded, dev.ecdh.public.encoded)
            assertArrayEquals(convKey, raw)
        }
    }

    @Test
    fun `wrap skips a device whose encryption key is garbage instead of failing everyone`() {
        val broken = ammi.public.copy(encryptionPublicKey = Base64Url.encode(byteArrayOf(1, 2, 3)))
        val wraps = E2eeCodec.wrapForDevices(convKey, "c_1", "x_1", listOf(broken, abbu.public), me.id, signer(me.device))
        assertEquals(listOf("d_abbu"), wraps.map { it.recipientDeviceId })
    }

    private fun senderLookup(vararg devs: Dev): (String) -> ByteArray? {
        val map = devs.associate { it.id to it.device.public.encoded }
        return { map[it] }
    }

    @Test
    fun `import takes only wraps addressed to me and verifies the signature first`() {
        val fromAmmi = E2eeCodec.wrapForDevices(convKey, "c_1", "x_1", listOf(me.public, abbu.public), ammi.id, signer(ammi.device))
        val result = E2eeCodec.importWraps(
            convId = "c_1", items = fromAmmi, myDeviceId = me.id,
            myEcdhPrivate = me.ecdh.private, myEcdhPublicSpki = me.ecdh.public.encoded,
            senderPublicKey = senderLookup(me, ammi, abbu), alreadyHeld = { false },
        )
        assertEquals(setOf("x_1"), result.imported.keys)
        assertArrayEquals(convKey, result.imported["x_1"])
        assertEquals(1, result.skipped) // abbu's copy
        assertEquals(0, result.refused)
    }

    @Test
    fun `import refuses a sender that is not a member device`() {
        val stranger = Dev("d_stranger", "u_x")
        val wraps = E2eeCodec.wrapForDevices(convKey, "c_1", "x_1", listOf(me.public), stranger.id, signer(stranger.device))
        val result = E2eeCodec.importWraps(
            "c_1", wraps, me.id, me.ecdh.private, me.ecdh.public.encoded, senderLookup(me, ammi, abbu),
        ) { false }
        assertTrue(result.imported.isEmpty())
        assertEquals(1, result.refused)
        assertEquals(1, result.unknownSenders)
    }

    @Test
    fun `import refuses a wrap whose signature does not match the claimed sender`() {
        // signed by a stranger but claiming to be ammi (a malicious server could try this)
        val stranger = Dev("d_stranger", "u_x")
        val forged = E2eeCodec.wrapForDevices(convKey, "c_1", "x_1", listOf(me.public), ammi.id, signer(stranger.device))
        assertEquals(ammi.id, forged.single().senderDeviceId)
        val result = E2eeCodec.importWraps(
            "c_1", forged, me.id, me.ecdh.private, me.ecdh.public.encoded, senderLookup(me, ammi, abbu),
        ) { false }
        assertTrue(result.imported.isEmpty())
        assertEquals(1, result.refused)
        assertEquals(0, result.unknownSenders)
    }

    @Test
    fun `import refuses wraps for another conversation and skips keys already held`() {
        val other = E2eeCodec.wrapForDevices(convKey, "c_2", "x_1", listOf(me.public), ammi.id, signer(ammi.device))
        val held = E2eeCodec.wrapForDevices(convKey, "c_1", "x_0", listOf(me.public), ammi.id, signer(ammi.device))
        val fresh = E2eeCodec.wrapForDevices(Ecies.newConversationKey(), "c_1", "x_2", listOf(me.public), ammi.id, signer(ammi.device))
        val result = E2eeCodec.importWraps(
            "c_1", other + held + fresh, me.id, me.ecdh.private, me.ecdh.public.encoded, senderLookup(me, ammi),
        ) { it == "x_0" }
        assertEquals(setOf("x_2"), result.imported.keys)
        assertEquals(1, result.refused)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `import keeps the first valid wrap per key id`() {
        val k = Ecies.newConversationKey()
        val a = E2eeCodec.wrapForDevices(k, "c_1", "x_3", listOf(me.public), ammi.id, signer(ammi.device))
        val b = E2eeCodec.wrapForDevices(k, "c_1", "x_3", listOf(me.public), abbu.id, signer(abbu.device))
        val result = E2eeCodec.importWraps(
            "c_1", a + b, me.id, me.ecdh.private, me.ecdh.public.encoded, senderLookup(ammi, abbu),
        ) { false }
        assertEquals(1, result.imported.size)
        assertArrayEquals(k, result.imported["x_3"])
        assertEquals(1, result.skipped)
    }

    @Test
    fun `a wrap for a different recipient never unwraps with my key`() {
        val wraps: List<WrappedKey> = E2eeCodec.wrapForDevices(convKey, "c_1", "x_1", listOf(ammi.public), me.id, signer(me.device))
        // relabelled by a hostile server as addressed to me: signature and AAD both break
        val relabelled = wraps.single().copy(recipientDeviceId = me.id)
        val result = E2eeCodec.importWraps(
            "c_1", listOf(relabelled), me.id, me.ecdh.private, me.ecdh.public.encoded, senderLookup(me),
        ) { false }
        assertNull(result.imported["x_1"])
        assertFalse(result.imported.containsKey("x_1"))
        assertEquals(1, result.refused)
    }
}
