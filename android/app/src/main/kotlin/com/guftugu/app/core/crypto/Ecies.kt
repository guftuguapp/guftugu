package com.guftugu.app.core.crypto

import com.guftugu.app.core.util.Base64Url
import com.guftugu.app.protocol.WrappedKey
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Conversation-key wrapping (ECIES) exactly per PROTOCOL §7:
 *
 * ```
 * eph      = new P-256 keypair
 * shared   = ECDH(eph.priv, recipient.encryptionPublicKey).x                       (32 bytes)
 * wrapKey  = HKDF-SHA256(ikm = shared, salt = 32 zero bytes,
 *                        info = "guftugu-keywrap-v1" ‖ eph.pub(SPKI) ‖ recipient.encryptionPublicKey(SPKI), L = 32)
 * iv       = random 12 bytes
 * ct       = AES-256-GCM(wrapKey, iv, convKey (32 B), aad = utf8(convId ":" keyId ":" recipientDeviceId))  // + 16 B tag
 * sig      = ECDSA-P256-SHA256(senderDevice.guftugu_device,
 *              utf8("guftugu-keywrap:v1:" + convId + ":" + keyId + ":" + recipientDeviceId + ":" + eph.pub + ":" + iv + ":" + ct))
 * ```
 * where `eph.pub`, `iv`, `ct` in the signed string are their base64url (no padding) forms.
 */
object Ecies {
    const val INFO_PREFIX = "guftugu-keywrap-v1"
    const val SIG_PREFIX = "guftugu-keywrap:v1:"
    const val CONV_KEY_LENGTH = 32
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private val ZERO_SALT = ByteArray(32)
    private val random = SecureRandom()

    class InvalidWrapException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

    /** Fresh 256-bit conversation key. */
    fun newConversationKey(): ByteArray = ByteArray(CONV_KEY_LENGTH).also(random::nextBytes)

    /**
     * Wraps [convKey] for one recipient device and signs the wrap with the sender's device key.
     *
     * @param senderDeviceSignature a [Signature] already initialised for signing with `guftugu_device`
     *   (see [KeystoreKeys.signatureFor]); in tests any `SHA256withECDSA` signature works.
     */
    fun wrapConversationKey(
        convKey: ByteArray,
        convId: String,
        keyId: String,
        recipientDeviceId: String,
        recipientEncryptionSpki: ByteArray,
        senderDeviceId: String,
        senderDeviceSignature: Signature,
        createdAt: Long = System.currentTimeMillis(),
    ): WrappedKey {
        require(convKey.size == CONV_KEY_LENGTH) { "conversation key must be 32 bytes" }
        val eph = SoftwareEcdh.generateKeyPair()
        val ephSpki = eph.public.encoded
        val shared = SoftwareEcdh.sharedSecret(eph.private, recipientEncryptionSpki)
        val wrapKey = deriveWrapKey(shared, ephSpki, recipientEncryptionSpki)
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad(convId, keyId, recipientDeviceId))
            doFinal(convKey)
        }
        wrapKey.fill(0)
        shared.fill(0)

        val ephB64 = Base64Url.encode(ephSpki)
        val ivB64 = Base64Url.encode(iv)
        val ctB64 = Base64Url.encode(ct)
        val signed = signedString(convId, keyId, recipientDeviceId, ephB64, ivB64, ctB64)
        val sig = Signatures.signP256(senderDeviceSignature, signed)

        return WrappedKey(
            keyId = keyId,
            convId = convId,
            recipientDeviceId = recipientDeviceId,
            senderDeviceId = senderDeviceId,
            ephemeralPublicKey = ephB64,
            iv = ivB64,
            ciphertext = ctB64,
            signature = Base64Url.encode(sig),
            createdAt = createdAt,
        )
    }

    /**
     * Verifies the sender's signature FIRST (against the sender device's `devicePublicKey` SPKI),
     * then unwraps with my identity ECDH private key. Throws [InvalidWrapException] on any failure.
     */
    fun unwrapConversationKey(
        wrapped: WrappedKey,
        myEcdhPrivate: PrivateKey,
        senderDevicePublicKeySpki: ByteArray,
        myEcdhPublicSpki: ByteArray,
    ): ByteArray {
        val signed = signedString(
            wrapped.convId, wrapped.keyId, wrapped.recipientDeviceId,
            wrapped.ephemeralPublicKey, wrapped.iv, wrapped.ciphertext,
        )
        val sig = Base64Url.decodeOrNull(wrapped.signature) ?: throw InvalidWrapException("malformed signature")
        if (!Signatures.verifyP256(senderDevicePublicKeySpki, signed, sig)) {
            throw InvalidWrapException("wrap signature does not verify")
        }
        val ephSpki = Base64Url.decodeOrNull(wrapped.ephemeralPublicKey) ?: throw InvalidWrapException("malformed ephemeral key")
        val iv = Base64Url.decodeOrNull(wrapped.iv) ?: throw InvalidWrapException("malformed iv")
        val ct = Base64Url.decodeOrNull(wrapped.ciphertext) ?: throw InvalidWrapException("malformed ciphertext")
        if (iv.size != IV_LENGTH) throw InvalidWrapException("iv must be 12 bytes")

        val shared = try {
            SoftwareEcdh.sharedSecret(myEcdhPrivate, ephSpki)
        } catch (e: GeneralSecurityException) {
            throw InvalidWrapException("ECDH failed", e)
        }
        val wrapKey = deriveWrapKey(shared, ephSpki, myEcdhPublicSpki)
        shared.fill(0)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(aad(wrapped.convId, wrapped.keyId, wrapped.recipientDeviceId))
                doFinal(ct)
            }.also { if (it.size != CONV_KEY_LENGTH) throw InvalidWrapException("unwrapped key has wrong length") }
        } catch (e: GeneralSecurityException) {
            throw InvalidWrapException("unwrap failed", e)
        } finally {
            wrapKey.fill(0)
        }
    }

    /** `HKDF-SHA256(ikm = shared, salt = 32 zero bytes, info = prefix ‖ ephSpki ‖ recipientSpki, L = 32)` */
    fun deriveWrapKey(shared: ByteArray, ephSpki: ByteArray, recipientSpki: ByteArray): ByteArray {
        val info = INFO_PREFIX.toByteArray(Charsets.UTF_8) + ephSpki + recipientSpki
        return Hkdf.derive(shared, ZERO_SALT, info, 32)
    }

    fun aad(convId: String, keyId: String, recipientDeviceId: String): ByteArray =
        "$convId:$keyId:$recipientDeviceId".toByteArray(Charsets.UTF_8)

    fun signedString(
        convId: String, keyId: String, recipientDeviceId: String,
        ephPubB64: String, ivB64: String, ctB64: String,
    ): ByteArray =
        (SIG_PREFIX + convId + ":" + keyId + ":" + recipientDeviceId + ":" + ephPubB64 + ":" + ivB64 + ":" + ctB64)
            .toByteArray(Charsets.UTF_8)
}
