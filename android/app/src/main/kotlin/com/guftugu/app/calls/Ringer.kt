package com.guftugu.app.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Incoming-call ringer that respects the phone's ringer mode (DESIGN.md "Incoming call"):
 * - `RINGER_MODE_NORMAL`  → default ringtone (looping) + vibration pattern
 * - `RINGER_MODE_VIBRATE` → vibration only
 * - `RINGER_MODE_SILENT`  → nothing at all
 *
 * The ringtone plays on the ring stream (`USAGE_NOTIFICATION_RINGTONE`), so the user's ring
 * volume applies and the media/alarm volumes are never touched.
 */
class Ringer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var ringing = false

    @Synchronized
    fun start() {
        if (ringing) return
        ringing = true
        when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> { playTone(); vibrate() }
            AudioManager.RINGER_MODE_VIBRATE -> vibrate()
            else -> Unit // silent: honour it
        }
    }

    @Synchronized
    fun stop() {
        if (!ringing) return
        ringing = false
        runCatching {
            player?.let { p ->
                if (p.isPlaying) p.stop()
                p.release()
            }
        }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun playTone() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(appContext, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure {
            runCatching { player?.release() }
            player = null
        }
    }

    private fun vibrate() {
        val v = resolveVibrator() ?: return
        if (!v.hasVibrator()) return
        vibrator = v
        runCatching {
            v.vibrate(VibrationEffect.createWaveform(PATTERN, 0))
        }
    }

    private fun resolveVibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    companion object {
        /** off 0 → on 900 ms → off 1300 ms, repeating. */
        private val PATTERN = longArrayOf(0L, 900L, 1300L)
    }
}
