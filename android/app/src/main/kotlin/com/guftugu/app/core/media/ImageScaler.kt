package com.guftugu.app.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Memory-safe image decoding for uploads and thumbnails: bounds pass → `inSampleSize` → one
 * decode → EXIF orientation fix → optional down-scale. Never decodes more than ~4× the target
 * pixel count so a 48 MP photo stays well under the budget phone's heap.
 */
object ImageScaler {
    const val UPLOAD_MAX_SIDE = 2048
    const val UPLOAD_JPEG_QUALITY = 85
    const val THUMB_MAX_SIDE = 512
    const val THUMB_JPEG_QUALITY = 70
    const val AVATAR_MAX_SIDE = 512

    data class Dimensions(val width: Int, val height: Int)

    /** Pixel size without decoding pixels; swaps width/height for 90°/270° EXIF rotations. */
    fun readDimensions(open: () -> InputStream?): Dimensions? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null by design (it just fills outWidth/outHeight), so only
        // a stream that won't open means failure here; the size check below catches non-images.
        (open() ?: return null).use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val rotation = runCatching { open()?.use { exifRotation(it) } ?: 0 }.getOrDefault(0)
        return if (rotation == 90 || rotation == 270) Dimensions(opts.outHeight, opts.outWidth) else Dimensions(opts.outWidth, opts.outHeight)
    }

    fun readDimensions(context: Context, uri: Uri): Dimensions? =
        readDimensions { context.contentResolver.openInputStream(uri) }

    fun readDimensions(file: File): Dimensions? = readDimensions { if (file.exists()) file.inputStream() else null }

    /**
     * Decodes [open] scaled so the longest side is at most [maxSide], EXIF-rotated upright.
     * Returns null when the bytes are not an image.
     */
    fun decodeScaled(open: () -> InputStream?, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // bounds-only decode returns null by design: don't treat that as "unreadable" (it made every
        // photo and avatar upload fail with "cannot read the selected file")
        (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = runCatching { open()?.use { exifRotation(it) } ?: 0 }.getOrDefault(0)
        val upright = if (rotation != 0) rotate(raw, rotation) else raw
        return fit(upright, maxSide)
    }

    fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? =
        decodeScaled({ context.contentResolver.openInputStream(uri) }, maxSide)

    fun decodeScaled(file: File, maxSide: Int): Bitmap? =
        decodeScaled({ if (file.exists()) file.inputStream() else null }, maxSide)

    /** Scales [bitmap] down (never up) so the longest side is ≤ [maxSide]; recycles the source if a copy is made. */
    fun fit(bitmap: Bitmap, maxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = maxOf(w, h)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** Writes [bitmap] as JPEG to [target] (parent dirs created). Returns the file length. */
    @Throws(IOException::class)
    fun writeJpeg(bitmap: Bitmap, target: File, quality: Int): Long {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) throw IOException("jpeg compress failed")
            out.flush()
        }
        return target.length()
    }

    /** Largest power-of-two sample size that keeps the longest side ≥ [maxSide] (so quality survives the final scale). */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxSide) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun exifRotation(input: InputStream): Int =
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }
}
