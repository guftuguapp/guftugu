package com.guftugu.app.core.media

import java.security.MessageDigest

/**
 * Deterministic "waveform" for voice-note bubbles: [BARS] heights in 0.18..1.0 derived from a
 * SHA-256 of the object key. No audio decoding — the same note always draws the same bars on
 * every phone, and the bubble never touches the file to render.
 */
object Waveform {
    const val BARS = 24
    private const val MIN = 0.18f

    fun bars(key: String, count: Int = BARS): FloatArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val out = FloatArray(count)
        for (i in 0 until count) {
            val b = digest[i % digest.size].toInt() and 0xFF
            // blend neighbouring bytes so the bars roll like speech rather than white noise
            val n = digest[(i + 1) % digest.size].toInt() and 0xFF
            val v = (b * 0.65f + n * 0.35f) / 255f
            out[i] = MIN + (1f - MIN) * v
        }
        // an envelope: quieter at both ends, like a real recording
        for (i in 0 until count) {
            val t = i.toFloat() / (count - 1).coerceAtLeast(1)
            val env = 0.55f + 0.45f * kotlin.math.sin(t * Math.PI).toFloat()
            out[i] = (out[i] * env).coerceIn(MIN, 1f)
        }
        return out
    }
}
