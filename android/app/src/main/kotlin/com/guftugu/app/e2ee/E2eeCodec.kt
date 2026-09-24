package com.guftugu.app.e2ee

import com.guftugu.app.core.crypto.ContentCipher
import com.guftugu.app.core.crypto.Ecies
import com.guftugu.app.core.util.Base64Url
import com.guftugu.app.protocol.Content
import com.guftugu.app.protocol.Envelope
import com.guftugu.app.protocol.PublicDevice
import com.guftugu.app.protocol.Signal
import com.guftugu.app.protocol.WrappedKey
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.Signature
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The pure, JVM-testable half of the key manager: AAD construction, envelope (de)serialisation
 * around [ContentCipher], and the wrap/verify/unwrap policy around [Ecies]. No Room, no Keystore,
 * no network — the manager feeds it keys and public keys.
 *
 * AAD rules (PROTOCOL §8, §12):
 * - content: `utf8(convId + ":" + senderId + ":" + clientId)`. If the server omits `clientId`
 *   for a message (it stores it for every e2e message, so this is unusual) the empty string is
 *   used, which is exactly what a sender without a clientId would have produced.
 * - signal:  `utf8(convId + ":" + callId + ":signal")`.
 */
object E2eeCodec {
    const val SIGNAL_AAD_SUFFIX = ":signal"

    // ---------- AAD ----------

    fun contentAad(convId: String, senderId: String, clientId: String?): ByteArray =
        ContentCipher.aad(convId, senderId, clientId ?: "")

    fun signalAad(convId: String, callId: String): ByteArray =
        (convId + ":" + callId + SIGNAL_AAD_SUFFIX).toByteArray(Charsets.UTF_8)

    // ---------- content envelopes ----------

    fun encryptContent(json: Json, convKey: ByteArray, keyId: String, content: Content, aad: ByteArray): Envelope {
        val plain = json.encodeToString(Content.serializer(), content)
        val envelope = encryptJson(convKey, keyId, plain, aad)
        // The server rejects envelopes over 64 KiB (attachments go to blob storage, not here).
        // The envelope JSON is pure ASCII, so its length is its byte size.
        val size = json.encodeToString(Envelope.serializer(), envelope).length
        if (size > ContentCipher.MAX_ENVELOPE_BYTES) {
            throw E2eeException(E2eeException.Reason.TOO_LARGE, "envelope is $size bytes; limit is ${ContentCipher.MAX_ENVELOPE_BYTES}")
        }
        return envelope
    }

    fun decryptContent(json: Json, convKey: ByteArray, envelope: Envelope, aad: ByteArray): Content {
        val plain = decryptJson(convKey, envelope, aad)
        return try {
            json.decodeFromString(Content.serializer(), plain)
        } catch (e: SerializationException) {
            throw E2eeException(E2eeException.Reason.MALFORMED, "decrypted content is not valid Content JSON", e)
        } catch (e: IllegalArgumentException) {
            throw E2eeException(E2eeException.Reason.MALFORMED, "decrypted content has an unexpected shape", e)
        }
    }

    // ---------- call-signal envelopes ----------

    fun encryptSignal(json: Json, convKey: ByteArray, keyId: String, signal: Signal, aad: ByteArray): Envelope =
        encryptJson(convKey, keyId, json.encodeToString(Signal.serializer(), signal), aad)

    fun decryptSignal(json: Json, convKey: ByteArray, envelope: Envelope, aad: ByteArray): Signal {
        val plain = decryptJson(convKey, envelope, aad)
        return try {
            json.decodeFromString(Signal.serializer(), plain)
        } catch (e: SerializationException) {
            throw E2eeException(E2eeException.Reason.MALFORMED, "decrypted signal is not valid Signal JSON", e)
        } catch (e: IllegalArgumentException) {
            throw E2eeException(E2eeException.Reason.MALFORMED, "decrypted signal has an unexpected shape", e)
        }
    }

    private fun encryptJson(convKey: ByteArray, keyId: String, plain: String, aad: ByteArray): Envelope = try {
        ContentCipher.encrypt(convKey, keyId, plain, aad)
    } catch (e: GeneralSecurityException) {
        throw E2eeException(E2eeException.Reason.CRYPTO, "envelope encryption failed", e)
    } catch (e: IllegalArgumentException) {
        throw E2eeException(E2eeException.Reason.CRYPTO, "envelope encryption failed", e)
    }

