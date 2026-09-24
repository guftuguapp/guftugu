package com.guftugu.app.ui.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.guftugu.app.GuftuguApp
import com.guftugu.app.protocol.Attachment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice-note playback. One [MediaPlayer] app-wide: starting a note stops whichever one was
 * playing, so two bubbles never talk over each other. The player is created on first play and
 * released when the bubble that owns it leaves the screen or the note finishes.
 */
object VoicePlayback {
    private var player: MediaPlayer? = null
    private var owner: VoicePlayerState? = null

    @Synchronized
    fun play(state: VoicePlayerState, file: File, startMs: Int): Boolean {
        if (owner !== state) {
            owner?.onPreempted()
            release()
        }
        val p = player ?: MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
        return try {
            if (owner !== state || !state.prepared) {
                p.reset()
                p.setDataSource(file.absolutePath)
                p.prepare()
                state.prepared = true
                state.durationMs = p.duration.toLong().coerceAtLeast(0)
            }
            p.setOnCompletionListener { state.onCompleted() }
            if (startMs > 0) p.seekTo(startMs)
            p.start()
            player = p
            owner = state
            true
        } catch (_: Exception) {
            state.prepared = false
            release()
            false
        }
    }

    @Synchronized
    fun pause(state: VoicePlayerState) {
        if (owner === state) runCatching { player?.pause() }
    }

    @Synchronized
    fun seek(state: VoicePlayerState, ms: Int) {
        if (owner === state && state.prepared) runCatching { player?.seekTo(ms) }
    }

    @Synchronized
    fun positionOf(state: VoicePlayerState): Long =
        if (owner === state && state.prepared) runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: state.positionMs else state.positionMs

    @Synchronized
    fun stop(state: VoicePlayerState) {
        if (owner === state) {
            release()
            owner = null
        }
    }

    private fun release() {
        runCatching { player?.reset() }
        runCatching { player?.release() }
        player = null
        owner?.prepared = false
        owner = null
    }
}

/** Per-bubble state for [com.guftugu.app.ui.media.VoiceNotePlayer]. Cheap; keeps only numbers. */
@Stable
class VoicePlayerState internal constructor(private val scope: CoroutineScope, private val open: suspend () -> File) {
    var isPlaying by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        internal set
    var durationMs by mutableLongStateOf(0L)
        internal set
    /** 0..1 of the note that has been played, for the waveform. */
    var progress by mutableFloatStateOf(0f)
        private set

    internal var prepared = false
    private var file: File? = null
    private var ticker: Job? = null

    fun toggle() {
        if (isPlaying) pause() else play()
    }

    fun play() {
        if (isLoading) return
        val f = file
        if (f == null) {
            isLoading = true
            error = false
            scope.launch {
                val opened = runCatching { withContext(Dispatchers.IO) { open() } }.getOrNull()
                isLoading = false
                if (opened == null) {
                    error = true
                } else {
                    file = opened
                    startPlayback(opened)
                }
            }
        } else {
            startPlayback(f)
        }
    }

    private fun startPlayback(f: File) {
        val startAt = if (positionMs >= durationMs - 250 && durationMs > 0) 0 else positionMs.toInt()
        if (VoicePlayback.play(this, f, startAt)) {
            isPlaying = true
            error = false
            startTicker()
        } else {
            error = true
        }
    }

    fun pause() {
        VoicePlayback.pause(this)
        isPlaying = false
        stopTicker()
        positionMs = VoicePlayback.positionOf(this)
        updateProgress()
    }

    /** Seek to a fraction of the note; plays from there if it was playing. */
    fun seekTo(fraction: Float) {
        val d = if (durationMs > 0) durationMs else return
        val target = (d * fraction.coerceIn(0f, 1f)).toLong()
        positionMs = target
        updateProgress()
        if (prepared) VoicePlayback.seek(this, target.toInt())
    }

    internal fun onCompleted() {
        scope.launch {
            isPlaying = false
            stopTicker()
            positionMs = 0L
            progress = 0f
            VoicePlayback.stop(this@VoicePlayerState)
        }
    }

    internal fun onPreempted() {
        scope.launch {
            isPlaying = false
            stopTicker()
        }
    }

    fun dispose() {
        stopTicker()
        VoicePlayback.stop(this)
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive && isPlaying) {
                positionMs = VoicePlayback.positionOf(this@VoicePlayerState)
                updateProgress()
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun updateProgress() {
        progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    private companion object {
        const val TICK_MS = 120L
    }
}

/** Remembers a [VoicePlayerState] for [att]; the decrypted file is opened lazily on first play. */
@Composable
fun rememberVoicePlayer(att: Attachment): VoicePlayerState {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(att.key) {
        val repo = GuftuguApp.graph(context).mediaRepository
        VoicePlayerState(scope) { repo.openAttachment(att) }.also { it.durationMs = att.durationMs ?: 0L }
    }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}

/**
 * ExoPlayer over a decrypted local [file] inside a media3 [PlayerView]. Created lazily when this
 * composable enters composition and released when it leaves (DESIGN rule 6).
 */
@Composable
fun VideoPlayer(file: File, modifier: Modifier = Modifier, autoPlay: Boolean = true) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(file.absolutePath) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = autoPlay
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx: Context ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = true
                controllerAutoShow = true
                controllerShowTimeoutMs = 3000
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view -> if (view.player !== player) view.player = player },
        onRelease = { view -> view.player = null },
    )
}
