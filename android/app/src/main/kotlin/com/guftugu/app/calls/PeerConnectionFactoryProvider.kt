package com.guftugu.app.calls

import android.content.Context
import android.media.AudioAttributes
import com.guftugu.app.protocol.IceServer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Lazily-created, process-wide WebRTC plumbing (DESIGN.md perf rule 6: WebRTC is created on
 * first use, never at startup). The [PeerConnectionFactory] and the shared [EglBase] are kept
 * alive between calls — creating them costs ~100 ms on a budget phone and the native library
 * cannot be unloaded anyway. Per-call objects (PeerConnection, tracks, capturer) are released
 * by [CallManagerImpl] after every call.
 *
 * The EglBase context is shared by the camera capturer, the encoder/decoder and every
 * `SurfaceViewRenderer` (renderers must be initialised with [eglContext] or texture frames
 * will not draw).
 */
object PeerConnectionFactoryProvider {

    private val lock = Any()

    @Volatile private var initialized = false
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null

    /** Shared EGL context for renderers. Safe to call from the UI without loading the native library's PeerConnectionFactory. */
    val eglContext: EglBase.Context
        get() = synchronized(lock) { (eglBase ?: EglBase.create().also { eglBase = it }).eglBaseContext }

    /** Loads the native library once (idempotent, cheap after the first call). */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .setFieldTrials("WebRTC-IntelVP8/Enabled/")
                    .createInitializationOptions(),
            )
            initialized = true
        }
    }

    fun factory(context: Context): PeerConnectionFactory {
        ensureInitialized(context)
        synchronized(lock) {
            factory?.let { return it }
            val egl = eglContext
            val adm = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .createAudioDeviceModule()
            audioModule = adm
            val created = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
                .setOptions(PeerConnectionFactory.Options().apply { disableNetworkMonitor = false })
                .createPeerConnectionFactory()
            factory = created
            return created
        }
    }

    /** Mutes the microphone at the audio-device level (in addition to disabling the track). */
    fun setMicrophoneMute(muted: Boolean) {
        synchronized(lock) { audioModule?.setMicrophoneMute(muted) }
    }

    /** Maps the server's ICE list (PROTOCOL §13 `IceServer`) to WebRTC's; falls back to Google STUN when empty. */
    fun iceServers(configured: List<IceServer>): List<PeerConnection.IceServer> {
        val mapped = configured.mapNotNull { s ->
            val urls = s.urls.filter { it.isNotBlank() }
            if (urls.isEmpty()) return@mapNotNull null
            PeerConnection.IceServer.builder(urls).apply {
                if (!s.username.isNullOrEmpty()) setUsername(s.username)
                if (!s.credential.isNullOrEmpty()) setPassword(s.credential)
            }.createIceServer()
        }
        return mapped.ifEmpty { listOf(PeerConnection.IceServer.builder(DEFAULT_STUN).createIceServer()) }
    }

    fun rtcConfiguration(servers: List<PeerConnection.IceServer>): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            keyType = PeerConnection.KeyType.ECDSA
        }

    private val DEFAULT_STUN = listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")
}
