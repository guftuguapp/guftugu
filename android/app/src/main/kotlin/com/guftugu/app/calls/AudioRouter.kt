package com.guftugu.app.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Routes call audio: `MODE_IN_COMMUNICATION` for the duration of the call, earpiece by default
 * for audio calls, loudspeaker for video calls, Bluetooth SCO when a headset is connected
 * (best effort), and everything restored when the call ends.
 *
 * All methods are cheap and safe to call from the main thread. Failures are swallowed: audio
 * routing is a nicety, a call must never crash because an OEM AudioManager misbehaves.
 */
class AudioRouter(context: Context) {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeaker: Boolean = false
    private var focusRequest: AudioFocusRequest? = null
    private var active = false

    /** Whether the loudspeaker is currently selected. */
    var speakerOn: Boolean = false
        private set

    /** Enter call mode; [speaker] is the initial route (video calls default to the loudspeaker). */
    fun start(speaker: Boolean) {
        if (active) {
            setSpeaker(speaker)
            return
        }
        active = true
        runCatching {
            savedMode = audioManager.mode
            @Suppress("DEPRECATION")
            savedSpeaker = audioManager.isSpeakerphoneOn
            requestFocus()
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        setSpeaker(speaker)
    }

    fun toggleSpeaker(): Boolean {
        setSpeaker(!speakerOn)
        return speakerOn
    }

    fun setSpeaker(on: Boolean) {
        speakerOn = on
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) routeModern(on) else routeLegacy(on)
        }
    }

    /** Leave call mode and restore what the system had before. */
    fun stop() {
        if (!active) return
        active = false
        speakerOn = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn) {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                }
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = savedSpeaker
            }
            audioManager.mode = savedMode
            abandonFocus()
        }
    }

    // ---------- API 31+: communication devices ----------

    private fun routeModern(speaker: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val devices = audioManager.availableCommunicationDevices
        val target = if (speaker) {
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }
        if (target != null) audioManager.setCommunicationDevice(target) else audioManager.clearCommunicationDevice()
    }

    // ---------- API 26–30: speakerphone + SCO ----------

    @Suppress("DEPRECATION")
    private fun routeLegacy(speaker: Boolean) {
        val bluetooth = !speaker && hasBluetoothOutput() && audioManager.isBluetoothScoAvailableOffCall
        if (bluetooth) {
            audioManager.isSpeakerphoneOn = false
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        } else {
            if (audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
            audioManager.isSpeakerphoneOn = speaker
        }
    }

    private fun hasBluetoothOutput(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    // ---------- focus ----------

    private fun requestFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { /* calls keep going through transient ducking */ }
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
