package com.guftugu.app.ui.calls

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * A [TextureView] video sink for the small local preview. Unlike `SurfaceViewRenderer` it is
 * drawn inside the view hierarchy, so Compose's `clip` (the 16dp card corners) applies to it.
 * The frame is centre-cropped to the view's aspect ratio (SCALE_ASPECT_FILL). Used only for the
 * 110dp preview; the full-screen remote video stays on a SurfaceView (no extra GPU copy).
 */
class TextureViewRenderer(context: Context) : TextureView(context), VideoSink, TextureView.SurfaceTextureListener {

    private val renderer = EglRenderer("GuftuguPreview")
    private var initialized = false

    /** Must be called once with the shared EGL context (texture frames come from that context). */
    fun init(sharedContext: EglBase.Context?) {
        if (initialized) return
        initialized = true
        renderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTextureListener = this
        surfaceTexture?.let { onSurfaceTextureAvailable(it, width, height) }
    }

    fun setMirror(mirror: Boolean) = renderer.setMirror(mirror)

    fun clearImage() = renderer.clearImage()

    /** Stops the render thread; the view must not be used afterwards. */
    fun release() {
        if (!initialized) return
        initialized = false
        renderer.release()
    }

    override fun onFrame(frame: VideoFrame) = renderer.onFrame(frame)

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (!initialized) return
        renderer.createEglSurface(surface)
        updateAspect(width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateAspect(width, height)

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val latch = CountDownLatch(1)
        renderer.releaseEglSurface { latch.countDown() }
        runCatching { latch.await(500, TimeUnit.MILLISECONDS) }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun updateAspect(width: Int, height: Int) {
        if (width > 0 && height > 0) renderer.setLayoutAspectRatio(width.toFloat() / height.toFloat())
    }
}
