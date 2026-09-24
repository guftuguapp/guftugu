package com.guftugu.app.core.crypto

import com.guftugu.app.core.util.Base64Url
import com.guftugu.app.protocol.Envelope
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Message-envelope encryption (PROTOCOL §8): AES-256-GCM, random 12-byte iv, 128-bit tag,
 * AAD = utf8(convId ":" senderId ":" clientId). The plaintext is the UTF-8 JSON of [com.guftugu.app.protocol.Content].
 */
object ContentCipher {
    const val IV_LENGTH = 12
    const val TAG_BITS = 128
    /** Server rejects envelopes over 64 KiB (attachments go to blob storage). */
    const val MAX_ENVELOPE_BYTES = 64 * 1024
    private val random = SecureRandom()

    class DecryptException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

    fun aad(convId: String, senderId: String, clientId: String): ByteArray =
        "$convId:$senderId:$clientId".toByteArray(Charsets.UTF_8)

    fun encrypt(convKey: ByteArray, keyId: String, contentJson: String, aad: ByteArray): Envelope {
        require(convKey.size == 32) { "conversation key must be 32 bytes" }
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(convKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
            doFinal(contentJson.toByteArray(Charsets.UTF_8))
        }
        return Envelope(v = 1, keyId = keyId, iv = Base64Url.encode(iv), ct = Base64Url.encode(ct))
    }

    /** Returns the UTF-8 JSON plaintext. Throws [DecryptException] on tag/AAD mismatch or malformed input. */
    fun decrypt(convKey: ByteArray, envelope: Envelope, aad: ByteArray): String {
        if (envelope.v != 1) throw DecryptException("unsupported envelope version ${envelope.v}")
        val iv = Base64Url.decodeOrNull(envelope.iv) ?: throw DecryptException("malformed iv")
        val ct = Base64Url.decodeOrNull(envelope.ct) ?: throw DecryptException("malformed ciphertext")
        if (iv.size != IV_LENGTH) throw DecryptException("iv must be 12 bytes")
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(convKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(aad)
                String(doFinal(ct), Charsets.UTF_8)
            }
        } catch (e: AEADBadTagException) {
            throw DecryptException("authentication failed", e)
        } catch (e: GeneralSecurityException) {
            throw DecryptException("decrypt failed", e)
        }
    }
}
