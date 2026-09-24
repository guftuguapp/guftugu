package com.guftugu.app.core.crypto

import com.guftugu.app.core.util.Base64Url
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * ECDSA P-256 / SHA-256 helpers and DER ⇄ IEEE P1363 (raw r‖s, 64 bytes) conversion.
 * Pure `java.security`, so it runs identically on Android and in JVM unit tests.
 *
 * The protocol sends signatures as P1363 (PROTOCOL §4); Android's `Signature` produces DER.
 */
object Signatures {
    const val ALGORITHM = "SHA256withECDSA"
    const val COMPONENT_LENGTH = 32
    const val P1363_LENGTH = COMPONENT_LENGTH * 2
    const val AUTH_PREFIX = "guftugu-auth:v1:"

    // ---------- hashing ----------

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    // ---------- SPKI ----------

    /** Parse an X.509 SubjectPublicKeyInfo (DER) into an EC public key. */
    fun decodeSpki(spki: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    fun decodeSpkiBase64Url(spki: String): PublicKey = decodeSpki(Base64Url.decode(spki))

    /** SPKI DER of a public key (`PublicKey.getEncoded()` is X.509/SPKI for EC keys). */
    fun encodeSpki(publicKey: PublicKey): ByteArray = publicKey.encoded

    fun encodeSpkiBase64Url(publicKey: PublicKey): String = Base64Url.encode(publicKey.encoded)

    // ---------- signing / verifying ----------

    /**
     * Signs [data] with an already-initialised (`initSign`) [Signature] — e.g. one obtained from
     * [KeystoreKeys.signatureFor] or, for the biometric key, the one inside a BiometricPrompt
     * CryptoObject after the user authenticated. Returns the P1363 (raw r‖s) signature.
     */
    fun signP256(signature: Signature, data: ByteArray): ByteArray {
        signature.update(data)
        return derToP1363(signature.sign())
    }

    /** Verifies a P1363 (or DER, tolerated) signature over [data] with the SPKI-encoded public key. */
    fun verifyP256(spki: ByteArray, data: ByteArray, sig: ByteArray): Boolean =
        verifyP256(decodeSpki(spki), data, sig)

    fun verifyP256(publicKey: PublicKey, data: ByteArray, sig: ByteArray): Boolean {
        val der = try {
            when {
                sig.size == P1363_LENGTH -> p1363ToDer(sig)
                sig.isNotEmpty() && sig[0] == 0x30.toByte() -> sig
                else -> return false
            }
        } catch (e: IllegalArgumentException) {
            return false
        }
        return try {
            Signature.getInstance(ALGORITHM).run {
                initVerify(publicKey)
                update(data)
                verify(der)
            }
        } catch (e: java.security.GeneralSecurityException) {
            false
        }
    }

    /** UTF-8 bytes of `"guftugu-auth:v1:" + deviceId + ":" + nonce` (PROTOCOL §4). */
    fun authMessage(deviceId: String, nonce: String): ByteArray =
        (AUTH_PREFIX + deviceId + ":" + nonce).toByteArray(Charsets.UTF_8)

    // ---------- DER <-> P1363 ----------

    /**
     * DER `SEQUENCE { INTEGER r, INTEGER s }` → fixed-width `r‖s` ([componentLength] bytes each).
     */
    fun derToP1363(der: ByteArray, componentLength: Int = COMPONENT_LENGTH): ByteArray {
        var pos = 0
        require(der.size >= 8 && der[pos++] == 0x30.toByte()) { "not a DER sequence" }
        val seqLen = readDerLength(der, pos).also { pos = it.second }.first
        require(pos + seqLen <= der.size) { "DER sequence length exceeds input" }
        val r = readDerInteger(der, pos).also { pos = it.second }.first
        val s = readDerInteger(der, pos).also { pos = it.second }.first
        val out = ByteArray(componentLength * 2)
        writeFixed(r, out, 0, componentLength)
        writeFixed(s, out, componentLength, componentLength)
        return out
    }

    /** Fixed-width `r‖s` → DER `SEQUENCE { INTEGER r, INTEGER s }`. */
    fun p1363ToDer(sig: ByteArray): ByteArray {
        require(sig.isNotEmpty() && sig.size % 2 == 0) { "P1363 signature must have even length" }
        val half = sig.size / 2
        val r = derInteger(sig.copyOfRange(0, half))
        val s = derInteger(sig.copyOfRange(half, sig.size))
        val body = r + s
        return byteArrayOf(0x30) + derLength(body.size) + body
    }

    private fun readDerLength(buf: ByteArray, start: Int): Pair<Int, Int> {
        var pos = start
        val first = buf[pos++].toInt() and 0xFF
        if (first < 0x80) return first to pos
        val n = first and 0x7F
        require(n in 1..2 && pos + n <= buf.size) { "unsupported DER length" }
        var len = 0
        repeat(n) { len = (len shl 8) or (buf[pos++].toInt() and 0xFF) }
        return len to pos
    }

    private fun readDerInteger(buf: ByteArray, start: Int): Pair<ByteArray, Int> {
        var pos = start
        require(pos < buf.size && buf[pos++] == 0x02.toByte()) { "expected DER INTEGER" }
        val (len, next) = readDerLength(buf, pos)
        pos = next
        require(pos + len <= buf.size) { "DER INTEGER exceeds input" }
        val value = buf.copyOfRange(pos, pos + len)
        return value to (pos + len)
    }

    /** Writes an unsigned big-endian integer (possibly with a leading 0x00 or shorter) into a fixed slot. */
    private fun writeFixed(value: ByteArray, out: ByteArray, offset: Int, length: Int) {
        var v = value
        // strip leading zeros
        var i = 0
        while (i < v.size - 1 && v[i] == 0.toByte()) i++
        if (i > 0) v = v.copyOfRange(i, v.size)
        require(v.size <= length) { "integer too large for P1363 component" }
        System.arraycopy(v, 0, out, offset + (length - v.size), v.size)
    }

    private fun derInteger(unsigned: ByteArray): ByteArray {
        // minimal two's-complement positive encoding
        val magnitude = BigInteger(1, unsigned).toByteArray() // BigInteger adds a 0x00 when the top bit is set
        return byteArrayOf(0x02) + derLength(magnitude.size) + magnitude
    }

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
    }
}
