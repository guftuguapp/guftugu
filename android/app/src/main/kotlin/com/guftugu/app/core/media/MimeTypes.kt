package com.guftugu.app.core.media

import com.guftugu.app.protocol.ContentType
import java.util.Locale

/**
 * Pure (no Android) MIME helpers shared by the media repository, pickers and widgets.
 * Kept free of `android.*` so it is unit-testable on the JVM.
 */
object MimeTypes {
    const val OCTET_STREAM = "application/octet-stream"
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val GIF = "image/gif"
    const val WEBP = "image/webp"
    const val MP4 = "video/mp4"
    const val M4A = "audio/mp4"

    private val byExtension: Map<String, String> = mapOf(
        "jpg" to JPEG, "jpeg" to JPEG, "png" to PNG, "gif" to GIF, "webp" to WEBP, "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
        "mp4" to MP4, "m4v" to "video/x-m4v", "3gp" to "video/3gpp", "mkv" to "video/x-matroska", "webm" to "video/webm", "mov" to "video/quicktime",
        "m4a" to M4A, "aac" to "audio/aac", "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "opus" to "audio/opus", "wav" to "audio/wav", "flac" to "audio/flac", "amr" to "audio/amr",
        "pdf" to "application/pdf", "txt" to "text/plain", "csv" to "text/csv", "json" to "application/json", "zip" to "application/zip", "apk" to "application/vnd.android.package-archive",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    private val extensionByMime: Map<String, String> = buildMap {
        // reverse of the table, first extension wins for duplicated mimes (jpg for image/jpeg)
        for ((ext, mime) in byExtension) if (!containsKey(mime)) put(mime, ext)
        put(M4A, "m4a")
        put("audio/x-m4a", "m4a")
        put("audio/mp4a-latm", "m4a")
        put("audio/mpeg", "mp3")
    }

    fun normalize(mime: String?): String {
        val m = mime?.trim()?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim().orEmpty()
        return if (m.isEmpty() || !m.contains('/')) OCTET_STREAM else m
    }

    fun isImage(mime: String?) = normalize(mime).startsWith("image/")
    fun isVideo(mime: String?) = normalize(mime).startsWith("video/")
    fun isAudio(mime: String?) = normalize(mime).startsWith("audio/")
    fun isGif(mime: String?) = normalize(mime) == GIF

    /** Which `Content.type` an attachment with this MIME (or, failing that, file name) becomes. */
    fun contentTypeFor(mime: String?, fileName: String? = null): ContentType {
        val m = normalize(mime).let { if (it == OCTET_STREAM && fileName != null) normalize(guessFromName(fileName)) else it }
        return when {
            m.startsWith("image/") -> ContentType.IMAGE
            m.startsWith("video/") -> ContentType.VIDEO
            m.startsWith("audio/") -> ContentType.AUDIO
            else -> ContentType.FILE
        }
    }

    /** MIME from a file name's extension, or null when unknown. */
    fun guessFromName(fileName: String?): String? {
        val ext = extensionOf(fileName) ?: return null
        return byExtension[ext]
    }

    /** Lower-case extension without the dot ("jpg"), or null. */
    fun extensionOf(fileName: String?): String? {
        val name = fileName?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase(Locale.ROOT).takeIf { it.length in 1..8 && it.all { c -> c.isLetterOrDigit() } }
    }

    /** Preferred extension for a MIME ("jpg"), "bin" when unknown. */
    fun extensionFor(mime: String?): String {
        val m = normalize(mime)
        extensionByMime[m]?.let { return it }
        val sub = m.substringAfter('/')
        return sub.takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } } ?: "bin"
    }

    /** "812 B", "12.4 KB", "3.1 MB" — no locale-specific formatting so it is stable in tests. */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${oneDecimal(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${oneDecimal(mb)} MB"
        return "${oneDecimal(mb / 1024.0)} GB"
    }

    private fun oneDecimal(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r >= 100 || r == Math.floor(r)) r.toLong().toString() else r.toString()
    }
}
