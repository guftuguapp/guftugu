package com.guftugu.app.core.util

import java.util.Base64

/**
 * base64url without padding (RFC 4648 §5) — the encoding for every binary value on the wire.
 * Uses `java.util.Base64` (API 26+) so it is identical on Android and in JVM unit tests.
 */
object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val stdDecoder = Base64.getDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Decodes base64url with or without padding; also tolerates standard base64 ('+', '/'). */
    fun decode(text: String): ByteArray {
        val trimmed = text.trim().trimEnd('=')
        return if (trimmed.indexOf('+') >= 0 || trimmed.indexOf('/') >= 0) {
            stdDecoder.decode(pad(trimmed))
        } else {
            decoder.decode(trimmed)
        }
    }

    fun decodeOrNull(text: String?): ByteArray? =
        if (text == null) null else runCatching { decode(text) }.getOrNull()

    fun isValid(text: String): Boolean = decodeOrNull(text) != null

    private fun pad(s: String): String = when (s.length % 4) {
        2 -> "$s=="
        3 -> "$s="
        else -> s
    }
}

fun ByteArray.toBase64Url(): String = Base64Url.encode(this)
fun String.fromBase64Url(): ByteArray = Base64Url.decode(this)
