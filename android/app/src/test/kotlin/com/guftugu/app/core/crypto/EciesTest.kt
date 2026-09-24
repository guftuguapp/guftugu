package com.guftugu.app.core.crypto

import com.guftugu.app.core.util.Base64Url
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EciesTest {
    private fun ecKeyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private val senderDevice = ecKeyPair()           // guftugu_device of the sender
    private val recipientEcdh = ecKeyPair()          // identity ECDH key of the recipient
    private val convKey = Ecies.newConversationKey()

    private fun signer() = Signature.getInstance("SHA256withECDSA").apply { initSign(senderDevice.private) }

    private fun wrap() = Ecies.wrapConversationKey(
        convKey = convKey,
        convId = "c_01",
        keyId = "x_01",
        recipientDeviceId = "d_recipient",
        recipientEncryptionSpki = recipientEcdh.public.encoded,
        senderDeviceId = "d_sender",
        senderDeviceSignature = signer(),
        createdAt = 1_758_500_000_000L,
    )

    @Test
    fun `round trip`() {
        val wrapped = wrap()
        assertEquals("c_01", wrapped.convId)
        assertEquals("x_01", wrapped.keyId)
        assertEquals("d_recipient", wrapped.recipientDeviceId)
        assertEquals("d_sender", wrapped.senderDeviceId)
        assertEquals(12, Base64Url.decode(wrapped.iv).size)
        assertEquals(32 + 16, Base64Url.decode(wrapped.ciphertext).size)
        assertEquals(64, Base64Url.decode(wrapped.signature).size)

        val unwrapped = Ecies.unwrapConversationKey(wrapped, recipientEcdh.private, senderDevice.public.encoded, recipientEcdh.public.encoded)
        assertArrayEquals(convKey, unwrapped)
    }

    @Test
    fun `signature is over the documented string and verifiable with the device key`() {
        val wrapped = wrap()
        val signed = ("guftugu-keywrap:v1:c_01:x_01:d_recipient:" + wrapped.ephemeralPublicKey + ":" + wrapped.iv + ":" + wrapped.ciphertext).toByteArray()
        assertTrue(Signatures.verifyP256(senderDevice.public.encoded, signed, Base64Url.decode(wrapped.signature)))
    }

    @Test
    fun `wrong sender key rejects before decrypting`() {
        val wrapped = wrap()
        val other = ecKeyPair()
        assertThrows(Ecies.InvalidWrapException::class.java) {
            Ecies.unwrapConversationKey(wrapped, recipientEcdh.private, other.public.encoded, recipientEcdh.public.encoded)
        }
    }

    @Test
    fun `tampered fields are rejected`() {
        val wrapped = wrap()
        val badSig = wrapped.copy(signature = Base64Url.encode(Base64Url.decode(wrapped.signature).also { it[5] = (it[5].toInt() xor 1).toByte() }))
        assertThrows(Ecies.InvalidWrapException::class.java) {
            Ecies.unwrapConversationKey(badSig, recipientEcdh.private, senderDevice.public.encoded, recipientEcdh.public.encoded)
        }
        // re-attributing to another recipient breaks both the signature and the AAD
        val badRecipient = wrapped.copy(recipientDeviceId = "d_other")
        assertThrows(Ecies.InvalidWrapException::class.java) {
            Ecies.unwrapConversationKey(badRecipient, recipientEcdh.private, senderDevice.public.encoded, recipientEcdh.public.encoded)
        }
    }

    @Test
    fun `wrong recipient private key fails`() {
        val wrapped = wrap()
        val other = ecKeyPair()
        assertThrows(Ecies.InvalidWrapException::class.java) {
            Ecies.unwrapConversationKey(wrapped, other.private, senderDevice.public.encoded, other.public.encoded)
        }
    }

    @Test
    fun `hkdf info layout matches the spec`() {
        val eph = ecKeyPair()
        val shared = SoftwareEcdh.sharedSecret(eph.private, recipientEcdh.public.encoded)
        assertEquals(32, shared.size)
        // symmetric: recipient computes the same secret from the ephemeral public key
        assertArrayEquals(shared, SoftwareEcdh.sharedSecret(recipientEcdh.private, eph.public.encoded))
        val expected = Hkdf.derive(shared, ByteArray(32), "guftugu-keywrap-v1".toByteArray() + eph.public.encoded + recipientEcdh.public.encoded, 32)
        assertArrayEquals(expected, Ecies.deriveWrapKey(shared, eph.public.encoded, recipientEcdh.public.encoded))
    }
}
