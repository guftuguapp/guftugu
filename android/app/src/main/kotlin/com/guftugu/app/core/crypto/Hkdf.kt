package com.guftugu.app.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val key = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmac(key, ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LEN)) { "HKDF length out of range" }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            val mac = Mac.getInstance(HMAC).apply { init(SecretKeySpec(prk, HMAC)) }
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - filled)
            System.arraycopy(previous, 0, out, filled, n)
            filled += n
            counter++
        }
        return out
    }

    fun derive(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)
}
