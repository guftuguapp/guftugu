package com.guftugu.app.ui.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.guftugu.app.core.util.Ulid
import java.io.File

/**
 * Thin MediaRecorder wrapper for voice notes: AAC in MPEG-4, 44.1 kHz, 96 kbps, mono, written to
 * `<cacheDir>/voice/<ulid>.m4a`. One recording at a time; every path releases the recorder.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null
    val elapsedMs: Long get() = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAt

    /** Starts recording; throws when the mic is busy or the recorder cannot prepare. */
    fun start(): File {
        cancel()
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val out = File(dir, Ulid.generate() + ".m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(SAMPLE_RATE)
            r.setAudioEncodingBitRate(BIT_RATE)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.reset() }
            runCatching { r.release() }
            out.delete()
            throw e
        }
        recorder = r
        file = out
        startedAt = SystemClock.elapsedRealtime()
        return out
    }

    /**
     * Stops and returns (file, durationMs), or null when the take is shorter than [minMs] or the
     * recorder failed (the file is deleted in both cases).
     */
    fun stop(minMs: Long = MIN_DURATION_MS): Pair<File, Long>? {
        val r = recorder ?: return null
        val f = file
        val duration = SystemClock.elapsedRealtime() - startedAt
        recorder = null
        file = null
        val ok = try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            false // nothing valid was written (too short / mic lost)
        } finally {
            runCatching { r.reset() }
            runCatching { r.release() }
        }
        if (!ok || f == null || duration < minMs || !f.isFile || f.length() == 0L) {
            f?.delete()
            return null
        }
        return f to duration
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.reset() }
        runCatching { r.release() }
        file?.delete()
        file = null
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 96_000
        const val MIN_DURATION_MS = 800L
    }
}
