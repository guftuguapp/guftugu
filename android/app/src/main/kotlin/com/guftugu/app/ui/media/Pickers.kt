package com.guftugu.app.ui.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.guftugu.app.core.media.MimeTypes
import com.guftugu.app.core.util.Ulid
import com.guftugu.app.protocol.ContentType
import java.io.File

/** Helpers shared by the attach-menu pickers and camera capture (see [rememberAttachmentPickers]). */
internal object PickerSupport {
    fun authority(context: Context): String = context.packageName + ".fileprovider"

    /** Fresh capture target in AppGraph.captureDir, exposed to the camera app through FileProvider. */
    fun newCapture(context: Context, captureDir: File, ext: String): Pair<File, Uri> {
        captureDir.mkdirs()
        val file = File(captureDir, "cap_" + Ulid.generate() + "." + ext)
        return file to FileProvider.getUriForFile(context, authority(context), file)
    }

    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && !c.isNull(i)) c.getString(i) else null
                } else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun mimeOf(context: Context, uri: Uri, fallbackName: String?): String {
        val fromResolver = if (uri.scheme == "content") runCatching { context.contentResolver.getType(uri) }.getOrNull() else null
        val m = MimeTypes.normalize(fromResolver)
        if (m != MimeTypes.OCTET_STREAM) return m
        return MimeTypes.normalize(MimeTypes.guessFromName(fallbackName ?: uri.lastPathSegment))
    }

    /** Builds the composer's [PickedAttachment] for a picked/captured Uri, forcing [expected] when known. */
    fun describe(context: Context, uri: Uri, expected: ContentType? = null): PickedAttachment {
        val name = displayName(context, uri)
        val mime = mimeOf(context, uri, name)
        val type = when {
            expected == ContentType.IMAGE && !MimeTypes.isImage(mime) && !MimeTypes.isVideo(mime) -> ContentType.IMAGE
            expected == ContentType.VIDEO && !MimeTypes.isVideo(mime) && !MimeTypes.isImage(mime) -> ContentType.VIDEO
            else -> MimeTypes.contentTypeFor(mime, name)
        }
        return PickedAttachment(uri, type, mime, name)
    }

    fun takePersistable(context: Context, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Opens a decrypted cache file with the system viewer through FileProvider; false when nothing can. */
    fun view(context: Context, file: File, mime: String): Boolean {
        val uri = runCatching { FileProvider.getUriForFile(context, authority(context), file) }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MimeTypes.normalize(mime).let { if (it == MimeTypes.OCTET_STREAM) "*/*" else it })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun share(context: Context, file: File, mime: String, title: String): Boolean {
        val uri = runCatching { FileProvider.getUriForFile(context, authority(context), file) }.getOrNull() ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MimeTypes.normalize(mime).let { if (it == MimeTypes.OCTET_STREAM) "*/*" else it }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
