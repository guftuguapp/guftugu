package com.guftugu.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.guftugu.app.R
import com.guftugu.app.core.auth.AuthError
import com.guftugu.app.core.util.Time
import com.guftugu.app.data.api.ApiException
import com.guftugu.app.di.AppGraph
import com.guftugu.app.ui.join.UiMessage
import com.guftugu.app.ui.join.WarmBanner
import com.guftugu.app.ui.join.graphViewModel
import com.guftugu.app.ui.join.text
import com.guftugu.app.ui.navigation.JoinIntent
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.GoldDeep
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.Ornament
import com.guftugu.app.ui.theme.ParchmentCard
import com.guftugu.app.ui.theme.RiverbankBackground
import com.guftugu.app.ui.theme.SkyTopBar
import com.guftugu.app.ui.theme.goldBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- view model ----------

/** `POST /me/link-code` → QR + code + countdown (PROTOCOL §5, §2). */
class LinkDeviceViewModel(private val graph: AppGraph) : ViewModel() {

    data class UiState(
        val code: String? = null,
        val link: String? = null,
        val expiresAt: Long = 0L,
        val qr: ImageBitmap? = null,
        val loading: Boolean = true,
        val message: UiMessage? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { create() }

    fun create() {
        _state.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            try {
                val invite = graph.api.createLinkCode()
                val apiUrl = graph.serverConfig.current.value.apiUrl.orEmpty()
                val link = invite.link.trim().ifBlank { JoinIntent.link(apiUrl, invite.code) }
                val qr = withContext(Dispatchers.Default) { encodeQr(link, QR_PIXELS) }
                _state.update { it.copy(code = invite.code, link = link, expiresAt = invite.expiresAt, qr = qr, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, message = UiMessage.Auth(AuthError.fromApi(e.code, e.status, e.message ?: "", e))) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = UiMessage.Res(R.string.link_error)) }
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        const val QR_PIXELS = 640

        /** Renders [text] as a crisp black-on-transparent QR bitmap of [size] px (ZXing core). */
        fun encodeQr(text: String, size: Int): ImageBitmap {
            val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            val ink = 0xFF1F2A22.toInt() // Ink
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) pixels[row + x] = if (matrix[x, y]) ink else 0x00000000
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            return bmp.asImageBitmap()
        }
    }
}

// ---------- screen ----------

/** POST /me/link-code → show the invite as QR + text for enrolling another phone. */
@Composable
fun LinkDeviceScreen(onBack: () -> Unit) {
    val vm: LinkDeviceViewModel = graphViewModel { LinkDeviceViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LinkDeviceContent(
        state = state,
        onBack = onBack,
        onNewCode = vm::create,
        onCopy = { link -> copyToClipboard(context, link) },
        onDismissMessage = vm::dismissMessage,
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Guftugu invite", text))
}

@Composable
fun LinkDeviceContent(
    state: LinkDeviceViewModel.UiState,
    onBack: () -> Unit,
    onNewCode: () -> Unit,
    onCopy: (String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            SkyTopBar(
                title = stringResource(R.string.title_link_device),
                subtitle = stringResource(R.string.link_caption),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        RiverbankBackground(Modifier.padding(padding)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ParchmentCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.link_instructions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Ornament(Modifier.padding(top = 10.dp, bottom = 14.dp))
                        QrPanel(state.qr, state.loading)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.link_or_type), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.code ?: "GFT-····-····",
                            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
                            color = if (state.code != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Countdown(expiresAt = state.expiresAt, visible = state.code != null)
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { state.link?.let(onCopy) },
                                enabled = state.link != null,
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = MaterialTheme.shapes.extraLarge,
                                border = BorderStroke(1.2.dp, Brushes.goldSoft),
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.link_copy), style = MaterialTheme.typography.labelLarge)
                            }
                            OutlinedButton(
                                onClick = onNewCode,
                                enabled = !state.loading,
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = MaterialTheme.shapes.extraLarge,
                                border = BorderStroke(1.2.dp, Brushes.goldSoft),
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.link_new_code), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        val message = state.message?.text()
                        if (message != null) {
                            Spacer(Modifier.height(14.dp))
                            WarmBanner(message = message, onDismiss = onDismissMessage, action = {
                                if (state.code == null) {
                                    TextButton(onClick = onNewCode) { Text(stringResource(R.string.retry)) }
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrPanel(qr: ImageBitmap?, loading: Boolean) {
    Box(
        Modifier
            .size(236.dp)
            .goldBorder(width = 1.5.dp, shape = RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            qr != null -> Image(
                bitmap = qr,
                contentDescription = stringResource(R.string.link_qr_desc),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
            loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = GoldDeep, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
                Text(stringResource(R.string.link_working), style = MaterialTheme.typography.bodySmall, color = GoldDeep, modifier = Modifier.padding(top = 10.dp))
            }
            else -> Unit
        }
    }
}

/** "Expires in 23:59:41" ticking once a second; a single Long state, formatted only when it changes. */
@Composable
private fun Countdown(expiresAt: Long, visible: Boolean) {
    if (!visible || expiresAt <= 0L) return
    var remainingMs by remember(expiresAt) { mutableLongStateOf((expiresAt - Time.serverNowMs()).coerceAtLeast(0L)) }
    LaunchedEffect(expiresAt) {
        while (true) {
            remainingMs = (expiresAt - Time.serverNowMs()).coerceAtLeast(0L)
            if (remainingMs == 0L) break
            delay(1000L - (Time.nowMs() % 1000L))
        }
    }
    val label = remember(remainingMs / 1000L) { Time.formatDuration(remainingMs) }
    val expired = remainingMs == 0L
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Timer, contentDescription = null, tint = if (expired) MaterialTheme.colorScheme.error else GoldDeep, modifier = Modifier.size(14.dp))
        Text(
            if (expired) stringResource(R.string.link_expired) else stringResource(R.string.link_expires_in, label),
            style = MaterialTheme.typography.labelMedium,
            color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

// ---------- previews ----------

@Preview(showBackground = true)
@Composable
private fun LinkDevicePreview() {
    val qr = remember { LinkDeviceViewModel.encodeQr("guftugu://join?api=https%3A%2F%2Fchat.example.family&code=GFT-7K3M-Q9XD", 320) }
    GuftuguTheme {
        LinkDeviceContent(
            LinkDeviceViewModel.UiState(code = "GFT-7K3M-Q9XD", link = "guftugu://join?api=x&code=GFT-7K3M-Q9XD", expiresAt = Time.nowMs() + 86_400_000L, qr = qr, loading = false),
            {}, {}, {}, {},
        )
    }
}
