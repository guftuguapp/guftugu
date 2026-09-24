package com.guftugu.app.ui.media

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.guftugu.app.GuftuguApp
import com.guftugu.app.R
import com.guftugu.app.core.media.MimeTypes
import com.guftugu.app.core.media.Waveform
import com.guftugu.app.core.util.Time
import com.guftugu.app.data.repo.MediaRepository
import com.guftugu.app.protocol.Attachment
import com.guftugu.app.protocol.ContentType
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.GuftuguTheme
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Media widgets the chat UI composes with. Chat-ui calls these; this file implements them with
 * Coil (decrypted images), MediaPlayer (voice notes), MediaRecorder (hold-to-record) and the
 * system pickers / camera. Every widget reads decrypted files through [MediaRepository] and
 * never touches ciphertext or keys itself.
 *
 * Performance: no blur, no graphicsLayer in bubbles, colours/brushes allocated once, formatted
 * text remembered, and full-size images are only ever decoded in [MediaViewerScreen].
 */

// ---------- shared, allocated once ----------

private val Scrim45 = Color(0x73000000)
private val Scrim60 = Color(0x99000000)
private val RecordRed = Color(0xFFD7263D)
private val MediaCorner = RoundedCornerShape(14.dp)
private val ChipShape = RoundedCornerShape(50)
private const val BUBBLE_MIN_WIDTH = 200
private const val BUBBLE_MAX_WIDTH = 280

private class LoadedMedia(val file: File?, val isThumbnail: Boolean, val failed: Boolean)

@Composable
private fun rememberMediaRepository(): MediaRepository {
    val context = LocalContext.current
    return remember(context) { GuftuguApp.graph(context).mediaRepository }
}

/**
 * Opens the thumbnail first (usually already on disk → instant), then the full file unless
 * [thumbnailOnly]. [allowFullFallback] = false keeps videos from ever being downloaded by a bubble.
 */
@Composable
private fun rememberAttachmentFile(att: Attachment, thumbnailOnly: Boolean, allowFullFallback: Boolean): LoadedMedia? {
    val repo = rememberMediaRepository()
    val state by produceState<LoadedMedia?>(initialValue = null, att.key, thumbnailOnly, allowFullFallback) {
        val thumb = runCatching { withContext(Dispatchers.IO) { repo.openThumbnail(att) } }.getOrNull()
        if (thumb != null) value = LoadedMedia(thumb, isThumbnail = true, failed = false)
        val wantFull = !thumbnailOnly || (thumb == null && allowFullFallback)
        if (wantFull) {
            val full = runCatching { withContext(Dispatchers.IO) { repo.openAttachment(att) } }.getOrNull()
            if (full != null) value = LoadedMedia(full, isThumbnail = false, failed = false)
            else if (thumb == null) value = LoadedMedia(null, isThumbnail = false, failed = true)
        } else if (thumb == null) {
            value = LoadedMedia(null, isThumbnail = true, failed = true)
        }
    }
    return state
}

/** Keeps the bubble's shape stable before pixels arrive: aspect from metadata, else a sane minimum. */
private fun Modifier.mediaAspect(att: Attachment): Modifier {
    val w = att.width ?: 0
    val h = att.height ?: 0
    return if (w > 0 && h > 0) aspectRatio((w.toFloat() / h).coerceIn(0.5f, 2.2f)) else heightIn(min = 140.dp)
}

