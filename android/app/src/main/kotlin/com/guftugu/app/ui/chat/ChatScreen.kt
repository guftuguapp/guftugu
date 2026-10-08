package com.guftugu.app.ui.chat

import com.guftugu.app.ui.theme.ChatWallpaper

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guftugu.app.R
import com.guftugu.app.data.sync.ChatOpenTracker
import com.guftugu.app.ui.common.DateChip
import com.guftugu.app.ui.common.ErrorBanner
import com.guftugu.app.ui.common.graphViewModel
import com.guftugu.app.ui.media.AttachmentPickers
import com.guftugu.app.ui.media.rememberAttachmentPickers
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.SkyTopBar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Messages, bubbles, composer (text, attach, camera, voice note), typing indicator.
 * [onCall] receives the callId once [com.guftugu.app.calls.CallManager.startCall] has created the call.
 */
@Composable
fun ChatScreen(
    convId: String,
    onBack: () -> Unit,
    onOpenMedia: (msgId: String) -> Unit,
    onCall: (callId: String) -> Unit,
) {
    val vm = graphViewModel(key = "chat/$convId") {
        ChatViewModel(convId, it.app, it.messageRepository, it.conversationRepository, it.userRepository, it.callManager, it.serverConfig)
    }
    val header by vm.header.collectAsStateWithLifecycle()
    val list by vm.list.collectAsStateWithLifecycle()
    val composer by vm.composer.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val draft = vm.draft.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LifecycleResumeEffect(vm) {
        vm.setVisible(true)
        onPauseOrDispose { vm.setVisible(false) }
    }
    // The sync layer suppresses notifications / unread counts for the conversation on screen.
    DisposableEffect(convId) {
        ChatOpenTracker.opened(convId)
        onDispose { ChatOpenTracker.closed(convId) }
    }
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is ChatEffect.OpenCall -> onCall(effect.callId)
                is ChatEffect.Notice -> snackbar.showSnackbar(effect.text)
            }
        }
    }
    val pickers = rememberAttachmentPickers(onPicked = vm::onPicked)
    val options = graphViewModel(key = "chatOptions/$convId") { ChatOptionsViewModel(convId, it) }
    val opts by options.state.collectAsStateWithLifecycle()
    // First chat opened = first activity: starts the one-week clock for creating a passcode.
    val graphForActivity = com.guftugu.app.GuftuguApp.graph(androidx.compose.ui.platform.LocalContext.current)
    LaunchedEffect(Unit) { runCatching { graphForActivity.serverConfig.markFirstActivity(System.currentTimeMillis()) } }

    ChatContent(
        header = header,
        list = list,
        composer = composer,
        draft = draft,
        error = error,
        snackbar = snackbar,
        pickers = pickers,
        onBack = onBack,
        onCall = vm::startCall,
        onOpenMedia = onOpenMedia,
        onDraftChange = vm::onDraftChange,
        onSend = vm::send,
        onVoiceRecorded = vm::onVoiceRecorded,
        onReply = vm::replyTo,
        onDelete = vm::delete,
        onRetry = vm::retryFailed,
        onLoadOlder = vm::loadOlder,
        onDismissError = vm::dismissError,
        onConfirmCaption = vm::confirmCaption,
        onCancelCaption = vm::cancelCaption,
        menu = {
            ChatOverflowMenu(
                state = opts,
                onBlock = options::block,
                onUnblock = options::unblock,
                onLeave = { options.leave(onBack) },
                onInvite = options::inviteToGroup,
                onDismissInvite = options::dismissInvite,
            )
        },
        composerOverride = if (opts.blocked || opts.otherLeft) ({ ChatClosedBar(opts, onUnblock = options::unblock) }) else null,
    )
}

