package com.guftugu.app.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.File

/** Thumbnails (≤ 512 px JPEG q70) and duration/size probing for video and audio. */
object Thumbnails {

    data class AvMeta(val durationMs: Long?, val width: Int?, val height: Int?)

    /** Duration + (rotation-corrected) frame size of a video or audio file; nulls when unreadable. */
    fun probe(context: Context, uri: Uri): AvMeta = withRetriever(context, uri) { r ->
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val swap = rot == 90 || rot == 270
        AvMeta(duration, if (swap) h else w, if (swap) w else h)
    } ?: AvMeta(null, null, null)

    /** First frame of a video scaled to ≤ [maxSide]. Null when the codec cannot decode it. */
    fun videoFrame(context: Context, uri: Uri, maxSide: Int = ImageScaler.THUMB_MAX_SIDE): Bitmap? = withRetriever(context, uri) { r ->
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide)
                ?: r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } else {
            r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        frame?.let { ImageScaler.fit(it, maxSide) }
    }

    /** Image thumbnail written next to nothing in particular: decode ≤ 512, JPEG q70 into [target]. */
    fun imageThumbnail(source: File, target: File, maxSide: Int = ImageScaler.THUMB_MAX_SIDE): Boolean {
        val bmp = ImageScaler.decodeScaled(source, maxSide) ?: return false
        return try {
            ImageScaler.writeJpeg(bmp, target, ImageScaler.THUMB_JPEG_QUALITY) > 0
        } finally {
            bmp.recycle()
        }
    }

    /** Thumbnail of an already-decoded bitmap (avoids decoding the original twice). */
    fun fromBitmap(bitmap: Bitmap, target: File, maxSide: Int = ImageScaler.THUMB_MAX_SIDE): Boolean {
        val copy = if (maxOf(bitmap.width, bitmap.height) <= maxSide) bitmap else {
            val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        }
        return try {
            ImageScaler.writeJpeg(copy, target, ImageScaler.THUMB_JPEG_QUALITY) > 0
        } finally {
            if (copy !== bitmap) copy.recycle()
        }
    }

    fun videoThumbnail(context: Context, uri: Uri, target: File): Boolean {
        val bmp = videoFrame(context, uri) ?: return false
        return try {
            ImageScaler.writeJpeg(bmp, target, ImageScaler.THUMB_JPEG_QUALITY) > 0
        } finally {
            bmp.recycle()
        }
    }

    private inline fun <T> withRetriever(context: Context, uri: Uri, block: (MediaMetadataRetriever) -> T?): T? {
        val r = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(context, uri)
            block(r)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
