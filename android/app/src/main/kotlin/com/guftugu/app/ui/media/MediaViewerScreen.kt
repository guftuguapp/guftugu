package com.guftugu.app.ui.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.guftugu.app.GuftuguApp
import com.guftugu.app.R
import com.guftugu.app.core.media.MimeTypes
import com.guftugu.app.di.AppGraph
import com.guftugu.app.protocol.Attachment
import com.guftugu.app.protocol.ContentType
import com.guftugu.app.protocol.ProtocolJson
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.ParchmentCard
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- view model ----------

sealed interface MediaViewerState {
    data object Loading : MediaViewerState
    data object NotFound : MediaViewerState
    /** Attachment known; [file] arrives once decrypted. [failed] when the download/decrypt failed. */
    data class Ready(val type: ContentType, val att: Attachment, val caption: String?, val file: File?, val failed: Boolean = false) : MediaViewerState
}

class MediaViewerViewModel(private val graph: AppGraph, private val convId: String, private val msgId: String) : ViewModel() {
    private val _state = MutableStateFlow<MediaViewerState>(MediaViewerState.Loading)
    val state: StateFlow<MediaViewerState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    init {
        viewModelScope.launch {
            val entity = withContext(Dispatchers.IO) { graph.db.messages().get(msgId) }
            val att = entity?.attachmentJson?.let { runCatching { ProtocolJson.decodeFromString(Attachment.serializer(), it) }.getOrNull() }
            if (entity == null || entity.convId != convId || att == null) {
                _state.value = MediaViewerState.NotFound
                return@launch
            }
            val type = ContentType.fromWire(entity.contentType) ?: MimeTypes.contentTypeFor(att.mime, att.fileName)
            _state.value = MediaViewerState.Ready(type, att, entity.text, file = null)
            val file = runCatching { withContext(Dispatchers.IO) { graph.mediaRepository.openAttachment(att) } }.getOrNull()
            _state.value = MediaViewerState.Ready(type, att, entity.text, file, failed = file == null)
        }
    }

    fun share(context: Context, title: String, failedText: String) {
        val s = _state.value as? MediaViewerState.Ready ?: return
        val f = s.file ?: return
        if (!PickerSupport.share(context, f, s.att.mime, title)) _toasts.tryEmit(failedText)
    }

    fun save(context: Context, savedFormat: String, failedText: String) {
        val s = _state.value as? MediaViewerState.Ready ?: return
        val f = s.file ?: return
        viewModelScope.launch {
            val where = withContext(Dispatchers.IO) { runCatching { MediaSaver.save(context, f, s.att.mime, s.att.fileName, s.type) }.getOrNull() }
            _toasts.tryEmit(if (where != null) String.format(savedFormat, where) else failedText)
        }
    }

    class Factory(private val graph: AppGraph, private val convId: String, private val msgId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MediaViewerViewModel(graph, convId, msgId) as T
    }
}

/** Copies a decrypted file into the public gallery/downloads through MediaStore (API 29+) or the legacy Downloads folder. */
object MediaSaver {
    private const val FOLDER = "Guftugu"

    @Throws(IOException::class)
    fun save(context: Context, file: File, mime: String, fileName: String?, type: ContentType): String {
        val name = fileName?.takeIf { it.isNotBlank() } ?: (file.nameWithoutExtension + "." + MimeTypes.extensionFor(mime))
        val m = MimeTypes.normalize(mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (collection, relative) = when (type) {
                ContentType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES + "/" + FOLDER
                ContentType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES + "/" + FOLDER
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, m)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: throw IOException("insert failed")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("open failed")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: IOException) {
                resolver.delete(uri, null, null)
                throw e
            }
            return relative
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
            var target = File(dir, name)
            var n = 1
            while (target.exists()) target = File(dir, target.nameWithoutExtension.substringBefore(" (") + " (${n++})." + target.extension)
            file.copyTo(target, overwrite = false)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(m), null)
            return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
        }
    }

    /** Below API 29 the public Downloads folder needs WRITE_EXTERNAL_STORAGE. */
    fun needsLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
}

// ---------- screen ----------

private val ViewerScrim = Color(0xA6000000)

/** Image viewer / video player (media3) / audio player for one message's attachment. */
@Composable
fun MediaViewerScreen(convId: String, msgId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { GuftuguApp.graph(context) }
    val vm: MediaViewerViewModel = viewModel(key = "media/$convId/$msgId", factory = MediaViewerViewModel.Factory(graph, convId, msgId))
    val state by vm.state.collectAsStateWithLifecycle()
    MediaViewerContent(state = state, onBack = onBack, vm = vm)
}

