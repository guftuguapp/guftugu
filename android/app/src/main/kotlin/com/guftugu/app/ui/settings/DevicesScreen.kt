package com.guftugu.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.guftugu.app.R
import com.guftugu.app.core.auth.AuthError
import com.guftugu.app.core.util.Time
import com.guftugu.app.data.api.ApiException
import com.guftugu.app.di.AppGraph
import com.guftugu.app.protocol.Device
import com.guftugu.app.ui.join.UiMessage
import com.guftugu.app.ui.join.WarmBanner
import com.guftugu.app.ui.join.graphViewModel
import com.guftugu.app.ui.join.text
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.Forest
import com.guftugu.app.ui.theme.GoldDeep
import com.guftugu.app.ui.theme.GoldLight
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.LogoMedallion
import com.guftugu.app.ui.theme.Ornament
import com.guftugu.app.ui.theme.ParchmentCard
import com.guftugu.app.ui.theme.RiverbankBackground
import com.guftugu.app.ui.theme.SkyTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------- model ----------

/** Row model: everything pre-formatted so the list item does no work while scrolling. */
@Immutable
data class DeviceRow(
    val deviceId: String,
    val name: String,
    val model: String,
    val enrolledLabel: String,
    val lastSeenLabel: String,
    val isThisPhone: Boolean,
    val isRevoked: Boolean,
    val hasBiometric: Boolean,
)

fun Device.toRow(myDeviceId: String?, now: Long = Time.nowMs()): DeviceRow = DeviceRow(
    deviceId = deviceId,
    name = name.ifBlank { model ?: deviceId },
    model = listOfNotNull(model?.takeIf { it.isNotBlank() }, os?.takeIf { it.isNotBlank() }).joinToString(" · "),
    enrolledLabel = Time.formatDayLabel(enrolledAt, now),
    lastSeenLabel = lastSeenAt?.let { Time.formatShort(it, now) } ?: "",
    isThisPhone = deviceId == myDeviceId,
    isRevoked = revokedAt != null,
    hasBiometric = hasBiometricKey,
)

// ---------- view model ----------

/** `GET /me/devices` + `DELETE /me/devices/{id}` (PROTOCOL §5). */
class DevicesViewModel(private val graph: AppGraph) : ViewModel() {

    data class UiState(
        val devices: List<DeviceRow> = emptyList(),
        val loading: Boolean = true,
        val refreshing: Boolean = false,
        val revoking: String? = null,
        val message: UiMessage? = null,
        /** Device awaiting confirmation. */
        val confirm: DeviceRow? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { load(initial = true) }

    fun refresh() = load(initial = false)

    private fun load(initial: Boolean) {
        _state.update { it.copy(loading = initial && it.devices.isEmpty(), refreshing = !initial, message = null) }
        viewModelScope.launch {
            val myId = graph.serverConfig.current.value.deviceId
            try {
                val now = Time.nowMs()
                val rows = graph.api.myDevices()
                    .sortedWith(compareByDescending<Device> { it.deviceId == myId }.thenBy { it.revokedAt != null }.thenByDescending { it.lastSeenAt ?: it.enrolledAt })
                    .map { it.toRow(myId, now) }
                _state.update { it.copy(devices = rows, loading = false, refreshing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, refreshing = false, message = UiMessage.Auth(AuthError.fromApi(e.code, e.status, e.message ?: "", e))) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, message = UiMessage.Res(R.string.devices_error)) }
            }
        }
    }

    fun askRevoke(row: DeviceRow) {
        if (row.isThisPhone || row.isRevoked) return
        _state.update { it.copy(confirm = row) }
    }

    fun cancelRevoke() = _state.update { it.copy(confirm = null) }

