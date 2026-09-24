package com.guftugu.app.core.crypto

import com.guftugu.app.core.util.Base64Url
import com.guftugu.app.data.prefs.SecureStore
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * The device's identity ECDH key (P-256) used to *receive* wrapped conversation keys (PROTOCOL §7).
 *
 * It is a software key (Keystore `KeyAgreement` only exists from Android 12) whose PKCS#8 private
 * part is stored in [SecureStore], i.e. encrypted at rest by the hardware-backed `guftugu_wrap`
 * AES key. The public part is exported as base64url SPKI DER (`encryptionPublicKey`).
 *
 * The static helpers are pure `java.security` and shared with [Ecies] and unit tests.
 */
class SoftwareEcdh(private val store: SecureStore) {

    /** Creates the identity keypair if absent; returns the public key as base64url SPKI. */
    @Synchronized
    fun ensureIdentityKey(): String {
        publicKeySpkiBase64Url()?.let { return it }
        val kp = generateKeyPair()
        store.putBytes(SecureStore.KEY_ECDH_PRIVATE_PKCS8, kp.private.encoded)
        store.putBytes(SecureStore.KEY_ECDH_PUBLIC_SPKI, kp.public.encoded)
        return Base64Url.encode(kp.public.encoded)
    }

    fun publicKeySpkiBase64Url(): String? = store.getBytes(SecureStore.KEY_ECDH_PUBLIC_SPKI)?.let(Base64Url::encode)

    fun privateKey(): PrivateKey? = store.getBytes(SecureStore.KEY_ECDH_PRIVATE_PKCS8)?.let(::privateKeyFromPkcs8)

    fun hasIdentityKey(): Boolean = store.contains(SecureStore.KEY_ECDH_PRIVATE_PKCS8)

    fun delete() {
        store.remove(SecureStore.KEY_ECDH_PRIVATE_PKCS8)
        store.remove(SecureStore.KEY_ECDH_PUBLIC_SPKI)
    }

    /** ECDH shared secret (x-coordinate, 32 bytes) between my private key and a peer's SPKI public key. */
    fun ecdhSharedSecret(privateKey: PrivateKey, peerSpki: ByteArray): ByteArray = sharedSecret(privateKey, peerSpki)

    companion object {
        const val CURVE = "secp256r1"
        const val SECRET_LENGTH = 32

        fun generateKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVE)) }.generateKeyPair()

        fun privateKeyFromPkcs8(pkcs8: ByteArray): PrivateKey =
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

        fun publicKeyFromSpki(spki: ByteArray): PublicKey = Signatures.decodeSpki(spki)

        /**
         * `ECDH(priv, peerPub).x` as exactly 32 big-endian bytes (`KeyAgreement("ECDH")` already returns the
         * fixed-width x-coordinate on SunEC and Conscrypt; normalised here defensively).
         */
        fun sharedSecret(privateKey: PrivateKey, peerSpki: ByteArray): ByteArray {
            val peer = publicKeyFromSpki(peerSpki)
            val raw = KeyAgreement.getInstance("ECDH").run {
                init(privateKey)
                doPhase(peer, true)
                generateSecret()
            }
            return when {
                raw.size == SECRET_LENGTH -> raw
                raw.size > SECRET_LENGTH -> raw.copyOfRange(raw.size - SECRET_LENGTH, raw.size)
                else -> ByteArray(SECRET_LENGTH - raw.size) + raw
            }
        }
    }
}
