package com.guftugu.app.core.auth

import com.guftugu.app.core.crypto.Signatures
import com.guftugu.app.core.util.Base64Url
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCryptoTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun signer(): Signature = Signature.getInstance("SHA256withECDSA").apply { initSign(keyPair.private) }

    @Test
    fun `challenge message is utf8 of prefix + deviceId + colon + nonce`() {
        val msg = AuthCrypto.challengeMessage("d_01HZX", "bm9uY2U")
        assertArrayEquals("guftugu-auth:v1:d_01HZX:bm9uY2U".toByteArray(Charsets.UTF_8), msg)
        assertEquals("guftugu-auth:v1:", AuthCrypto.CHALLENGE_PREFIX)
        // identical to the shared Signatures helper (single source of truth for the wire format)
        assertArrayEquals(Signatures.authMessage("d_01HZX", "bm9uY2U"), msg)
    }

    @Test
    fun `signChallenge returns base64url P1363 (64 bytes) that verifies like the server does`() {
        val sigB64 = AuthCrypto.signChallenge(signer(), "d_1", "n_1")
        val raw = Base64Url.decode(sigB64)
        assertEquals(64, raw.size)
        assertFalse("no padding", sigB64.contains("="))
        assertFalse("url-safe alphabet", sigB64.contains("+") || sigB64.contains("/"))
        assertTrue(AuthCrypto.verifyChallenge(keyPair.public, "d_1", "n_1", sigB64))
        assertTrue(AuthCrypto.verifyChallenge(keyPair.public.encoded, "d_1", "n_1", sigB64))
        // DER form is also accepted (PROTOCOL §4 "servers should also accept DER")
        val der = Base64Url.encode(Signatures.p1363ToDer(raw))
        assertTrue(AuthCrypto.verifyChallenge(keyPair.public, "d_1", "n_1", der))
    }

    @Test
    fun `a signature is bound to deviceId and nonce`() {
        val sigB64 = AuthCrypto.signChallenge(signer(), "d_1", "n_1")
        assertFalse(AuthCrypto.verifyChallenge(keyPair.public, "d_2", "n_1", sigB64))
        assertFalse(AuthCrypto.verifyChallenge(keyPair.public, "d_1", "n_2", sigB64))
        assertFalse("garbage is rejected, not thrown", AuthCrypto.verifyChallenge(keyPair.public, "d_1", "n_1", "%%%not-base64%%%"))
        assertFalse(AuthCrypto.verifyChallenge(keyPair.public, "d_1", "n_1", ""))
    }

    @Test
    fun `local proof lives in its own domain`() {
        val nonce = AuthCrypto.randomNonce()
        assertEquals(32, Base64Url.decode(nonce).size)
        assertNotEquals(nonce, AuthCrypto.randomNonce())
        val proof = AuthCrypto.signLocalProof(signer(), "d_1", nonce)
        assertTrue(AuthCrypto.verifyLocalProof(keyPair.public, "d_1", nonce, proof))
        // the same bytes must NOT verify as a server challenge signature
        assertFalse(AuthCrypto.verifyChallenge(keyPair.public, "d_1", nonce, proof))
        assertArrayEquals("guftugu-local:v1:d_1:$nonce".toByteArray(), AuthCrypto.localProofMessage("d_1", nonce))
    }

    @Test
    fun `many signatures all convert to fixed-width P1363`() {
        repeat(48) { i ->
            val sig = Base64Url.decode(AuthCrypto.signChallenge(signer(), "d_$i", "nonce$i"))
            assertEquals(64, sig.size)
        }
    }
}
