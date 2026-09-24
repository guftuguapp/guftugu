package com.guftugu.app.ui.media

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.guftugu.app.GuftuguApp

/**
 * One Coil 3 [ImageLoader] for the whole app. It shares AppGraph's OkHttp client (avatars via
 * presigned URLs) and decodes the *decrypted* files in the media cache. Coil's own disk cache is
 * off: decrypted bytes already live in our LRU cache and a second plaintext copy would only
 * double the footprint. Built lazily on first use (DESIGN "lazy heavy objects").
 */
object MediaImageLoader {
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        instance ?: synchronized(this) { instance ?: build(context.applicationContext).also { instance = it } }

    private fun build(app: Context): ImageLoader =
        ImageLoader.Builder(app)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { GuftuguApp.graph(app).httpClient }))
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.15).build() }
            .diskCachePolicy(CachePolicy.DISABLED)
            .bitmapConfig(Bitmap.Config.RGB_565.takeIf { isLowRam(app) } ?: Bitmap.Config.ARGB_8888)
            .allowHardware(true)
            .crossfade(CROSSFADE_MS)
            .build()

    private fun isLowRam(app: Context): Boolean {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        return am.isLowRamDevice
    }

    const val CROSSFADE_MS = 120
}