@Composable
fun ChatContent(
    header: ChatHeader,
    list: ChatListUi,
    composer: ComposerUi,
    draft: State<String>,
    error: String?,
    snackbar: SnackbarHostState,
    pickers: AttachmentPickers?,
    onBack: () -> Unit,
    onCall: (video: Boolean) -> Unit,
    onOpenMedia: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceRecorded: (android.net.Uri, Long) -> Unit,
    onReply: (MessageUi?) -> Unit,
    onDelete: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadOlder: () -> Unit,
    onDismissError: () -> Unit,
    onConfirmCaption: (String) -> Unit,
    onCancelCaption: () -> Unit,
    /** Extra top-bar actions (the ⋮ menu). */
    menu: @Composable () -> Unit = {},
    /** Shown instead of the composer (blocked / the other person left). */
    composerOverride: (@Composable () -> Unit)? = null,
) {
    var attachOpen by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<MessageUi?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.chat_copied)

    ChatWallpaper(leaves = true) {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SkyTopBar(
                title = header.title,
                subtitle = header.subtitle,
                alignStart = true,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    if (header.canCall) {
                        IconButton(onClick = { onCall(false) }) { Icon(Icons.Outlined.Call, contentDescription = stringResource(R.string.chat_audio_call)) }
                        IconButton(onClick = { onCall(true) }) { Icon(Icons.Outlined.Videocam, contentDescription = stringResource(R.string.chat_video_call)) }
                    }
                    menu()
                },
            )
        },
        bottomBar = {
            if (composerOverride != null) {
                androidx.compose.foundation.layout.Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) { composerOverride() }
            } else {
                Composer(
                    draft = draft,
                    replyTo = composer.replyTo,
                    onDraftChange = onDraftChange,
                    onSend = onSend,
                    onAttach = { attachOpen = true },
                    onVoiceRecorded = onVoiceRecorded,
                    onCancelReply = { onReply(null) },
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ErrorBanner(error, onDismiss = onDismissError)
            Box(Modifier.fillMaxSize()) {
                MessageList(
                    list = list,
                    onOpenMedia = onOpenMedia,
                    onLongPress = { actionTarget = it },
                    onRetry = onRetry,
                    onLoadOlder = onLoadOlder,
                )
            }
        }
    }
    }

    if (attachOpen && pickers != null) {
        AttachSheet(pickers = pickers, onDismiss = { attachOpen = false })
    }
    actionTarget?.let { target ->
        MessageActionSheet(
            message = target,
            onReply = { onReply(target) },
            onCopy = {
                val text = target.copyText ?: return@MessageActionSheet
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Guftugu", text)))
                    if (Build.VERSION.SDK_INT < 33) snackbar.showSnackbar(copied)
                }
            },
            onDelete = { deleteTarget = target.msgId },
            onDismiss = { actionTarget = null },
        )
    }
    deleteTarget?.let { id ->
        DeleteMessageDialog(onConfirm = { deleteTarget = null; onDelete(id) }, onDismiss = { deleteTarget = null })
    }
    composer.pendingCaption?.let { pending ->
        CaptionDialog(pending = pending, onSend = onConfirmCaption, onCancel = onCancelCaption)
    }
}

@Composable
private fun MessageList(
    list: ChatListUi,
    onOpenMedia: (String) -> Unit,
    onLongPress: (MessageUi) -> Unit,
    onRetry: () -> Unit,
    onLoadOlder: () -> Unit,
) {
    val listState: LazyListState = rememberLazyListState()
    val animatedIds = remember { HashSet<String>() }
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
    val scope = rememberCoroutineScope()

    // Newest item changed while we were at the bottom → stay at the bottom (instant, per DESIGN motion rules).
    val firstKey = list.items.firstOrNull()?.key
    LaunchedEffect(firstKey) {
        if (firstKey != null && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    // Reaching the top of history (end of the reversed list) loads older messages.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }.distinctUntilChanged().filter { it }.collect { onLoadOlder() }
    }

    Box(Modifier.fillMaxSize()) {
        if (list.loaded && list.items.isEmpty()) {
            Text(
                stringResource(R.string.chat_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list.items, key = { it.key }, contentType = { it.type }) { item ->
                when (item) {
                    is ChatItem.DateChip -> DateChip(item.label)
                    is ChatItem.Bubble -> MessageBubble(
                        model = item.message,
                        maxWidth = maxBubbleWidth,
                        animatedIds = animatedIds,
                        onOpenMedia = onOpenMedia,
                        onLongPress = onLongPress,
                        onRetry = onRetry,
                    )
                }
            }
        }
        if (showJump) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.scrollToItem(0) } },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 2.dp, focusedElevation = 2.dp, hoveredElevation = 2.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 14.dp)
                    .border(1.dp, Brushes.goldSoft, CircleShape),
            ) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_jump_to_bottom))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatPreview() {
    val items = listOf(
        ChatItem.Bubble(MessageUi("m_3", true, "You", false, BubbleBody.Text("On my way — https://maps.app/x", listOf(LinkSpan(12, 30, "https://maps.app/x"))), "14:07", Tick.READ, null, "On my way", false)),
        ChatItem.Bubble(MessageUi("m_2", false, "Ammi", true, BubbleBody.Text("Dinner at 8?", emptyList()), "14:05", Tick.NONE, ReplyQuote("m_1", "You", "Coming tonight?"), "Dinner at 8?", false)),
        ChatItem.Bubble(MessageUi("m_1", false, "Ammi", false, BubbleBody.CallLog("Missed audio call", false, true), "13:00", Tick.NONE, null, null, false)),
        ChatItem.DateChip("d_1", "Today"),
    )
    GuftuguTheme {
        ChatContent(
            header = ChatHeader("Ammi", "Online", false, true),
            list = ChatListUi(items, loaded = true),
            composer = ComposerUi(),
            draft = remember { mutableStateOf("") },
            error = null,
            snackbar = remember { SnackbarHostState() },
            pickers = null,
            onBack = {}, onCall = {}, onOpenMedia = {}, onDraftChange = {}, onSend = {}, onVoiceRecorded = { _, _ -> },
            onReply = {}, onDelete = {}, onRetry = {}, onLoadOlder = {}, onDismissError = {}, onConfirmCaption = {}, onCancelCaption = {},
        )
    }
}
