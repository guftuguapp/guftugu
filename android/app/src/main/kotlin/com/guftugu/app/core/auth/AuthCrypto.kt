package com.guftugu.app.core.auth

import com.guftugu.app.core.crypto.Signatures
import com.guftugu.app.core.util.Base64Url
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature

/**
 * The login challenge–response maths of PROTOCOL §4, isolated so it is trivially unit-testable:
 *
 * ```
 * message   = utf8("guftugu-auth:v1:" + deviceId + ":" + nonce)
 * signature = ECDSA-P256/SHA-256(message)  →  raw r‖s (IEEE P1363, 64 bytes)  →  base64url
 * ```
 *
 * The same shape is reused for the *offline* unlock, where the phone signs a locally generated
 * nonce with `guftugu_auth` and verifies it against its own public key — nothing crosses the wire,
 * so the prefix differs (`guftugu-local:v1:`) to keep the two domains apart.
 */
object AuthCrypto {
    const val CHALLENGE_PREFIX: String = Signatures.AUTH_PREFIX
    const val LOCAL_PREFIX: String = "guftugu-local:v1:"
    const val NONCE_BYTES = 32

    private val random = SecureRandom()

    /** UTF-8 bytes of `"guftugu-auth:v1:" + deviceId + ":" + nonce` (PROTOCOL §4). */
    fun challengeMessage(deviceId: String, nonce: String): ByteArray =
        Signatures.authMessage(deviceId, nonce)

    /** UTF-8 bytes of the offline proof message; never sent to a server. */
    fun localProofMessage(deviceId: String, nonce: String): ByteArray =
        (LOCAL_PREFIX + deviceId + ":" + nonce).toByteArray(Charsets.UTF_8)

    const val AUTH_KEY_PREFIX: String = "guftugu-authkey:v1:"

    /** UTF-8 bytes of `"guftugu-authkey:v1:" + deviceId + ":" + nonce + ":" + authPublicKey` (PUT /me/device/auth-key). */
    fun authKeyMessage(deviceId: String, nonce: String, authPublicKey: String): ByteArray =
        (AUTH_KEY_PREFIX + deviceId + ":" + nonce + ":" + authPublicKey).toByteArray(Charsets.UTF_8)

    /** 32 random bytes as base64url (same shape as a server nonce). */
    fun randomNonce(): String = Base64Url.encode(ByteArray(NONCE_BYTES).also(random::nextBytes))

    /**
     * Signs the challenge with an already-initialised [Signature] (`guftugu_device`, or the
     * `guftugu_auth` instance handed back by BiometricPrompt) and returns the base64url P1363
     * signature ready for `POST /auth/verify`.
     */
    fun signChallenge(signature: Signature, deviceId: String, nonce: String): String =
        Base64Url.encode(Signatures.signP256(signature, challengeMessage(deviceId, nonce)))

    /** Signs the local proof; used only by the offline unlock. */
    fun signLocalProof(signature: Signature, deviceId: String, nonce: String): String =
        Base64Url.encode(Signatures.signP256(signature, localProofMessage(deviceId, nonce)))

    /** Verifies a base64url P1363 (or DER) challenge signature — what the server does. */
    fun verifyChallenge(publicKey: PublicKey, deviceId: String, nonce: String, signatureB64: String): Boolean {
        val sig = Base64Url.decodeOrNull(signatureB64) ?: return false
        return Signatures.verifyP256(publicKey, challengeMessage(deviceId, nonce), sig)
    }

    fun verifyChallenge(spki: ByteArray, deviceId: String, nonce: String, signatureB64: String): Boolean =
        verifyChallenge(Signatures.decodeSpki(spki), deviceId, nonce, signatureB64)

    /** Verifies the offline proof against `guftugu_auth`'s public key. */
    fun verifyLocalProof(publicKey: PublicKey, deviceId: String, nonce: String, signatureB64: String): Boolean {
        val sig = Base64Url.decodeOrNull(signatureB64) ?: return false
        return Signatures.verifyP256(publicKey, localProofMessage(deviceId, nonce), sig)
    }
}
