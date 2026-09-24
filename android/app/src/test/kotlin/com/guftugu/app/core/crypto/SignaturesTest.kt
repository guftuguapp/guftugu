package com.guftugu.app.core.crypto

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignaturesTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test
    fun `der to p1363 and back round trips and verifies`() {
        // many iterations so that r/s with leading zero bytes and high bits are covered
        repeat(64) { i ->
            val data = "guftugu-auth:v1:d_test:$i".toByteArray()
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(keyPair.private); update(data); sign() }
            val p1363 = Signatures.derToP1363(der)
            assertEquals(64, p1363.size)
            val der2 = Signatures.p1363ToDer(p1363)
            assertArrayEquals(der, der2)
            assertTrue(Signatures.verifyP256(keyPair.public.encoded, data, p1363))
            assertTrue("DER also accepted", Signatures.verifyP256(keyPair.public.encoded, data, der))
        }
    }

    @Test
    fun `signP256 produces a verifiable p1363 signature`() {
        val data = Signatures.authMessage("d_01", "nonce")
        val sig = Signatures.signP256(Signature.getInstance("SHA256withECDSA").apply { initSign(keyPair.private) }, data)
        assertEquals(64, sig.size)
        assertTrue(Signatures.verifyP256(keyPair.public.encoded, data, sig))
        // tampered
        sig[10] = (sig[10].toInt() xor 0x01).toByte()
        assertFalse(Signatures.verifyP256(keyPair.public.encoded, data, sig))
        // wrong data
        sig[10] = (sig[10].toInt() xor 0x01).toByte()
        assertFalse(Signatures.verifyP256(keyPair.public.encoded, "other".toByteArray(), sig))
    }

    @Test
    fun `p1363 with leading zeros encodes minimal der`() {
        val r = ByteArray(32).also { it[31] = 1 }               // r = 1
        val s = ByteArray(32).also { it[0] = 0x80.toByte() }     // s has the top bit set → 0x00 prefix
        val der = Signatures.p1363ToDer(r + s)
        // 30 len 02 01 01 02 21 00 80 ...
        assertEquals(0x30, der[0].toInt() and 0xFF)
        assertEquals(0x02, der[2].toInt() and 0xFF)
        assertEquals(0x01, der[3].toInt() and 0xFF)
        assertEquals(0x01, der[4].toInt() and 0xFF)
        assertEquals(0x02, der[5].toInt() and 0xFF)
        assertEquals(0x21, der[6].toInt() and 0xFF)
        assertEquals(0x00, der[7].toInt() and 0xFF)
        assertArrayEquals(r + s, Signatures.derToP1363(der))
    }

    @Test
    fun `spki round trip`() {
        val spki = Signatures.encodeSpki(keyPair.public)
        val decoded = Signatures.decodeSpki(spki)
        assertArrayEquals(spki, decoded.encoded)
        assertEquals(Signatures.encodeSpkiBase64Url(keyPair.public), com.guftugu.app.core.util.Base64Url.encode(spki))
    }

    @Test
    fun `sha256 known vector`() {
        val h = Signatures.sha256("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h.joinToString("") { "%02x".format(it) })
    }
}
