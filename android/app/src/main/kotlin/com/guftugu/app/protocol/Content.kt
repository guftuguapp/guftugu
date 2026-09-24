/**
 * Decrypted payloads — never seen by the server. Mirrors the "decrypted content"
 * section of `server/src/protocol/types.ts` / PROTOCOL.md §8, §11.
 */
package com.guftugu.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** `Content.type`. Content is produced by this app, so a closed enum is safe here. */
@Serializable
enum class ContentType {
    @SerialName("text") TEXT,
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("audio") AUDIO,
    @SerialName("file") FILE;

    val wire: String
        get() = when (this) {
            TEXT -> "text"; IMAGE -> "image"; VIDEO -> "video"; AUDIO -> "audio"; FILE -> "file"
        }

    companion object {
        fun fromWire(value: String?): ContentType? = entries.firstOrNull { it.wire == value }
    }
}

/** Encrypted-attachment metadata carried inside the envelope (PROTOCOL §8/§9). */
@Serializable
data class Attachment(
    /** Blob-store object key, `conv/<convId>/….bin`. */
    val key: String,
    val mime: String,
    val sizeBytes: Long,
    val fileName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val enc: String = ENC_AES_256_GCM_CHUNKED_V1,
    /** base64url, 32 bytes */
    val fileKey: String,
    /** base64url, 8 bytes */
    val baseIv: String,
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    /** base64url SHA-256 of the ciphertext object */
    val sha256: String,
    val thumbKey: String? = null,
    val thumbSha256: String? = null,
) {
    companion object {
        const val ENC_AES_256_GCM_CHUNKED_V1 = "aes-256-gcm-chunked-v1"
        const val DEFAULT_CHUNK_SIZE = 1 shl 20 // 1 MiB
    }
}

/** Plaintext of a message envelope (UTF-8 JSON before encryption). */
@Serializable
data class Content(
    val type: ContentType,
    val text: String? = null,
    val attachment: Attachment? = null,
    val replyTo: String? = null,
) {
    companion object {
        fun text(text: String, replyTo: String? = null) = Content(ContentType.TEXT, text = text, replyTo = replyTo)
    }
}

/** Decrypted `call.signal` payload, discriminated by `kind`. */
@Serializable
@JsonClassDiscriminator("kind")
sealed class Signal {
    @Serializable
    @SerialName("offer")
    data class Offer(val sdp: String) : Signal()

    @Serializable
    @SerialName("answer")
    data class Answer(val sdp: String) : Signal()

    @Serializable
    @SerialName("ice")
    data class Ice(val candidate: String, val sdpMid: String, val sdpMLineIndex: Int) : Signal()
}
