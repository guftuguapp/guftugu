package com.guftugu.app.ui.calls

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * The remote video: a WebRTC [SurfaceViewRenderer] inside Compose. Initialised once with the
 * shared EGL context (frames are GPU textures from that context; a private context would
 * render black), attached to whichever [track] is current, and released when it leaves
 * composition.
 *
 * Cheap by construction: the SurfaceView draws on its own surface, so Compose never re-draws
 * video frames; recomposition only runs [AndroidView.update] when [track] or [mirror] change.
 */
@Composable
fun VideoRendererView(
    track: VideoTrack?,
    eglContext: EglBase.Context?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    scaling: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
) {
    val holder = remember { TrackHolder() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                runCatching { init(eglContext, null) }
                setEnableHardwareScaler(true)
                setScalingType(scaling)
                setMirror(mirror)
            }
        },
        update = { view ->
            view.setMirror(mirror)
            holder.attach(view, track)
        },
        onRelease = { view ->
            holder.attach(view, null)
            runCatching { view.clearImage() }
            runCatching { view.release() }
        },
    )
}

/**
 * The local preview: a [TextureViewRenderer], which honours Compose clipping (rounded card)
 * and always centre-crops to its box.
 */
@Composable
fun VideoPreviewView(
    track: VideoTrack?,
    eglContext: EglBase.Context?,
    modifier: Modifier = Modifier,
    mirror: Boolean = true,
) {
    val holder = remember { TrackHolder() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureViewRenderer(ctx).apply {
                runCatching { init(eglContext) }
                setMirror(mirror)
            }
        },
        update = { view ->
            view.setMirror(mirror)
            holder.attach(view, track)
        },
        onRelease = { view ->
            holder.attach(view, null)
            runCatching { view.clearImage() }
            runCatching { view.release() }
        },
    )
}

/** Remembers which track a sink is attached to so we add/remove exactly once per change. */
private class TrackHolder {
    private var current: VideoTrack? = null

    fun attach(sink: VideoSink, track: VideoTrack?) {
        if (current === track) return
        current?.let { old ->
            // The call may already have torn the track down; a disposed track has no sinks left.
            runCatching { if (!old.isDisposed) old.removeSink(sink) }
        }
        current = track
        if (track != null) runCatching { if (!track.isDisposed) track.addSink(sink) }
    }
}