    fun confirmRevoke() {
        val row = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null, revoking = row.deviceId, message = null) }
        viewModelScope.launch {
            try {
                graph.api.revokeDevice(row.deviceId)
                _state.update { s -> s.copy(revoking = null, devices = s.devices.map { if (it.deviceId == row.deviceId) it.copy(isRevoked = true) else it }) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(revoking = null, message = UiMessage.Auth(AuthError.fromApi(e.code, e.status, e.message ?: "", e))) }
            } catch (e: Exception) {
                _state.update { it.copy(revoking = null, message = UiMessage.Res(R.string.devices_error)) }
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}

// ---------- screen ----------

/** My devices (GET /me/devices) with revoke. */
@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val vm: DevicesViewModel = graphViewModel { DevicesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    DevicesContent(
        state = state,
        onBack = onBack,
        onRefresh = vm::refresh,
        onAskRevoke = vm::askRevoke,
        onCancelRevoke = vm::cancelRevoke,
        onConfirmRevoke = vm::confirmRevoke,
        onDismissMessage = vm::dismissMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesContent(
    state: DevicesViewModel.UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAskRevoke: (DeviceRow) -> Unit,
    onCancelRevoke: () -> Unit,
    onConfirmRevoke: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            SkyTopBar(
                title = stringResource(R.string.title_devices),
                subtitle = stringResource(R.string.devices_caption),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        RiverbankBackground(Modifier.padding(padding)) {
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val message = state.message
                    if (message != null) {
                        item(key = "message", contentType = "message") {
                            WarmBanner(message = message.text(), onDismiss = onDismissMessage)
                        }
                    }
                    if (state.loading) {
                        item(key = "loading", contentType = "loading") {
                            Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = GoldDeep, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                    if (!state.loading && state.devices.isEmpty() && message == null) {
                        item(key = "empty", contentType = "empty") { EmptyDevices() }
                    }
                    items(state.devices, key = { it.deviceId }, contentType = { "device" }) { row ->
                        DeviceCard(row = row, revoking = state.revoking == row.deviceId, onAskRevoke = onAskRevoke)
                    }
                    if (state.devices.any { !it.isThisPhone && !it.isRevoked }) {
                        item(key = "hint", contentType = "hint") {
                            Text(
                                stringResource(R.string.devices_swipe_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    val confirm = state.confirm
    if (confirm != null) {
        AlertDialog(
            onDismissRequest = onCancelRevoke,
            title = { Text(stringResource(R.string.devices_revoke_title, confirm.name), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.devices_revoke_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = onConfirmRevoke) { Text(stringResource(R.string.devices_revoke_confirm), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = onCancelRevoke) { Text(stringResource(R.string.cancel)) } },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceCard(row: DeviceRow, revoking: Boolean, onAskRevoke: (DeviceRow) -> Unit) {
    val canRevoke = !row.isThisPhone && !row.isRevoked && !revoking
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && canRevoke) onAskRevoke(row)
            false // never actually dismiss; the confirm dialog decides
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = canRevoke,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.large)
                    .padding(end = 22.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        ParchmentCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                DeviceGlyph(row)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (row.isRevoked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (row.isThisPhone) Tag(stringResource(R.string.devices_this_phone), Forest, GoldLight)
                        if (row.isRevoked) Tag(stringResource(R.string.devices_revoked), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    }
                    if (row.model.isNotEmpty()) {
                        Text(row.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.devices_enrolled, row.enrolledLabel) + " · " +
                            (if (row.lastSeenLabel.isEmpty()) stringResource(R.string.devices_never_seen) else stringResource(R.string.devices_last_seen, row.lastSeenLabel)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = if (row.hasBiometric) GoldDeep else MaterialTheme.colorScheme.outline, modifier = Modifier.size(13.dp))
                        Text(
                            stringResource(if (row.hasBiometric) R.string.devices_biometric else R.string.devices_password_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                if (revoking) {
                    CircularProgressIndicator(color = GoldDeep, strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(end = 2.dp))
                    Spacer(Modifier.width(14.dp))
                } else if (canRevoke) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.devices_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.colorScheme.surface) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.devices_revoke), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onAskRevoke(row) },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}

@Composable
private fun DeviceGlyph(row: DeviceRow) {
    Box(
        Modifier
            .size(44.dp)
            .background(if (row.isRevoked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .then(if (row.isThisPhone) Modifier.border(2.dp, Brushes.gold, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.PhoneAndroid,
            contentDescription = null,
            tint = if (row.isRevoked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun Tag(text: String, background: Color, foreground: Color) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = foreground, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun EmptyDevices() {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LogoMedallion(size = 96.dp)
        Ornament(Modifier.padding(top = 10.dp, bottom = 6.dp))
        Text(stringResource(R.string.devices_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- previews ----------

@Preview(showBackground = true)
@Composable
private fun DevicesPreview() {
    GuftuguTheme {
        DevicesContent(
            DevicesViewModel.UiState(
                loading = false,
                devices = listOf(
                    DeviceRow("d_1", "Ammi's Pixel", "Pixel 8 · Android 15", "Today", "14:05", isThisPhone = true, isRevoked = false, hasBiometric = true),
                    DeviceRow("d_2", "Old Oppo", "OPPO CPH2083 · Android 9", "12 Mar 2026", "Monday", isThisPhone = false, isRevoked = false, hasBiometric = false),
                    DeviceRow("d_3", "Lost phone", "Huawei AQM-LX1", "1 Jan 2026", "", isThisPhone = false, isRevoked = true, hasBiometric = true),
                ),
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}