@Composable
private fun MediaViewerContent(state: MediaViewerState, onBack: () -> Unit, vm: MediaViewerViewModel?) {
    val context = LocalContext.current
    var chrome by remember { mutableStateOf(true) }
    val shareTitle = stringResource(R.string.media_share)
    val openFailed = stringResource(R.string.media_open_failed)
    val savedFormat = stringResource(R.string.media_saved)
    val saveFailed = stringResource(R.string.media_save_failed)
    val savePermission = stringResource(R.string.media_save_permission)

    LaunchedEffect(vm) {
        vm?.toasts?.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val legacyWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm?.save(context, savedFormat, saveFailed) else Toast.makeText(context, savePermission, Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (state) {
            MediaViewerState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = GuftuguTheme.craft.gold)
            MediaViewerState.NotFound -> Text(
                stringResource(R.string.media_not_found),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            is MediaViewerState.Ready -> {
                val file = state.file
                when {
                    file == null && state.failed -> Text(openFailed, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                    file == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = GuftuguTheme.craft.gold)
                        Text(stringResource(R.string.media_decrypting), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    }
                    state.type == ContentType.IMAGE -> ZoomableImage(file, onTap = { chrome = !chrome }, modifier = Modifier.fillMaxSize())
                    state.type == ContentType.VIDEO -> VideoPlayer(file, modifier = Modifier.fillMaxSize())
                    state.type == ContentType.AUDIO -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        ParchmentCard { VoiceNotePlayer(state.att, Modifier.padding(12.dp).widthIn(max = 320.dp)) }
                    }
                    else -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        ParchmentCard { FileAttachment(state.att, Modifier.padding(8.dp)) }
                    }
                }
                if (chrome && !state.caption.isNullOrBlank()) {
                    Text(
                        state.caption,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(ViewerScrim).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        if (chrome) {
            val ready = state as? MediaViewerState.Ready
            val title = ready?.att?.fileName ?: when (ready?.type) {
                ContentType.IMAGE -> stringResource(R.string.media_image)
                ContentType.VIDEO -> stringResource(R.string.media_video)
                ContentType.AUDIO -> stringResource(R.string.media_audio)
                else -> stringResource(R.string.title_media)
            }
            val subtitle = ready?.let { remember(it.att.sizeBytes) { MimeTypes.formatSize(it.att.sizeBytes) } }
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().zIndex(1f).background(ViewerScrim).statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                val canAct = ready?.file != null
                IconButton(onClick = { vm?.share(context, shareTitle, openFailed) }, enabled = canAct) {
                    Icon(Icons.Outlined.Share, contentDescription = shareTitle, tint = if (canAct) Color.White else Color.White.copy(alpha = 0.4f))
                }
                IconButton(
                    onClick = {
                        if (MediaSaver.needsLegacyPermission(context)) legacyWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        else vm?.save(context, savedFormat, saveFailed)
                    },
                    enabled = canAct,
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.media_save), tint = if (canAct) Color.White else Color.White.copy(alpha = 0.4f))
                }
            }
        }
    }
}

/** Pinch-zoom / pan (1×–5×) with double-tap toggle. graphicsLayer is fine here: it is one full-screen image, not a list item. */
@Composable
private fun ZoomableImage(file: File, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (container.width * (s - 1f)) / 2f
        val maxY = (container.height * (s - 1f)) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    val transform = rememberTransformableState { zoom, pan, _ ->
        val newScale = (scale * zoom).coerceIn(1f, 5f)
        scale = newScale
        offset = clamp(offset + pan, newScale)
    }
    val request = remember(file.absolutePath) {
        ImageRequest.Builder(context).data(file).memoryCacheKey("viewer:" + file.absolutePath).crossfade(MediaImageLoader.CROSSFADE_MS).build()
    }
    Box(
        modifier
            .onSizeChanged { container = it }
            .pointerInput(file) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val target = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = target
                            offset = clamp((center - tap) * (target - 1f), target)
                        }
                    },
                )
            }
            .transformable(transform),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = stringResource(R.string.media_image),
            imageLoader = MediaImageLoader.get(context),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MediaViewerPreview() {
    GuftuguTheme { MediaViewerContent(state = MediaViewerState.Loading, onBack = {}, vm = null) }
}