    private fun decryptJson(convKey: ByteArray, envelope: Envelope, aad: ByteArray): String = try {
        ContentCipher.decrypt(convKey, envelope, aad)
    } catch (e: ContentCipher.DecryptException) {
        throw E2eeException(E2eeException.Reason.AUTHENTICATION_FAILED, "envelope does not authenticate", e)
    } catch (e: GeneralSecurityException) {
        throw E2eeException(E2eeException.Reason.AUTHENTICATION_FAILED, "envelope does not authenticate", e)
    }

    // ---------- key wrapping ----------

    /**
     * Wraps [convKey] once per distinct device in [devices], signed with the sender's device key.
     * A device whose `encryptionPublicKey` cannot be parsed is skipped (it simply keeps
     * `hasCurrentKey = false` on the server); the result may therefore be shorter than [devices].
     *
     * @param signer a [Signature] initialised for signing with `guftugu_device`; it is reused for
     *   every wrap (`sign()` resets it for the next one).
     */
    fun wrapForDevices(
        convKey: ByteArray,
        convId: String,
        keyId: String,
        devices: List<PublicDevice>,
        senderDeviceId: String,
        signer: Signature,
        createdAt: Long = System.currentTimeMillis(),
    ): List<WrappedKey> {
        val out = ArrayList<WrappedKey>(devices.size)
        val seen = HashSet<String>(devices.size * 2)
        for (device in devices) {
            if (!seen.add(device.deviceId)) continue
            val spki = Base64Url.decodeOrNull(device.encryptionPublicKey) ?: continue
            val wrapped = try {
                Ecies.wrapConversationKey(
                    convKey = convKey,
                    convId = convId,
                    keyId = keyId,
                    recipientDeviceId = device.deviceId,
                    recipientEncryptionSpki = spki,
                    senderDeviceId = senderDeviceId,
                    senderDeviceSignature = signer,
                    createdAt = createdAt,
                )
            } catch (e: GeneralSecurityException) {
                continue // unusable recipient key; never let one bad device block the others
            } catch (e: IllegalArgumentException) {
                continue
            }
            out.add(wrapped)
        }
        return out
    }

    /** Outcome of [importWraps]: the unwrapped keys by keyId plus counters for diagnostics. */
    class ImportResult(
        val imported: Map<String, ByteArray>,
        /** Not addressed to me, or a key I already hold. */
        val skipped: Int,
        /** Addressed to me but rejected: wrong conversation, unknown sender, bad signature or bad ciphertext. */
        val refused: Int,
        /** Subset of [refused] whose sender device was not in the recipients list. */
        val unknownSenders: Int,
    )

    /**
     * Picks the wraps addressed to [myDeviceId] for [convId] that are not [alreadyHeld], verifies
     * each one's signature against the sender device's `devicePublicKey` (from [senderPublicKey];
     * a sender that is not a current member device is refused) and unwraps it.
     * The first valid wrap per keyId wins.
     */
    fun importWraps(
        convId: String,
        items: List<WrappedKey>,
        myDeviceId: String,
        myEcdhPrivate: PrivateKey,
        myEcdhPublicSpki: ByteArray,
        senderPublicKey: (senderDeviceId: String) -> ByteArray?,
        alreadyHeld: (keyId: String) -> Boolean,
    ): ImportResult {
        val imported = LinkedHashMap<String, ByteArray>()
        var skipped = 0
        var refused = 0
        var unknownSenders = 0
        for (wrap in items) {
            if (wrap.recipientDeviceId != myDeviceId) { skipped++; continue }
            if (wrap.keyId.isEmpty() || imported.containsKey(wrap.keyId) || alreadyHeld(wrap.keyId)) { skipped++; continue }
            if (wrap.convId != convId) { refused++; continue }
            val senderSpki = senderPublicKey(wrap.senderDeviceId)
            if (senderSpki == null) { refused++; unknownSenders++; continue }
            val raw = try {
                Ecies.unwrapConversationKey(wrap, myEcdhPrivate, senderSpki, myEcdhPublicSpki)
            } catch (e: Ecies.InvalidWrapException) {
                refused++; continue
            } catch (e: GeneralSecurityException) {
                refused++; continue
            } catch (e: IllegalArgumentException) {
                refused++; continue
            }
            imported[wrap.keyId] = raw
        }
        return ImportResult(imported, skipped, refused, unknownSenders)
    }
}
