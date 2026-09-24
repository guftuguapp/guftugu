package com.guftugu.app.core.crypto

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `aes-256-gcm-chunked-v1` streaming file encryption (PROTOCOL §9, SECURITY "Attachments").
 *
 * The plaintext is split into [chunkSize] pieces; chunk *i* is AES-256-GCM encrypted with
 * `iv = baseIv(8) ‖ uint32be(i)` and `aad = utf8(objectKey + ":" + i)`, its 16-byte tag appended.
 * A final empty chunk is not written. Thumbnails reuse `fileKey` with indices starting at
 * 0x80000000 so the same key never repeats an iv. `sha256` of the whole ciphertext lets the
 * receiver detect truncation/corruption before spending CPU on decryption.
 */
object AttachmentCipher {
    const val ENC = "aes-256-gcm-chunked-v1"
    const val DEFAULT_CHUNK_SIZE = 1 shl 20 // 1 MiB
    const val KEY_LENGTH = 32
    const val BASE_IV_LENGTH = 8
    const val TAG_LENGTH = 16
    const val THUMBNAIL_INDEX_BASE = 0x80000000L
    private const val TAG_BITS = TAG_LENGTH * 8
    private val random = SecureRandom()

    class IntegrityException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

    data class Result(
        /** SHA-256 of the ciphertext object (what goes into `Attachment.sha256`/`thumbSha256`). */
        val sha256: ByteArray,
        val plainBytes: Long,
        val cipherBytes: Long,
    )

    fun newFileKey(): ByteArray = ByteArray(KEY_LENGTH).also(random::nextBytes)
    fun newBaseIv(): ByteArray = ByteArray(BASE_IV_LENGTH).also(random::nextBytes)

    /** Size of the ciphertext for a plaintext of [plainSize] bytes. */
    fun encryptedSize(plainSize: Long, chunkSize: Int = DEFAULT_CHUNK_SIZE): Long {
        val chunks = (plainSize + chunkSize - 1) / chunkSize
        return plainSize + chunks * TAG_LENGTH
    }

    fun chunkIv(baseIv: ByteArray, index: Long): ByteArray {
        require(baseIv.size == BASE_IV_LENGTH) { "baseIv must be 8 bytes" }
        val iv = ByteArray(12)
        System.arraycopy(baseIv, 0, iv, 0, BASE_IV_LENGTH)
        val i = index and 0xFFFFFFFFL
        iv[8] = (i ushr 24).toByte()
        iv[9] = (i ushr 16).toByte()
        iv[10] = (i ushr 8).toByte()
        iv[11] = i.toByte()
        return iv
    }

    /** `utf8(objectKey + ":" + index)` with the index as an unsigned decimal (thumbnails: 2147483648…). */
    fun chunkAad(objectKey: String, index: Long): ByteArray =
        (objectKey + ":" + (index and 0xFFFFFFFFL).toString()).toByteArray(Charsets.UTF_8)

    /**
     * Encrypts [input] to [output]. Neither stream is closed. [onProgress] receives cumulative plaintext bytes.
     */
    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        baseIv: ByteArray,
        objectKey: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        thumbnail: Boolean = false,
        onProgress: ((plainBytesSoFar: Long) -> Unit)? = null,
    ): Result {
        require(fileKey.size == KEY_LENGTH) { "fileKey must be 32 bytes" }
        require(chunkSize > 0) { "chunkSize must be positive" }
        val key = SecretKeySpec(fileKey, "AES")
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(chunkSize)
        var index = if (thumbnail) THUMBNAIL_INDEX_BASE else 0L
        var plain = 0L
        var cipherBytes = 0L
        while (true) {
            val n = readFully(input, buf, chunkSize)
            if (n <= 0) break
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, chunkIv(baseIv, index)))
            cipher.updateAAD(chunkAad(objectKey, index))
            val ct = cipher.doFinal(buf, 0, n)
            output.write(ct)
            digest.update(ct)
            cipherBytes += ct.size
            plain += n
            index++
            onProgress?.invoke(plain)
            if (n < chunkSize) break
        }
        output.flush()
        return Result(digest.digest(), plain, cipherBytes)
    }

    /**
     * Decrypts [input] to [output], verifying every chunk tag and (if given) the ciphertext SHA-256.
     * Throws [IntegrityException] on any mismatch or truncation. Neither stream is closed.
     */
    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        fileKey: ByteArray,
        baseIv: ByteArray,
        objectKey: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        thumbnail: Boolean = false,
        expectedSha256: ByteArray? = null,
        onProgress: ((cipherBytesSoFar: Long) -> Unit)? = null,
    ): Result {
        require(fileKey.size == KEY_LENGTH) { "fileKey must be 32 bytes" }
        require(chunkSize > 0) { "chunkSize must be positive" }
        val key = SecretKeySpec(fileKey, "AES")
        val digest = MessageDigest.getInstance("SHA-256")
        val encChunk = chunkSize + TAG_LENGTH
        val buf = ByteArray(encChunk)
        var index = if (thumbnail) THUMBNAIL_INDEX_BASE else 0L
        var plain = 0L
        var cipherBytes = 0L
        while (true) {
            val n = readFully(input, buf, encChunk)
            if (n <= 0) break
            if (n < TAG_LENGTH) throw IntegrityException("truncated ciphertext (chunk $index)")
            digest.update(buf, 0, n)
            cipherBytes += n
            val pt = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, chunkIv(baseIv, index)))
                cipher.updateAAD(chunkAad(objectKey, index))
                cipher.doFinal(buf, 0, n)
            } catch (e: GeneralSecurityException) {
                throw IntegrityException("chunk $index failed authentication", e)
            }
            output.write(pt)
            plain += pt.size
            index++
            onProgress?.invoke(cipherBytes)
            if (n < encChunk) break
        }
        output.flush()
        val sha = digest.digest()
        if (expectedSha256 != null && !MessageDigest.isEqual(sha, expectedSha256)) {
            throw IntegrityException("ciphertext sha256 mismatch")
        }
        return Result(sha, plain, cipherBytes)
    }

    /** Reads up to [len] bytes, looping until the buffer is full or EOF. Returns bytes read (0 at EOF). */
    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = try {
                input.read(buf, total, len - total)
            } catch (e: EOFException) {
                -1
            }
            if (n < 0) break
            total += n
        }
        return total
    }
}
