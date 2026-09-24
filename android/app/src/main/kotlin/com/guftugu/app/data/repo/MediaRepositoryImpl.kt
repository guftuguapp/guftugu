package com.guftugu.app.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.guftugu.app.core.crypto.AttachmentCipher
import com.guftugu.app.core.media.CacheTrim
import com.guftugu.app.core.media.ImageScaler
import com.guftugu.app.core.media.MimeTypes
import com.guftugu.app.core.media.Thumbnails
import com.guftugu.app.core.util.Base64Url
import com.guftugu.app.core.util.Time
import com.guftugu.app.core.util.Ulid
import com.guftugu.app.data.api.GuftuguApi
import com.guftugu.app.data.db.GuftuguDb
import com.guftugu.app.data.db.MediaCacheEntity
import com.guftugu.app.data.prefs.ServerConfigStore
import com.guftugu.app.protocol.Attachment
import com.guftugu.app.protocol.ContentType
import com.guftugu.app.protocol.MediaKind
import com.guftugu.app.protocol.UploadUrlRequest
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Typed failures of the media pipeline (the UI maps these to strings). */
sealed class MediaException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class TooLarge(val sizeBytes: Long, val maxBytes: Long) : MediaException("attachment is $sizeBytes bytes, limit $maxBytes")
    class Unreadable(cause: Throwable? = null) : MediaException("cannot read the selected file", cause)
    class Integrity(cause: Throwable? = null) : MediaException("attachment failed integrity check", cause)
    class Unsupported(val mime: String) : MediaException("unsupported media type $mime")
}

/**
 * PROTOCOL §9 on the phone. Plaintext never leaves this process unencrypted: uploads are
 * `aes-256-gcm-chunked-v1` with a fresh fileKey per attachment (thumbnails share it with the
 * 0x80000000 chunk-index base); downloads are decrypted into [cacheDir]/<sha256(key)>.<ext>
 * and tracked in `media_cache` so the LRU trimmer can evict them.
 *
 * Every public method is safe to call from any dispatcher (heavy work hops to IO).
 */
