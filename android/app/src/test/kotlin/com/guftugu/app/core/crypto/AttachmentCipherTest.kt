package com.guftugu.app.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AttachmentCipherTest {
    private val fileKey = AttachmentCipher.newFileKey()
    private val baseIv = AttachmentCipher.newBaseIv()
    private val objectKey = "conv/c_1/abc.bin"
    private val chunk = 1024

    private fun encrypt(plain: ByteArray, thumbnail: Boolean = false): Pair<ByteArray, AttachmentCipher.Result> {
        val out = ByteArrayOutputStream()
        val r = AttachmentCipher.encryptStream(ByteArrayInputStream(plain), out, fileKey, baseIv, objectKey, chunk, thumbnail)
        return out.toByteArray() to r
    }

    private fun decrypt(cipher: ByteArray, sha: ByteArray?, thumbnail: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        AttachmentCipher.decryptStream(ByteArrayInputStream(cipher), out, fileKey, baseIv, objectKey, chunk, thumbnail, sha)
        return out.toByteArray()
    }

    @Test
    fun `round trip across chunk boundaries`() {
        val rnd = java.util.Random(7)
        for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, 3 * chunk + 7, 5 * chunk)) {
            val plain = ByteArray(size).also(rnd::nextBytes)
            val (cipher, r) = encrypt(plain)
            assertEquals("size $size", AttachmentCipher.encryptedSize(size.toLong(), chunk), cipher.size.toLong())
            assertEquals(size.toLong(), r.plainBytes)
            assertEquals(cipher.size.toLong(), r.cipherBytes)
            assertArrayEquals(Signatures.sha256(cipher), r.sha256)
            assertArrayEquals("size $size", plain, decrypt(cipher, r.sha256))
        }
    }

    @Test
    fun `chunk iv and aad layout`() {
        val iv = AttachmentCipher.chunkIv(baseIv, 258)
        assertEquals(12, iv.size)
        assertArrayEquals(baseIv, iv.copyOfRange(0, 8))
        assertArrayEquals(byteArrayOf(0, 0, 1, 2), iv.copyOfRange(8, 12))
        val thumbIv = AttachmentCipher.chunkIv(baseIv, AttachmentCipher.THUMBNAIL_INDEX_BASE + 1)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0, 0, 1), thumbIv.copyOfRange(8, 12))
        assertEquals("$objectKey:3", String(AttachmentCipher.chunkAad(objectKey, 3)))
        assertEquals("$objectKey:2147483648", String(AttachmentCipher.chunkAad(objectKey, AttachmentCipher.THUMBNAIL_INDEX_BASE)))
    }

    @Test
    fun `thumbnails use a distinct iv space`() {
        val plain = ByteArray(2 * chunk + 5) { it.toByte() }
        val (asFile, _) = encrypt(plain, thumbnail = false)
        val (asThumb, r) = encrypt(plain, thumbnail = true)
        assertEquals(asFile.size, asThumb.size)
        assert(!asFile.contentEquals(asThumb))
        assertArrayEquals(plain, decrypt(asThumb, r.sha256, thumbnail = true))
        assertThrows(AttachmentCipher.IntegrityException::class.java) { decrypt(asThumb, null, thumbnail = false) }
    }

    @Test
    fun `truncation and corruption are detected`() {
        val plain = ByteArray(3 * chunk) { (it * 7).toByte() }
        val (cipher, r) = encrypt(plain)
        // drop the last chunk entirely: chunks still authenticate, but the sha256 catches it
        val truncated = cipher.copyOfRange(0, 2 * (chunk + 16))
        assertThrows(AttachmentCipher.IntegrityException::class.java) { decrypt(truncated, r.sha256) }
        // cut mid-chunk: GCM tag fails
        assertThrows(AttachmentCipher.IntegrityException::class.java) { decrypt(cipher.copyOfRange(0, cipher.size - 3), null) }
        // flip a byte
        val corrupted = cipher.copyOf().also { it[chunk + 20] = (it[chunk + 20].toInt() xor 1).toByte() }
        assertThrows(AttachmentCipher.IntegrityException::class.java) { decrypt(corrupted, null) }
        // wrong object key → AAD mismatch
        assertThrows(AttachmentCipher.IntegrityException::class.java) {
            AttachmentCipher.decryptStream(ByteArrayInputStream(cipher), ByteArrayOutputStream(), fileKey, baseIv, "conv/c_1/other.bin", chunk)
        }
    }
}
