package com.guftugu.app.core.util

import java.security.SecureRandom

/**
 * ULID generator (26 chars, Crockford base32, 48-bit ms timestamp + 80-bit randomness).
 * Used for client ids (`clientId` idempotency keys) and locally minted key epochs.
 * Monotonic within the same millisecond so ids sort in creation order.
 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()
    private val lock = Any()
    private var lastTime = 0L
    private val lastRandom = ByteArray(10)

    fun generate(nowMs: Long = System.currentTimeMillis()): String {
        val rnd = ByteArray(10)
        synchronized(lock) {
            if (nowMs == lastTime) {
                // increment the 80-bit random part (little chance of overflow; if so, re-roll)
                var i = 9
                var carry = true
                while (i >= 0 && carry) {
                    val v = (lastRandom[i].toInt() and 0xFF) + 1
                    lastRandom[i] = (v and 0xFF).toByte()
                    carry = v > 0xFF
                    i--
                }
                if (carry) random.nextBytes(lastRandom)
            } else {
                random.nextBytes(lastRandom)
                lastTime = nowMs
            }
            System.arraycopy(lastRandom, 0, rnd, 0, 10)
        }
        return encode(nowMs, rnd)
    }

    /** `k_`, `x_`, … prefixed ids as used by the reference server. */
    fun prefixed(prefix: String, nowMs: Long = System.currentTimeMillis()): String = prefix + generate(nowMs)

    private fun encode(time: Long, rnd: ByteArray): String {
        val out = CharArray(26)
        // 10 chars of time (48 bits -> 50 bits, top 2 bits zero)
        var t = time
        for (i in 9 downTo 0) {
            out[i] = ALPHABET[(t and 0x1F).toInt()]
            t = t ushr 5
        }
        // 16 chars of randomness (80 bits)
        var acc = 0L
        var bits = 0
        var pos = 10
        for (b in rnd) {
            acc = (acc shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out[pos++] = ALPHABET[((acc ushr bits) and 0x1F).toInt()]
            }
        }
        return String(out)
    }

    /** Milliseconds encoded in a ULID (with or without a `x_`-style prefix). */
    fun timestampOf(ulid: String): Long? {
        val body = ulid.substringAfter('_', ulid)
        if (body.length != 26) return null
        var t = 0L
        for (i in 0 until 10) {
            val idx = ALPHABET.indexOf(body[i].uppercaseChar())
            if (idx < 0) return null
            t = (t shl 5) or idx.toLong()
        }
        return t
    }
}
