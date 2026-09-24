package com.guftugu.app.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.guftugu.app.core.util.Base64Url
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The three Android Keystore keys (SECURITY.md "Keys on the phone"):
 *
 * | alias            | algorithm      | user auth                                   | purpose |
 * |------------------|----------------|---------------------------------------------|---------|
 * | `guftugu_device` | EC P-256 sign  | none                                        | proves this install; signs key wraps; password-path login |
 * | `guftugu_auth`   | EC P-256 sign  | BIOMETRIC_STRONG per use, invalidated on new enrolment | proves the person (biometric login) |
 * | `guftugu_wrap`   | AES-256-GCM    | none                                        | encrypts software secrets at rest ([com.guftugu.app.data.prefs.SecureStore]) |
 *
 * Keys are non-exportable; StrongBox is attempted first with a TEE fallback. Nothing here is
 * ever backed up (reinstall / new phone = re-enrol).
 */
object KeystoreKeys {
    const val DEVICE = "guftugu_device"
    const val AUTH = "guftugu_auth"
    const val WRAP = "guftugu_wrap"

    private const val PROVIDER = "AndroidKeyStore"
    private const val CURVE = "secp256r1"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    // ---------- creation ----------

    /** Creates `guftugu_device` if missing. Returns its public key. */
    @Synchronized
    fun ensureDeviceKey(): PublicKey {
        publicKey(DEVICE)?.let { return it }
        generateEcKey(DEVICE, userAuth = false)
        return requireNotNull(publicKey(DEVICE))
    }

    /**
     * Creates `guftugu_auth` (biometric-gated) if missing. Throws if the device cannot create
     * such a key (e.g. no biometrics enrolled) — callers then go password-only.
     */
    @Synchronized
    fun ensureAuthKey(): PublicKey {
        publicKey(AUTH)?.let { return it }
        generateEcKey(AUTH, userAuth = true)
        return requireNotNull(publicKey(AUTH))
    }

    /** Creates `guftugu_wrap` (AES-256-GCM) if missing. */
    @Synchronized
    fun ensureWrapKey(): SecretKey {
        secretKey(WRAP)?.let { return it }
        generateAesKey(WRAP)
        return requireNotNull(secretKey(WRAP))
    }

    // ---------- lookup ----------

    fun hasKey(alias: String): Boolean = runCatching { keyStore.containsAlias(alias) }.getOrDefault(false)

    fun hasBiometricKey(): Boolean = hasKey(AUTH)

    fun publicKey(alias: String): PublicKey? =
        runCatching { keyStore.getCertificate(alias)?.publicKey }.getOrNull()

    /** base64url SPKI DER of the key's public half, or null when the alias does not exist. */
    fun publicKeySpkiBase64Url(alias: String): String? = publicKey(alias)?.let { Base64Url.encode(it.encoded) }

    private fun secretKey(alias: String): SecretKey? =
        runCatching { keyStore.getKey(alias, null) as? SecretKey }.getOrNull()

    // ---------- signing ----------

    /**
     * A `SHA256withECDSA` [Signature] initialised for signing with [alias].
     *
     * For [AUTH] this must be wrapped in a `BiometricPrompt.CryptoObject` and only used after the
     * prompt succeeds. A [KeyPermanentlyInvalidatedException] (new biometric enrolled) propagates
     * to the caller, which should fall back to the password path and offer re-enrolment.
     */
    @Throws(KeyPermanentlyInvalidatedException::class)
    fun signatureFor(alias: String): Signature {
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("keystore alias $alias missing")
        return Signature.getInstance(Signatures.ALGORITHM).apply { initSign(entry.privateKey) }
    }

    // ---------- wrapping ----------

    /** AES-GCM encrypts [plain] with `guftugu_wrap`; output is `iv(12) ‖ ciphertext+tag`. */
    fun wrap(plain: ByteArray): ByteArray {
        val key = ensureWrapKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key) // Keystore generates the iv (randomised encryption required)
        val iv = cipher.iv
        check(iv.size == GCM_IV_LENGTH) { "unexpected GCM iv length" }
        val ct = cipher.doFinal(plain)
        return iv + ct
    }

    /** Inverse of [wrap]. Throws `javax.crypto.AEADBadTagException` on tampering or a wrong key. */
    fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > GCM_IV_LENGTH) { "wrapped blob too short" }
        val key = ensureWrapKey()
        val iv = wrapped.copyOfRange(0, GCM_IV_LENGTH)
        val ct = wrapped.copyOfRange(GCM_IV_LENGTH, wrapped.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    // ---------- lifecycle ----------

    /** Deletes all three keys (logout after revocation, re-enrolment). */
    @Synchronized
    fun deleteAll() {
        for (alias in listOf(DEVICE, AUTH, WRAP)) {
            runCatching { if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) }
        }
    }

    @Synchronized
    fun deleteAuthKey() {
        runCatching { if (keyStore.containsAlias(AUTH)) keyStore.deleteEntry(AUTH) }
    }

    // ---------- generation internals ----------

    private fun generateEcKey(alias: String, userAuth: Boolean) {
        val build: (Boolean) -> KeyGenParameterSpec = { strongBox ->
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply {
                    if (userAuth) {
                        setUserAuthenticationRequired(true)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                        } else {
                            @Suppress("DEPRECATION")
                            setUserAuthenticationValidityDurationSeconds(-1)
                        }
                        setInvalidatedByBiometricEnrollment(true)
                    }
                    if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
                }
                .build()
        }
        generateWithStrongBoxFallback { strongBox ->
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
                initialize(build(strongBox))
            }.generateKeyPair()
        }
    }

    private fun generateAesKey(alias: String) {
        val build: (Boolean) -> KeyGenParameterSpec = { strongBox ->
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
                .build()
        }
        generateWithStrongBoxFallback { strongBox ->
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
                init(build(strongBox))
            }.generateKey()
        }
    }

    /** Try StrongBox first (API 28+); on [StrongBoxUnavailableException] (or any provider error) retry in the TEE. */
    private fun generateWithStrongBoxFallback(generate: (strongBox: Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generate(true)
                return
            } catch (e: StrongBoxUnavailableException) {
                // fall through
            } catch (e: java.security.ProviderException) {
                // Some devices report StrongBox failures as a generic ProviderException.
            }
        }
        generate(false)
    }
}
