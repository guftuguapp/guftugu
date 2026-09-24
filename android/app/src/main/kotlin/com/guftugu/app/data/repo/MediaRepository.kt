package com.guftugu.app.data.repo

import android.net.Uri
import com.guftugu.app.protocol.Attachment
import java.io.File

/** Encrypted attachments (PROTOCOL §9): phone ⇄ blob store via presigned URLs, decrypted disk cache. */
interface MediaRepository {
    /** Download + decrypt to the cache (idempotent; returns the cached file when present). */
    suspend fun openAttachment(att: Attachment): File
    suspend fun openThumbnail(att: Attachment): File?
    /** Encrypt with a fresh fileKey, upload, return the attachment metadata (incl. fileKey/baseIv/sha256). */
    suspend fun uploadEncrypted(convId: String, local: Uri, mime: String): Attachment
    /** Avatars are not E2E: plain upload, returns the `avatars/…` key. */
    suspend fun uploadAvatar(local: Uri, mime: String): String
    /** Presigned download URL for an avatar key (cached briefly). */
    suspend fun avatarUrl(avatarKey: String): String?
    /** Evict least-recently-used decrypted files above [maxBytes]. */
    suspend fun trimCache(maxBytes: Long)
    suspend fun clearCache()
}

class StubMediaRepository : MediaRepository {
    override suspend fun openAttachment(att: Attachment): File = throw NotImplementedError("implemented in feature phase")
    override suspend fun openThumbnail(att: Attachment): File? = throw NotImplementedError("implemented in feature phase")
    override suspend fun uploadEncrypted(convId: String, local: Uri, mime: String): Attachment = throw NotImplementedError("implemented in feature phase")
    override suspend fun uploadAvatar(local: Uri, mime: String): String = throw NotImplementedError("implemented in feature phase")
    override suspend fun avatarUrl(avatarKey: String): String? = throw NotImplementedError("implemented in feature phase")
    override suspend fun trimCache(maxBytes: Long) = throw NotImplementedError("implemented in feature phase")
    override suspend fun clearCache() = throw NotImplementedError("implemented in feature phase")
}