@Composable
private fun DecryptedPicture(file: File, modifier: Modifier, contentDescription: String?) {
    val context = LocalContext.current
    val request = remember(file.absolutePath) {
        ImageRequest.Builder(context)
            .data(file)
            .memoryCacheKey(file.absolutePath)
            .crossfade(MediaImageLoader.CROSSFADE_MS)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        imageLoader = MediaImageLoader.get(context),
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}

/** Parchment tile with a padlock: what a bubble shows while its attachment is still ciphertext. */
@Composable
private fun LockedPlaceholder(modifier: Modifier, icon: ImageVector = Icons.Outlined.Lock, busy: Boolean = true) {
    val craft = GuftuguTheme.craft
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(44.dp).border(1.dp, Brushes.goldSoft, CircleShape).padding(2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = craft.goldDeep, modifier = Modifier.size(22.dp))
            }
            if (busy) {
                Text(
                    stringResource(R.string.media_decrypting),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

// ---------- images ----------

/** Decrypted image (or its thumbnail while loading), fills [modifier]. Tap handled by caller. */
@Composable
fun AttachmentImage(att: Attachment, modifier: Modifier = Modifier, thumbnailOnly: Boolean = false) {
    val loaded = rememberAttachmentFile(att, thumbnailOnly = thumbnailOnly, allowFullFallback = true)
    val desc = stringResource(R.string.media_image)
    Box(modifier.mediaAspect(att).clip(MediaCorner)) {
        val file = loaded?.file
        if (file != null) {
            DecryptedPicture(file, Modifier.matchParentSize(), desc)
        } else {
            LockedPlaceholder(Modifier.matchParentSize(), icon = if (loaded?.failed == true) Icons.Outlined.Image else Icons.Outlined.Lock, busy = loaded?.failed != true)
        }
    }
}

// ---------- video ----------

/** Video thumbnail with a play badge and duration. Tap handled by caller (opens MediaViewerScreen). */
@Composable
fun AttachmentVideo(att: Attachment, modifier: Modifier = Modifier) {
    val loaded = rememberAttachmentFile(att, thumbnailOnly = true, allowFullFallback = false)
    val duration = remember(att.durationMs) { att.durationMs?.let { Time.formatDuration(it) } }
    val desc = stringResource(R.string.media_video)
    Box(modifier.mediaAspect(att).clip(MediaCorner)) {
        val file = loaded?.file
        if (file != null) {
            DecryptedPicture(file, Modifier.matchParentSize(), desc)
        } else {
            LockedPlaceholder(Modifier.matchParentSize(), icon = if (loaded?.failed == true) Icons.Outlined.Movie else Icons.Outlined.Lock, busy = loaded?.failed != true)
        }
        // play badge: dark disc with a gold hairline (single draw pass)
        Box(
            Modifier.align(Alignment.Center).size(54.dp).background(Scrim45, CircleShape).border(1.2.dp, Brushes.goldSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.media_play), tint = Color.White, modifier = Modifier.size(32.dp))
        }
        if (duration != null) {
            Text(
                duration,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Scrim60, ChipShape).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

// ---------- voice notes ----------

/** Voice note / audio file: play-pause, seek bar, duration. Self-contained playback. */
@Composable
fun VoiceNotePlayer(att: Attachment, modifier: Modifier = Modifier, isMine: Boolean = false) {
    val state = rememberVoicePlayer(att)
    val bars = remember(att.key) { Waveform.bars(att.key) }
    val playedColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val shownMs = if (state.isPlaying || state.positionMs > 0L) state.positionMs else state.durationMs
    val shownSec = shownMs / 1000
    val timeText = remember(shownSec) { Time.formatDuration(shownSec * 1000) }
    val playDesc = stringResource(R.string.media_play)
    val pauseDesc = stringResource(R.string.media_pause)

    Row(
        modifier.widthIn(min = BUBBLE_MIN_WIDTH.dp, max = BUBBLE_MAX_WIDTH.dp).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .background(playedColor, CircleShape)
                .border(1.dp, Brushes.goldSoft, CircleShape)
                .clip(CircleShape)
                .clickable(enabled = !state.isLoading) { state.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                state.isPlaying -> Icon(Icons.Filled.Pause, contentDescription = pauseDesc, tint = Color.White)
                else -> Icon(Icons.Filled.PlayArrow, contentDescription = playDesc, tint = Color.White)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            WaveformBars(
                bars = bars,
                progress = { state.progress },
                playedColor = playedColor,
                restColor = restColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .pointerInput(state) { detectTapGestures { p -> state.seekTo(p.x / size.width) } }
                    .pointerInput(state) {
                        detectHorizontalDragGestures(onDragStart = { p -> state.seekTo(p.x / size.width) }) { change, _ ->
                            state.seekTo(change.position.x / size.width)
                        }
                    },
            )
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (state.error) stringResource(R.string.media_open_failed) else timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 24 rounded bars; [progress] is read inside the draw lambda so seeking never recomposes. */
@Composable
private fun WaveformBars(bars: FloatArray, progress: () -> Float, playedColor: Color, restColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = bars.size
        if (n == 0) return@Canvas
        val slot = size.width / n
        val barW = slot * 0.55f
        val radius = CornerRadius(barW / 2f, barW / 2f)
        val played = (progress().coerceIn(0f, 1f) * n)
        for (i in 0 until n) {
            val h = (size.height * bars[i]).coerceAtLeast(barW)
            val x = i * slot + (slot - barW) / 2f
            val y = (size.height - h) / 2f
            drawRoundRect(if (i < played) playedColor else restColor, Offset(x, y), Size(barW, h), radius)
        }
    }
}

// ---------- files ----------

private fun fileIcon(mime: String, fileName: String?): ImageVector {
    val m = MimeTypes.normalize(mime)
    val ext = MimeTypes.extensionOf(fileName)
    return when {
        m == "application/pdf" || ext == "pdf" -> Icons.Outlined.PictureAsPdf
        m.startsWith("image/") -> Icons.Outlined.Image
        m.startsWith("video/") -> Icons.Outlined.Movie
        m.startsWith("audio/") -> Icons.Outlined.Audiotrack
        m.contains("zip") || m.contains("compressed") || m.contains("tar") || ext in setOf("zip", "rar", "7z", "gz", "tgz") -> Icons.Outlined.Archive
        m.contains("spreadsheet") || m.contains("excel") || m == "text/csv" || ext in setOf("xls", "xlsx", "csv") -> Icons.Outlined.TableChart
        m.startsWith("text/") || m.contains("word") || m.contains("document") || m.contains("presentation") -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
}

/** Generic file chip: icon, name, size; tap downloads+decrypts and opens with a system viewer. */
@Composable
fun FileAttachment(att: Attachment, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = rememberMediaRepository()
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    val icon = remember(att.mime, att.fileName) { fileIcon(att.mime, att.fileName) }
    val meta = remember(att.sizeBytes, att.mime, att.fileName) {
        val ext = MimeTypes.extensionOf(att.fileName) ?: MimeTypes.extensionFor(att.mime)
        ext.uppercase() + " · " + MimeTypes.formatSize(att.sizeBytes)
    }
    val name = att.fileName ?: stringResource(R.string.media_file)
    val failedText = stringResource(R.string.media_open_failed)
    val noAppText = stringResource(R.string.media_no_app)
    val craft = GuftuguTheme.craft

    Row(
        modifier
            .widthIn(min = BUBBLE_MIN_WIDTH.dp, max = BUBBLE_MAX_WIDTH.dp)
            .clip(MediaCorner)
            .clickable(enabled = !opening) {
                opening = true
                scope.launch {
                    val file = runCatching { withContext(Dispatchers.IO) { repo.openAttachment(att) } }.getOrNull()
                    opening = false
                    when {
                        file == null -> Toast.makeText(context, failedText, Toast.LENGTH_SHORT).show()
                        !PickerSupport.view(context, file, att.mime) -> Toast.makeText(context, noAppText, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).border(1.dp, Brushes.goldSoft, RoundedCornerShape(12.dp)).padding(1.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (opening) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = craft.goldDeep)
            else Icon(icon, contentDescription = null, tint = craft.goldDeep, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

// ---------- voice recorder ----------

/**
 * Hold-to-record microphone button. Calls [onRecorded] with a local m4a Uri and the
 * duration when the user releases (cancel by sliding away / very short recordings are dropped).
 *
 * While recording the composable widens to the left with a blinking red dot, a timer and
 * "slide to cancel"; it is meant to sit at the end of a composer row whose text field has weight.
 */
@Composable
fun VoiceRecorderButton(onRecorded: (uri: Uri, durationMs: Long) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    val onRecordedState = rememberUpdatedState(onRecorded)
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionText = stringResource(R.string.mic_permission_needed)
    val tooShortText = stringResource(R.string.mic_too_short)
    val recordDesc = stringResource(R.string.mic_record)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (!granted) Toast.makeText(context, permissionText, Toast.LENGTH_SHORT).show()
    }
    val cancelPx = with(LocalDensity.current) { 96.dp.toPx() }
    val haptic = LocalHapticFeedback.current

    DisposableEffect(recorder) { onDispose { recorder.cancel() } }
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.elapsedMs
            kotlinx.coroutines.delay(100)
        }
    }

    val gesture = Modifier.pointerInput(hasPermission) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!hasPermission) {
                waitForUpOrCancellation()
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@awaitEachGesture
            }
            if (runCatching { recorder.start() }.isFailure) {
                waitForUpOrCancellation()
                Toast.makeText(context, tooShortText, Toast.LENGTH_SHORT).show()
                return@awaitEachGesture
            }
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            recording = true
            elapsed = 0L
            dragX = 0f
            var cancelled = false
            var finished = false
            try {
                val startX = down.position.x
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    val dx = change.position.x - startX
                    dragX = dx.coerceAtMost(0f)
                    change.consume()
                    if (dx < -cancelPx) {
                        cancelled = true
                        break
                    }
                }
                finished = true
            } finally {
                recording = false
                dragX = 0f
                if (!finished || cancelled) {
                    recorder.cancel()
                    if (cancelled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                } else {
                    val result = recorder.stop()
                    if (result == null) {
                        Toast.makeText(context, tooShortText, Toast.LENGTH_SHORT).show()
                    } else {
                        onRecordedState.value(Uri.fromFile(result.first), result.second)
                    }
                }
            }
        }
    }

    Row(modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (recording) {
            val secs = elapsed / 1000
            val timer = remember(secs) { Time.formatDuration(secs * 1000) }
            Row(
                Modifier.offset { IntOffset(dragX.roundToInt(), 0) }.padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BlinkingDot()
                Spacer(Modifier.width(8.dp))
                Text(timer, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(14.dp))
                Icon(Icons.Outlined.ChevronLeft, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.mic_slide_to_cancel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .size(if (recording) 52.dp else 46.dp)
                .then(if (recording) Modifier.background(RecordRed, CircleShape) else Modifier.background(Brushes.gold, CircleShape))
                .border(1.5.dp, com.guftugu.app.ui.theme.GoldShadow, CircleShape)
                .semantics { contentDescription = recordDesc }
                .then(gesture),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = if (recording) Color.White else com.guftugu.app.ui.theme.Ink, modifier = Modifier.size(if (recording) 26.dp else 23.dp))
        }
    }
}

@Composable
private fun BlinkingDot() {
    val transition = rememberInfiniteTransition(label = "rec")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "recAlpha",
    )
    Box(Modifier.size(10.dp).drawBehind { drawCircle(RecordRed, alpha = alpha) })
}

// ---------- pickers ----------

/** Picked/captured attachment ready to send. */
data class PickedAttachment(val uri: Uri, val type: ContentType, val mime: String, val fileName: String?)

/** Launchers for the composer's attach menu. Handles runtime permissions and no-Google-services fallbacks. */
interface AttachmentPickers {
    fun pickImage()
    fun pickVideo()
    fun pickFile()
    fun captureImage()
    fun captureVideo()
}

private class CaptureInFlight {
    var file: File? = null
    var uri: Uri? = null
    var afterCameraPermission: (() -> Unit)? = null
}

@Composable
fun rememberAttachmentPickers(onPicked: (PickedAttachment) -> Unit): AttachmentPickers {
    val context = LocalContext.current
    val captureDir = remember(context) { GuftuguApp.graph(context).captureDir }
    val picked = rememberUpdatedState(onPicked)
    val inFlight = remember { CaptureInFlight() }
    val cameraText = stringResource(R.string.camera_permission_needed)
    val noPickerText = stringResource(R.string.picker_unavailable)

    val pickVisual = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) picked.value(PickerSupport.describe(context, uri))
    }
    val openImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            PickerSupport.takePersistable(context, uri)
            picked.value(PickerSupport.describe(context, uri, ContentType.IMAGE))
        }
    }
    val openVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            PickerSupport.takePersistable(context, uri)
            picked.value(PickerSupport.describe(context, uri, ContentType.VIDEO))
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            PickerSupport.takePersistable(context, uri)
            picked.value(PickerSupport.describe(context, uri))
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = inFlight.file
        val u = inFlight.uri
        inFlight.file = null
        inFlight.uri = null
        if (ok && f != null && u != null && f.isFile && f.length() > 0) {
            picked.value(PickedAttachment(u, ContentType.IMAGE, MimeTypes.JPEG, f.name))
        } else {
            f?.delete()
        }
    }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val f = inFlight.file
        val u = inFlight.uri
        inFlight.file = null
        inFlight.uri = null
        if (ok && f != null && u != null && f.isFile && f.length() > 0) {
            picked.value(PickedAttachment(u, ContentType.VIDEO, MimeTypes.MP4, f.name))
        } else {
            f?.delete()
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = inFlight.afterCameraPermission
        inFlight.afterCameraPermission = null
        if (granted) next?.invoke() else Toast.makeText(context, cameraText, Toast.LENGTH_SHORT).show()
    }

    return remember(pickVisual, openImage, openVideo, openFile, takePicture, captureVideo, cameraPermission) {
        object : AttachmentPickers {
            private fun toastNoPicker() = Toast.makeText(context, noPickerText, Toast.LENGTH_SHORT).show()

            private fun pickVisual(type: ActivityResultContracts.PickVisualMedia.VisualMediaType, fallback: () -> Unit) {
                val photoPicker = ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)
                if (photoPicker) {
                    try {
                        pickVisual.launch(PickVisualMediaRequest(type))
                        return
                    } catch (_: ActivityNotFoundException) {
                        // fall through to the document picker (no Google services / no photo picker)
                    }
                }
                fallback()
            }

            override fun pickImage() = pickVisual(ActivityResultContracts.PickVisualMedia.ImageOnly) {
                try { openImage.launch(arrayOf("image/*")) } catch (_: ActivityNotFoundException) { toastNoPicker() }
            }

            override fun pickVideo() = pickVisual(ActivityResultContracts.PickVisualMedia.VideoOnly) {
                try { openVideo.launch(arrayOf("video/*")) } catch (_: ActivityNotFoundException) { toastNoPicker() }
            }

            override fun pickFile() {
                try { openFile.launch(arrayOf("*/*")) } catch (_: ActivityNotFoundException) { toastNoPicker() }
            }

            private fun withCamera(action: () -> Unit) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    action()
                } else {
                    inFlight.afterCameraPermission = action
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }

            override fun captureImage() = withCamera {
                val (file, uri) = PickerSupport.newCapture(context, captureDir, "jpg")
                inFlight.file = file
                inFlight.uri = uri
                try { takePicture.launch(uri) } catch (_: ActivityNotFoundException) { file.delete(); toastNoPicker() }
            }

            override fun captureVideo() = withCamera {
                val (file, uri) = PickerSupport.newCapture(context, captureDir, "mp4")
                inFlight.file = file
                inFlight.uri = uri
                try { captureVideo.launch(uri) } catch (_: ActivityNotFoundException) { file.delete(); toastNoPicker() }
            }
        }
    }
}