class MediaRepositoryImpl(
    private val api: GuftuguApi,
    private val db: GuftuguDb,
    private val appContext: Context,
    private val cacheDir: File,
    private val serverConfig: ServerConfigStore,
    private val appScope: CoroutineScope,
) : MediaRepository {

    /** Upload progress 0..1 keyed by the local source Uri (for the chat agent's sending bubble). */
    val uploadProgress: StateFlow<Map<String, Float>> get() = progress
    private val progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    private val tmpDir: File get() = File(cacheDir, "tmp").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val avatarUrls = ConcurrentHashMap<String, Pair<String, Long>>()

    private fun lockFor(key: String): Mutex = locks.getOrPut(key) { Mutex() }

    // ---------- download side ----------

    override suspend fun openAttachment(att: Attachment): File =
        open(att.key, att.fileKey, att.baseIv, att.chunkSize, att.sha256, thumbnail = false, ext = MimeTypes.extensionFor(att.mime))

    override suspend fun openThumbnail(att: Attachment): File? {
        val thumbKey = att.thumbKey ?: return null
        return open(thumbKey, att.fileKey, att.baseIv, att.chunkSize, att.thumbSha256, thumbnail = true, ext = "jpg")
    }

    /** Cached decrypted file if present on disk (no network, no locks) — for instant first frames. */
    suspend fun cached(key: String): File? = withContext(Dispatchers.IO) {
        db.mediaCache().get(key)?.let { File(it.localPath) }?.takeIf { it.isFile }
    }

    private suspend fun open(
        key: String,
        fileKeyB64: String,
        baseIvB64: String,
        chunkSize: Int,
        expectedShaB64: String?,
        thumbnail: Boolean,
        ext: String,
    ): File = lockFor(key).withLock {
        withContext(Dispatchers.IO) {
            val dao = db.mediaCache()
            dao.get(key)?.let { row ->
                val f = File(row.localPath)
                if (f.isFile) {
                    dao.touch(key, Time.nowMs())
                    return@withContext f
                }
                dao.delete(key)
            }
            val fileKey = Base64Url.decodeOrNull(fileKeyB64)?.takeIf { it.size == AttachmentCipher.KEY_LENGTH }
                ?: throw MediaException.Integrity()
            val baseIv = Base64Url.decodeOrNull(baseIvB64)?.takeIf { it.size == AttachmentCipher.BASE_IV_LENGTH }
                ?: throw MediaException.Integrity()
            val expectedSha = expectedShaB64?.let { Base64Url.decodeOrNull(it) }
            val target = cacheFile(key, ext)
            val cipherTmp = File(tmpDir, target.name + ".enc")
            val plainTmp = File(tmpDir, target.name + ".dec")
            try {
                val url = api.downloadUrl(key).downloadUrl
                api.download(url, cipherTmp)
                try {
                    cipherTmp.inputStream().buffered(256 * 1024).use { input ->
                        plainTmp.outputStream().buffered(256 * 1024).use { out ->
                            AttachmentCipher.decryptStream(input, out, fileKey, baseIv, key, chunkSize, thumbnail, expectedSha)
                        }
                    }
                } catch (e: AttachmentCipher.IntegrityException) {
                    throw MediaException.Integrity(e)
                }
                target.parentFile?.mkdirs()
                if (target.exists()) target.delete()
                if (!plainTmp.renameTo(target)) {
                    plainTmp.copyTo(target, overwrite = true)
                    plainTmp.delete()
                }
                dao.upsert(MediaCacheEntity(key, target.absolutePath, target.length(), Time.nowMs()))
                target
            } finally {
                cipherTmp.delete()
                plainTmp.delete()
                File(tmpDir, cipherTmp.name + ".part").delete()
            }
        }
    }

    // ---------- upload side ----------

    override suspend fun uploadEncrypted(convId: String, local: Uri, mime: String): Attachment = withContext(Dispatchers.IO) {
        val maxBytes = serverConfig.current.value.maxUploadBytes
        val declaredName = displayName(local)
        val type = MimeTypes.contentTypeFor(mime, declaredName)
        val work = File(tmpDir, "up_" + Ulid.generate())
        val plainFile = File(work, "plain")
        val thumbPlain = File(work, "thumb.jpg")
        work.mkdirs()
        val progressKey = local.toString()
        try {
            // 1. Prepare the plaintext + metadata.
            val prepared = when (type) {
                ContentType.IMAGE -> prepareImage(local, mime, plainFile, thumbPlain, declaredName)
                ContentType.VIDEO -> prepareVideo(local, mime, plainFile, thumbPlain, declaredName, maxBytes)
                ContentType.AUDIO -> prepareAudio(local, mime, plainFile, declaredName, maxBytes)
                ContentType.FILE, ContentType.TEXT -> prepareFile(local, mime, plainFile, declaredName, maxBytes)
            }
            val plainSize = plainFile.length()
            if (plainSize > maxBytes) throw MediaException.TooLarge(plainSize, maxBytes)
            if (plainSize <= 0L) throw MediaException.Unreadable()

            // 2. Fresh key material; ask for the main object.
            val fileKey = AttachmentCipher.newFileKey()
            val baseIv = AttachmentCipher.newBaseIv()
            val chunkSize = AttachmentCipher.DEFAULT_CHUNK_SIZE
            val cipherSize = AttachmentCipher.encryptedSize(plainSize, chunkSize)
            val slot = api.uploadUrl(UploadUrlRequest(convId = convId, kind = MediaKind.ATTACHMENT, mime = MimeTypes.OCTET_STREAM, sizeBytes = cipherSize))
            val cipherFile = File(work, "cipher")
            val sha = encryptToFile(plainFile, cipherFile, fileKey, baseIv, slot.key, chunkSize, thumbnail = false)
            setProgress(progressKey, 0.05f)
            api.upload(slot.uploadUrl, slot.method, slot.headers, cipherFile) { sent, total ->
                if (total > 0) setProgress(progressKey, 0.05f + 0.85f * (sent.toFloat() / total))
            }
            cipherFile.delete()

            // 3. Thumbnail (images, videos) — same fileKey, thumbnail chunk-index base.
            var thumbKey: String? = null
            var thumbSha: String? = null
            if (thumbPlain.isFile && thumbPlain.length() > 0) {
                val thumbSize = AttachmentCipher.encryptedSize(thumbPlain.length(), chunkSize)
                val tslot = api.uploadUrl(UploadUrlRequest(convId = convId, kind = MediaKind.THUMBNAIL, mime = MimeTypes.OCTET_STREAM, sizeBytes = thumbSize))
                val tcipher = File(work, "thumb.cipher")
                val tsha = encryptToFile(thumbPlain, tcipher, fileKey, baseIv, tslot.key, chunkSize, thumbnail = true)
                api.upload(tslot.uploadUrl, tslot.method, tslot.headers, tcipher)
                tcipher.delete()
                thumbKey = tslot.key
                thumbSha = Base64Url.encode(tsha)
            }
            setProgress(progressKey, 0.95f)

            // 4. Keep the sender's own copy so the bubble renders instantly.
            val now = Time.nowMs()
            val cachedMain = moveIntoCache(plainFile, slot.key, MimeTypes.extensionFor(prepared.mime))
            db.mediaCache().upsert(MediaCacheEntity(slot.key, cachedMain.absolutePath, cachedMain.length(), now))
            if (thumbKey != null && thumbPlain.isFile) {
                val cachedThumb = moveIntoCache(thumbPlain, thumbKey, "jpg")
                db.mediaCache().upsert(MediaCacheEntity(thumbKey, cachedThumb.absolutePath, cachedThumb.length(), now))
            }
            setProgress(progressKey, 1f)

            Attachment(
                key = slot.key,
                mime = prepared.mime,
                sizeBytes = plainSize,
                fileName = prepared.fileName,
                width = prepared.width,
                height = prepared.height,
                durationMs = prepared.durationMs,
                enc = AttachmentCipher.ENC,
                fileKey = Base64Url.encode(fileKey),
                baseIv = Base64Url.encode(baseIv),
                chunkSize = chunkSize,
                sha256 = Base64Url.encode(sha),
                thumbKey = thumbKey,
                thumbSha256 = thumbSha,
            )
        } finally {
            work.deleteRecursively()
            clearProgress(progressKey)
        }
    }

    private class Prepared(val mime: String, val fileName: String?, val width: Int?, val height: Int?, val durationMs: Long?)

    private fun prepareImage(local: Uri, mime: String, plainFile: File, thumbPlain: File, name: String?): Prepared {
        if (MimeTypes.isGif(mime)) {
            copyUri(local, plainFile, serverConfig.current.value.maxUploadBytes)
            val dims = ImageScaler.readDimensions(plainFile)
            // A still thumbnail of the first frame keeps the list cheap (no animated decode in bubbles).
            runCatching { Thumbnails.imageThumbnail(plainFile, thumbPlain) }
            return Prepared(MimeTypes.GIF, name ?: "animation.gif", dims?.width, dims?.height, null)
        }
        val bmp = ImageScaler.decodeScaled(appContext, local, ImageScaler.UPLOAD_MAX_SIDE) ?: throw MediaException.Unreadable()
        try {
            ImageScaler.writeJpeg(bmp, plainFile, ImageScaler.UPLOAD_JPEG_QUALITY)
            Thumbnails.fromBitmap(bmp, thumbPlain)
            val base = name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "photo"
            return Prepared(MimeTypes.JPEG, "$base.jpg", bmp.width, bmp.height, null)
        } finally {
            bmp.recycle()
        }
    }

    private fun prepareVideo(local: Uri, mime: String, plainFile: File, thumbPlain: File, name: String?, maxBytes: Long): Prepared {
        copyUri(local, plainFile, maxBytes)
        val meta = Thumbnails.probe(appContext, Uri.fromFile(plainFile))
        runCatching { Thumbnails.videoThumbnail(appContext, Uri.fromFile(plainFile), thumbPlain) }
        val m = MimeTypes.normalize(mime).let { if (it == MimeTypes.OCTET_STREAM) MimeTypes.guessFromName(name) ?: MimeTypes.MP4 else it }
        return Prepared(m, name ?: ("video." + MimeTypes.extensionFor(m)), meta.width, meta.height, meta.durationMs)
    }

    private fun prepareAudio(local: Uri, mime: String, plainFile: File, name: String?, maxBytes: Long): Prepared {
        copyUri(local, plainFile, maxBytes)
        val meta = Thumbnails.probe(appContext, Uri.fromFile(plainFile))
        val m = MimeTypes.normalize(mime).let { if (it == MimeTypes.OCTET_STREAM) MimeTypes.guessFromName(name) ?: MimeTypes.M4A else it }
        return Prepared(m, name ?: ("voice." + MimeTypes.extensionFor(m)), null, null, meta.durationMs)
    }

    private fun prepareFile(local: Uri, mime: String, plainFile: File, name: String?, maxBytes: Long): Prepared {
        copyUri(local, plainFile, maxBytes)
        val m = MimeTypes.normalize(mime).let { if (it == MimeTypes.OCTET_STREAM) MimeTypes.guessFromName(name) ?: it else it }
        return Prepared(m, name ?: ("file." + MimeTypes.extensionFor(m)), null, null, null)
    }

    override suspend fun uploadAvatar(local: Uri, mime: String): String = withContext(Dispatchers.IO) {
        val work = File(tmpDir, "av_" + Ulid.generate()).apply { mkdirs() }
        try {
            val bmp = ImageScaler.decodeScaled(appContext, local, ImageScaler.AVATAR_MAX_SIDE) ?: throw MediaException.Unreadable()
            val jpeg = File(work, "avatar.jpg")
            try {
                ImageScaler.writeJpeg(bmp, jpeg, ImageScaler.UPLOAD_JPEG_QUALITY)
            } finally {
                bmp.recycle()
            }
            val slot = api.uploadUrl(UploadUrlRequest(kind = MediaKind.AVATAR, mime = MimeTypes.JPEG, sizeBytes = jpeg.length()))
            val headers = if (slot.headers.keys.any { it.equals("Content-Type", true) }) slot.headers else slot.headers + ("Content-Type" to MimeTypes.JPEG)
            api.upload(slot.uploadUrl, slot.method, headers, jpeg)
            slot.key
        } finally {
            work.deleteRecursively()
        }
    }

    override suspend fun avatarUrl(avatarKey: String): String? {
        val now = Time.nowMs()
        avatarUrls[avatarKey]?.let { (url, expiresAt) -> if (now < expiresAt - AVATAR_URL_SLACK_MS) return url }
        return try {
            val r = api.downloadUrl(avatarKey)
            avatarUrls[avatarKey] = r.downloadUrl to r.expiresAt
            r.downloadUrl
        } catch (_: IOException) {
            null
        }
    }

    // ---------- cache management ----------

    override suspend fun trimCache(maxBytes: Long) = withContext(Dispatchers.IO) {
        val dao = db.mediaCache()
        val total = dao.totalSize()
        if (total <= maxBytes) return@withContext
        val rows = dao.leastRecentlyUsed(Int.MAX_VALUE)
        val plan = CacheTrim.plan(rows.map { CacheTrim.Entry(it.key, it.sizeBytes, it.lastUsedAt) }, maxBytes)
        for (e in plan) {
            val row = rows.firstOrNull { it.key == e.key } ?: continue
            lockFor(row.key).withLock {
                File(row.localPath).delete()
                dao.delete(row.key)
            }
        }
        sweepTmp()
    }

    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        db.mediaCache().clear()
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        avatarUrls.clear()
        Unit
    }

    /** Fire-and-forget trim from lifecycle hooks (never blocks the caller). */
    fun trimInBackground(maxBytes: Long = DEFAULT_CACHE_BYTES) {
        appScope.launch { runCatching { trimCache(maxBytes) } }
    }

    // ---------- helpers ----------

    private fun cacheFile(key: String, ext: String): File = File(cacheDir, sha256Hex(key) + "." + ext)

    private fun moveIntoCache(src: File, key: String, ext: String): File {
        val target = cacheFile(key, ext)
        if (target.exists()) target.delete()
        if (!src.renameTo(target)) {
            src.copyTo(target, overwrite = true)
            src.delete()
        }
        return target
    }

    private fun encryptToFile(plain: File, cipher: File, fileKey: ByteArray, baseIv: ByteArray, objectKey: String, chunkSize: Int, thumbnail: Boolean): ByteArray =
        plain.inputStream().buffered(256 * 1024).use { input ->
            cipher.outputStream().buffered(256 * 1024).use { out ->
                AttachmentCipher.encryptStream(input, out, fileKey, baseIv, objectKey, chunkSize, thumbnail).sha256
            }
        }

    /** Streams a content:// or file:// Uri to [target]; aborts early with [MediaException.TooLarge]. */
    private fun copyUri(uri: Uri, target: File, maxBytes: Long) {
        declaredSize(uri)?.let { if (it > maxBytes) throw MediaException.TooLarge(it, maxBytes) }
        val input = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw MediaException.Unreadable(e)
        } ?: throw MediaException.Unreadable()
        target.parentFile?.mkdirs()
        var copied = 0L
        input.use { ins ->
            target.outputStream().buffered(256 * 1024).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    copied += n
                    if (copied > maxBytes) throw MediaException.TooLarge(copied, maxBytes)
                    out.write(buf, 0, n)
                }
            }
        }
    }

    private fun declaredSize(uri: Uri): Long? {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() }?.takeIf { it > 0 }
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) c.getLong(i) else null
                } else null
            }
        }.getOrNull()?.takeIf { it > 0 }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && !c.isNull(i)) c.getString(i) else null
                } else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.takeIf { it.contains('.') }
    }

    private fun sweepTmp() {
        val cutoff = Time.nowMs() - 6 * 60 * 60 * 1000L
        tmpDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
    }

    private fun setProgress(key: String, value: Float) {
        progress.value = progress.value + (key to value.coerceIn(0f, 1f))
    }

    private fun clearProgress(key: String) {
        progress.value = progress.value - key
    }

    companion object {
        const val DEFAULT_CACHE_BYTES = 512L * 1024 * 1024
        private const val AVATAR_URL_SLACK_MS = 60_000L

        fun sha256Hex(text: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(d.size * 2)
            for (b in d) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
