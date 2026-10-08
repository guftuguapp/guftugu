package com.guftugu.app.ui.join

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guftugu.app.R
import com.guftugu.app.data.repo.ServerInfo
import com.guftugu.app.ui.join.JoinServerViewModel.Mode
import com.guftugu.app.ui.join.JoinServerViewModel.UiState
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.CraftHeading
import com.guftugu.app.ui.theme.GoldButton
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.Ornament
import com.guftugu.app.ui.theme.ParchmentCard
import com.guftugu.app.ui.theme.RiverbankBackground
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Scan a QR / paste a link / enter apiUrl + code manually (PROTOCOL §1–§2). */
@Composable
fun JoinServerScreen(onJoin: (apiUrl: String, code: String) -> Unit) {
    val vm: JoinServerViewModel = graphViewModel { JoinServerViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val scanPrompt = stringResource(R.string.join_scan_prompt)
    val scanOptions = remember(scanPrompt) {
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(scanPrompt)
            .setBeepEnabled(false)
            .setOrientationLocked(true)
    }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result -> vm.onScanResult(result.contents) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanLauncher.launch(scanOptions) else vm.onCameraDenied()
    }

    // A guftugu://join deep link that arrived while this screen was (about to be) shown.
    LaunchedEffect(Unit) { vm.consumeDeepLink() }

    JoinServerContent(
        state = state,
        onScan = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                scanLauncher.launch(scanOptions)
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
        onPaste = { vm.pasteFromClipboard(context) },
        onManual = vm::showManual,
        onBack = vm::backToActions,
        onApiUrl = vm::setApiUrl,
        onCode = vm::setCode,
        onSubmitManual = vm::submitManual,
        onConfirm = { vm.confirmed()?.let { (api, code) -> onJoin(api, code) } },
        onDismissMessage = vm::dismissMessage,
        onStartOver = vm::startOver,
    )
}

/** Stateless body (previewable). */
@Composable
fun JoinServerContent(
    state: UiState,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit,
    onApiUrl: (String) -> Unit,
    onCode: (String) -> Unit,
    onSubmitManual: () -> Unit,
    onConfirm: () -> Unit,
    onDismissMessage: () -> Unit,
    onStartOver: () -> Unit,
) {
    RiverbankBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            GlowMedallion(size = 180.dp)
            Spacer(Modifier.height(18.dp))
            CraftHeading(title = stringResource(R.string.app_name), caption = stringResource(R.string.join_tagline))
            Spacer(Modifier.height(26.dp))

            ParchmentCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (state.mode) {
                        Mode.ACTIONS -> ActionsBlock(onScan, onPaste, onManual)
                        Mode.MANUAL -> ManualBlock(state, onApiUrl, onCode, onSubmitManual, onBack)
                        Mode.CHECKING -> CheckingBlock()
                        Mode.FOUND -> FoundBlock(state.server, onConfirm, onBack)
                        Mode.REVOKED -> RevokedBlock(state.revokedServer, state.resetting, onStartOver)
                    }
                    val message = state.message?.text()
                    if (message != null) {
                        Spacer(Modifier.height(16.dp))
                        WarmBanner(message = message, onDismiss = onDismissMessage)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Text(
                stringResource(R.string.join_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActionsBlock(onScan: () -> Unit, onPaste: () -> Unit, onManual: () -> Unit) {
    GoldButton(text = stringResource(R.string.join_manual), onClick = onManual, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    SecondaryAction(icon = { Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)) }, text = stringResource(R.string.join_scan), onClick = onScan)
    Spacer(Modifier.height(10.dp))
    SecondaryAction(icon = { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)) }, text = stringResource(R.string.join_paste), onClick = onPaste)
}

@Composable
private fun SecondaryAction(icon: @Composable () -> Unit, text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.2.dp, Brushes.goldSoft),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    ) {
        icon()
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ManualBlock(state: UiState, onApiUrl: (String) -> Unit, onCode: (String) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit) {
    Text(stringResource(R.string.join_manual), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(14.dp))
    // A build made for a specific server (BuildConfig.DEFAULT_SERVER_URL) never asks for an address.
    if (com.guftugu.app.BuildConfig.DEFAULT_SERVER_URL.isEmpty()) {
        CraftTextField(
            value = state.apiUrl,
            onValueChange = onApiUrl,
            label = stringResource(R.string.join_server_url),
            placeholder = stringResource(R.string.join_server_url_hint),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        )
        Spacer(Modifier.height(10.dp))
    }
    CraftTextField(
        value = state.code,
        onValueChange = onCode,
        label = stringResource(R.string.join_code),
        placeholder = stringResource(R.string.join_code_hint),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Go,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onGo = { if (state.manualReady) onSubmit() }),
    )
    Spacer(Modifier.height(16.dp))
    GoldButton(text = stringResource(R.string.join_continue), onClick = onSubmit, enabled = state.manualReady, modifier = Modifier.fillMaxWidth())
    CraftTextButton(text = stringResource(R.string.back), onClick = onBack, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun CheckingBlock() {
    Spacer(Modifier.height(8.dp))
    CircularProgressIndicator(color = GuftuguTheme.craft.goldDeep, strokeWidth = 2.5.dp, modifier = Modifier.size(34.dp))
    Spacer(Modifier.height(14.dp))
    Text(stringResource(R.string.join_checking), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun FoundBlock(server: ServerInfo?, onConfirm: () -> Unit, onBack: () -> Unit) {
    // Only the circle's name: people never need to see the server address (owner's rule).
    Text(stringResource(R.string.join_found_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Text(
        server?.name.orEmpty(),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Ornament(Modifier.padding(top = 6.dp, bottom = 6.dp), width = 90.dp)
    Spacer(Modifier.height(18.dp))
    GoldButton(text = stringResource(R.string.join_found_join), onClick = onConfirm, modifier = Modifier.fillMaxWidth())
    CraftTextButton(text = stringResource(R.string.join_found_change), onClick = onBack, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun RevokedBlock(serverName: String?, resetting: Boolean, onStartOver: () -> Unit) {
    Text(
        stringResource(R.string.join_revoked_title, serverName ?: stringResource(R.string.app_name)),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.join_revoked_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    if (resetting) {
        CircularProgressIndicator(color = GuftuguTheme.craft.goldDeep, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
    } else {
        GoldButton(text = stringResource(R.string.join_start_over), onClick = onStartOver, modifier = Modifier.fillMaxWidth())
    }
}

// ---------- previews ----------

@Preview(showBackground = true)
@Composable
private fun JoinServerPreview() {
    GuftuguTheme {
        JoinServerContent(UiState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun JoinServerFoundPreview() {
    GuftuguTheme {
        JoinServerContent(
            UiState(mode = Mode.FOUND, server = ServerInfo("https://chat.example.family", "wss://chat.example.family/ws", "Our family"), pendingCode = "GFT-7K3M-Q9XD"),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun JoinServerManualPreview() {
    GuftuguTheme(darkTheme = true) {
        JoinServerContent(
            UiState(mode = Mode.MANUAL, apiUrl = "https://chat.example.family", code = "GFT-7K3M", message = UiMessage.Res(R.string.join_invalid_code)),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}
