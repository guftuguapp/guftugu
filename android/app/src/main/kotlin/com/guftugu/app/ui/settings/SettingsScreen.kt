package com.guftugu.app.ui.settings

import com.guftugu.app.core.auth.BiometricGate
import com.guftugu.app.ui.join.findFragmentActivity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guftugu.app.R
import com.guftugu.app.service.BackgroundConnection
import com.guftugu.app.ui.common.AvatarImage
import com.guftugu.app.ui.common.ErrorBanner
import com.guftugu.app.ui.common.graphViewModel
import com.guftugu.app.ui.media.rememberAttachmentPickers
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.GoldButton
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.Ink
import com.guftugu.app.ui.theme.Ornament
import com.guftugu.app.ui.theme.ParchmentCard
import com.guftugu.app.ui.theme.RiverbankBackground
import com.guftugu.app.ui.theme.SkyTopBar

/**
 * Profile, security, background connection, devices, link another phone, about, log out.
 * [onLoggedOut] runs after a successful logout (defaults to [onBack]; the NavGraph should route to the unlock screen).
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onDevices: () -> Unit, onLinkDevice: () -> Unit, onLoggedOut: () -> Unit = onBack) {
    val vm = graphViewModel { SettingsViewModel(it.app, it.userRepository, it.mediaRepository, it.authRepository, it.serverConfig) }
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LifecycleResumeEffect(vm) {
        vm.onResume()
        onPauseOrDispose { }
    }
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        vm.consumeNotice()
        snackbar.showSnackbar(n)
    }
    val pickers = rememberAttachmentPickers { picked -> vm.onAvatarPicked(picked.uri, picked.mime) }
    val context = LocalContext.current
    val activity = context.findFragmentActivity()
    SettingsContent(
        state = state,
        snackbar = snackbar,
        resolveAvatar = vm.resolveAvatar,
        onBack = onBack,
        onPickPhoto = pickers::pickImage,
        onSaveName = vm::setDisplayName,
        onChangePassword = vm::changePassword,
        onFingerprint = {
            when {
                state.fingerprintOn -> Unit
                state.biometrics == BiometricGate.Availability.AVAILABLE -> activity?.let(vm::enableFingerprint)
                else -> BiometricGate.openFingerprintSettings(context)
            }
        },
        onLockTimeout = vm::setLockTimeout,
        onBackgroundConnection = vm::setBackgroundConnection,
        onDevices = onDevices,
        onLinkDevice = onLinkDevice,
        onDismissError = vm::dismissError,
        onLogout = { vm.logout(onLoggedOut) },
    )
}

@Composable
fun SettingsContent(
    state: SettingsState,
    snackbar: SnackbarHostState,
    resolveAvatar: (suspend (String) -> String?)?,
    onBack: () -> Unit,
    onPickPhoto: () -> Unit,
    onSaveName: (String) -> Unit,
    onChangePassword: (current: String, new: String) -> Unit,
    onFingerprint: () -> Unit,
    onLockTimeout: (Int) -> Unit,
    onBackgroundConnection: (Boolean) -> Unit,
    onDevices: () -> Unit,
    onLinkDevice: () -> Unit,
    onDismissError: () -> Unit,
    onLogout: () -> Unit,
) {
    var nameDialog by rememberSaveable { mutableStateOf(false) }
    var passwordDialog by rememberSaveable { mutableStateOf(false) }
    var lockDialog by rememberSaveable { mutableStateOf(false) }
    var logoutDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var noticeHidden by remember { mutableStateOf(com.guftugu.app.service.QuietConnection.isNoticeHidden(context)) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        noticeHidden = com.guftugu.app.service.QuietConnection.isNoticeHidden(context)
    }

    RiverbankBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                SkyTopBar(
                    title = stringResource(R.string.title_settings),
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 6.dp), color = MaterialTheme.colorScheme.tertiary)
                ErrorBanner(state.error, onDismiss = onDismissError, modifier = Modifier.padding(bottom = 4.dp))

                // Profile
                SettingsCard(stringResource(R.string.settings_profile)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            AvatarImage(name = state.displayName, avatarKey = state.avatarKey, resolveUrl = resolveAvatar, size = 72.dp, ring = 2.5.dp)
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Brushes.gold)
                                    .clickable(onClick = onPickPhoto),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.PhotoCamera, contentDescription = stringResource(R.string.settings_change_photo), tint = Ink, modifier = Modifier.size(15.dp))
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(
                                state.displayName.ifEmpty { stringResource(R.string.settings_unknown) },
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // No circle/server name under the person: people aren't tied to a server (owner's rule).
                        }
                        IconButton(onClick = { nameDialog = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.settings_edit_name), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // Security
                SettingsCard(stringResource(R.string.settings_security)) {
                    SettingsRow(
                        Icons.Outlined.Fingerprint,
                        stringResource(R.string.settings_fingerprint),
                        subtitle = stringResource(
                            when {
                                state.fingerprintOn -> R.string.settings_fingerprint_is_on
                                state.biometrics == BiometricGate.Availability.AVAILABLE -> R.string.settings_fingerprint_turn_on
                                state.biometrics == BiometricGate.Availability.NONE_ENROLLED -> R.string.settings_fingerprint_add_first
                                else -> R.string.settings_fingerprint_no_sensor
                            },
                        ),
                        onClick = if (state.fingerprintOn || state.biometrics == BiometricGate.Availability.UNAVAILABLE) null else onFingerprint,
                    )
                    RowDivider()
                    SettingsRow(
                        Icons.Outlined.Key,
                        stringResource(R.string.settings_change_password),
                        subtitle = stringResource(if (state.hasPassword) R.string.settings_passcode_set else R.string.settings_passcode_not_set),
                        onClick = { passwordDialog = true },
                    )
                    RowDivider()
                    if (state.fingerprintOn) {
                        SettingsRow(
                            Icons.Outlined.Lock,
                            stringResource(R.string.settings_lock_timeout),
                            subtitle = lockLabel(state.lockTimeoutMinutes),
                            onClick = { lockDialog = true },
                        )
                    } else {
                        // Passcode phones stay signed in for the whole 30-day session (owner's rule).
                        SettingsRow(Icons.Outlined.Lock, stringResource(R.string.settings_lock_timeout), subtitle = stringResource(R.string.settings_lock_passcode_30d))
                    }
                }

                // Connection
                SettingsCard(stringResource(R.string.settings_connection)) {
                    SettingsRow(
                        Icons.Outlined.Wifi,
                        stringResource(R.string.settings_stay_connected),
                        subtitle = stringResource(R.string.settings_stay_connected_desc),
                        trailing = {
                            Switch(
                                checked = state.backgroundEnabled,
                                onCheckedChange = onBackgroundConnection,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.secondary,
                                    checkedThumbColor = Color.White,
                                ),
                            )
                        },
                        onClick = { onBackgroundConnection(!state.backgroundEnabled) },
                    )
                    RowDivider()
                    if (state.batteryExempt) {
                        SettingsRow(Icons.Outlined.Cloud, stringResource(R.string.settings_battery_title), subtitle = stringResource(R.string.settings_battery_ok))
                    } else {
                        SettingsRow(
                            Icons.Outlined.BatteryAlert,
                            stringResource(R.string.settings_battery_title),
                            subtitle = stringResource(R.string.settings_battery_hint),
                            tint = MaterialTheme.colorScheme.tertiary,
                            trailing = {
                                GoldButton(
                                    stringResource(R.string.settings_battery_button),
                                    onClick = { runCatching { context.startActivity(BackgroundConnection.batteryOptimizationIntent(context)) } },
                                )
                            },
                        )
                    }
                    // Messages and calls without the always-visible "connected" notice (owner's wish)
                    RowDivider()
                    SettingsRow(
                        Icons.Outlined.NotificationsOff,
                        stringResource(R.string.settings_quiet_notice),
                        subtitle = stringResource(if (noticeHidden) R.string.settings_quiet_notice_hidden else R.string.settings_quiet_notice_shown),
                        onClick = { com.guftugu.app.service.QuietConnection.open(context, com.guftugu.app.service.QuietConnection.noticeSettingsIntent(context)) },
                    )
                    RowDivider()
                    SettingsRow(
                        Icons.Outlined.BatteryChargingFull,
                        stringResource(R.string.settings_keep_running),
                        subtitle = stringResource(com.guftugu.app.service.QuietConnection.keepRunningHint()),
                        onClick = { com.guftugu.app.service.QuietConnection.open(context, com.guftugu.app.service.QuietConnection.keepRunningIntent(context)) },
                    )
                }

                // Devices
                SettingsCard(stringResource(R.string.settings_devices)) {
                    SettingsRow(Icons.Outlined.Devices, stringResource(R.string.settings_devices), subtitle = stringResource(R.string.settings_devices_desc), onClick = onDevices)
                    RowDivider()
                    SettingsRow(Icons.Outlined.Link, stringResource(R.string.settings_link_device), subtitle = stringResource(R.string.settings_link_device_desc), onClick = onLinkDevice)
                }

                // About
                SettingsCard(stringResource(R.string.settings_about)) {
                    SettingsRow(Icons.Outlined.Cloud, stringResource(R.string.settings_server), subtitle = state.serverName)
                    RowDivider()
                    SettingsRow(Icons.Outlined.Info, stringResource(R.string.settings_version), subtitle = state.version)
                }

                // Log out
                ParchmentCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    SettingsRow(
                        Icons.AutoMirrored.Outlined.Logout,
                        stringResource(R.string.settings_logout),
                        tint = MaterialTheme.colorScheme.error,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { logoutDialog = true },
                    )
                }
                Ornament(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp))
            }
        }
    }

    if (nameDialog) {
        NameDialog(initial = state.displayName, onSave = { nameDialog = false; onSaveName(it) }, onDismiss = { nameDialog = false })
    }
    if (passwordDialog) {
        PasswordDialog(needsCurrent = state.hasPassword, onSave = { c, n -> passwordDialog = false; onChangePassword(c, n) }, onDismiss = { passwordDialog = false })
    }
    if (lockDialog) {
        LockTimeoutDialog(current = state.lockTimeoutMinutes, onPick = { lockDialog = false; onLockTimeout(it) }, onDismiss = { lockDialog = false })
    }
    if (logoutDialog) {
        AlertDialog(
            onDismissRequest = { logoutDialog = false },
            title = { Text(stringResource(R.string.settings_logout_title), style = MaterialTheme.typography.headlineSmall) },
            text = { Text(stringResource(R.string.settings_logout_text)) },
            confirmButton = {
                TextButton(onClick = { logoutDialog = false; onLogout() }) {
                    Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { logoutDialog = false }) { Text(stringResource(R.string.cancel)) } },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

// ---------- pieces ----------

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    ParchmentCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 2.dp),
            )
            content()
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), thickness = 1.dp, color = GuftuguTheme.craft.hairline)
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 18.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
private fun lockLabel(minutes: Int): String = stringResource(
    when (minutes) {
        SettingsViewModel.LOCK_EVERY_TIME -> R.string.settings_lock_every_time
        1 -> R.string.settings_lock_1_min
        5 -> R.string.settings_lock_5_min
        30 -> R.string.settings_lock_30_min
        SettingsViewModel.LOCK_NEVER -> R.string.settings_lock_never
        else -> R.string.settings_lock_5_min
    },
)

@Composable
private fun NameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_display_name), style = MaterialTheme.typography.headlineSmall) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { GoldButton(stringResource(R.string.settings_save), onClick = { onSave(name) }, enabled = name.isNotBlank()) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun PasswordDialog(needsCurrent: Boolean, onSave: (current: String, new: String) -> Unit, onDismiss: () -> Unit) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val min = com.guftugu.app.core.auth.PasswordStrength.MIN_LENGTH
    val tooShort = new.isNotEmpty() && new.length < min
    val mismatch = confirm.isNotEmpty() && confirm != new
    val valid = (!needsCurrent || current.isNotEmpty()) && new.length >= min && confirm == new
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_change_password), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (needsCurrent) PasswordField(current, { current = it }, stringResource(R.string.settings_current_password))
                PasswordField(new, { new = it }, stringResource(R.string.settings_new_password), error = if (tooShort) stringResource(R.string.settings_password_short) else null)
                PasswordField(confirm, { confirm = it }, stringResource(R.string.settings_confirm_password), error = if (mismatch) stringResource(R.string.settings_password_mismatch) else null)
            }
        },
        confirmButton = { GoldButton(stringResource(R.string.settings_save), onClick = { onSave(current, new) }, enabled = valid) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = if (error != null) ({ Text(error) }) else null,
        visualTransformation = PasswordVisualTransformation(),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LockTimeoutDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_lock_timeout), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                for (choice in SettingsViewModel.LOCK_CHOICES) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = choice == current, onClick = { onPick(choice) })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == current, onClick = { onPick(choice) })
                        Text(lockLabel(choice), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    GuftuguTheme {
        SettingsContent(
            state = SettingsState(displayName = "Ammi", serverName = "Our family", batteryExempt = false, loaded = true),
            snackbar = remember { SnackbarHostState() },
            resolveAvatar = null,
            onBack = {}, onPickPhoto = {}, onSaveName = {}, onChangePassword = { _, _ -> }, onFingerprint = {}, onLockTimeout = {}, onBackgroundConnection = {},
            onDevices = {}, onLinkDevice = {}, onDismissError = {}, onLogout = {},
        )
    }
}
