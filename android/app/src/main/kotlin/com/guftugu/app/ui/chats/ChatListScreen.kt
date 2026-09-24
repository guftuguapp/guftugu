package com.guftugu.app.ui.chats

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guftugu.app.R
import com.guftugu.app.ui.common.AvatarImage
import com.guftugu.app.ui.common.EmptyState
import com.guftugu.app.ui.common.ErrorBanner
import com.guftugu.app.ui.common.PermissionRationale
import com.guftugu.app.ui.common.SearchPill
import com.guftugu.app.ui.common.SectionHeader
import com.guftugu.app.ui.common.graphViewModel
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.CraftListRow
import com.guftugu.app.ui.theme.GoldBadge
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.RiverbankBackground
import com.guftugu.app.ui.theme.RiverbankHeader
import com.guftugu.app.ui.theme.GoldFab
import com.guftugu.app.ui.theme.ChatWallpaper
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background

/** Conversations with local previews + unread badges (from Room). */
@Composable
fun ChatListScreen(
    onOpenChat: (convId: String) -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm = graphViewModel { ChatListViewModel(it.app, it.conversationRepository, it.mediaRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }
    ChatListContent(
        state = state,
        onQueryChange = vm::setQuery,
        onRefresh = vm::refresh,
        onDismissError = vm::dismissError,
        resolveAvatar = vm.resolveAvatar,
        onOpenChat = onOpenChat,
        onNewChat = onNewChat,
        onNewGroup = onNewGroup,
        onSettings = onSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListContent(
    state: ChatListState,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    resolveAvatar: (suspend (String) -> String?)?,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    onSettings: () -> Unit,
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    ChatWallpaper(bottomInset = 0.dp) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                RiverbankHeader(
                    title = stringResource(R.string.app_name),
                    actions = {
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) onQueryChange("") }) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.chats_search))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.chats_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chats_menu_new_group)) },
                                    onClick = { menuOpen = false; onNewGroup() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chats_menu_settings)) },
                                    onClick = { menuOpen = false; onSettings() },
                                )
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                GoldFab(onClick = onNewChat, contentDescription = stringResource(R.string.chats_new_chat))
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (searchOpen) {
                    SearchPill(
                        query = state.query,
                        onQueryChange = onQueryChange,
                        hint = stringResource(R.string.chats_search_hint),
                        onClose = { searchOpen = false; onQueryChange("") },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                ErrorBanner(state.error, onRetry = onRefresh, onDismiss = onDismissError)
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    if (state.loaded) ConversationList(state, resolveAvatar, onOpenChat) else Box(Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun ConversationList(
    state: ChatListState,
    resolveAvatar: (suspend (String) -> String?)?,
    onOpenChat: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item(key = "fingerprint", contentType = "nudge") { FingerprintNudge() }
        item(key = "notifications", contentType = "rationale") { NotificationRationale() }
        when {
            state.items.isEmpty() && state.filteredOut -> item(key = "empty", contentType = "empty") {
                EmptyState(title = stringResource(R.string.chats_no_results), medallion = false, modifier = Modifier.fillParentMaxHeight(0.7f))
            }
            state.items.isEmpty() -> item(key = "empty", contentType = "empty") {
                EmptyState(
                    title = stringResource(R.string.chats_empty_title),
                    caption = stringResource(R.string.chats_empty_caption),
                    modifier = Modifier.fillParentMaxHeight(0.85f),
                )
            }
            else -> {
                item(key = "header", contentType = "header") { SectionHeader(stringResource(R.string.chats_section)) }
                items(state.items, key = { it.convId }, contentType = { "conversation" }) { row ->
                    ConversationRow(row, resolveAvatar, onOpenChat)
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    row: ConversationUi,
    resolveAvatar: (suspend (String) -> String?)?,
    onOpenChat: (String) -> Unit,
) {
    val unread = row.unread > 0
    val edge = if (unread) Brushes.goldSoft else ReadEdge
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .shadow(1.dp, RowShape, clip = false)
            .clip(RowShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(if (unread) 1.3.dp else 1.dp, edge, RowShape)
            .drawBehind { if (unread) drawRect(UnreadAccent, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
            .clickable { onOpenChat(row.convId) },
    ) {
        CraftListRow(Modifier.padding(start = 2.dp)) {
            AvatarImage(
                name = row.title,
                avatarKey = row.avatarKey,
                resolveUrl = resolveAvatar,
                size = 50.dp,
                groupRing = row.isGroup,
            )
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    if (row.keyPending) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(end = 3.dp).size(14.dp),
                        )
                    }
                    Text(
                        row.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                Text(
                    row.stamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
                GoldBadge(row.unread, Modifier.padding(top = 6.dp))
            }
        }
    }
}

private val RowShape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
private val ReadEdge: androidx.compose.ui.graphics.Brush = androidx.compose.ui.graphics.SolidColor(com.guftugu.app.ui.theme.GoldEdge.copy(alpha = 0.45f))
private val UnreadAccent: androidx.compose.ui.graphics.Brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(com.guftugu.app.ui.theme.GoldLight, com.guftugu.app.ui.theme.GoldDeep))

/** POST_NOTIFICATIONS rationale (API 33+), shown until answered or dismissed. */
@Composable
private fun NotificationRationale() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    if (dismissed || granted) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        dismissed = true
    }
    PermissionRationale(
        icon = Icons.Outlined.Notifications,
        title = stringResource(R.string.notifications_rationale_title),
        text = stringResource(R.string.notifications_rationale_text),
        allowLabel = stringResource(R.string.notifications_rationale_allow),
        laterLabel = stringResource(R.string.notifications_rationale_later),
        onAllow = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        onLater = { dismissed = true },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatListPreview() {
    GuftuguTheme {
        ChatListContent(
            state = ChatListState(
                loaded = true,
                items = listOf(
                    ConversationUi("c_1", "Ammi", false, "You: see you at 8", "14:05", 0, false, null),
                    ConversationUi("c_2", "Cousins", true, "Sara: 📷 Photo", "Yesterday", 3, false, null),
                    ConversationUi("c_3", "Abbu", false, "Waiting for keys…", "Mon", 0, true, null),
                ),
            ),
            onQueryChange = {}, onRefresh = {}, onDismissError = {}, resolveAvatar = null,
            onOpenChat = {}, onNewChat = {}, onNewGroup = {}, onSettings = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatListEmptyPreview() {
    GuftuguTheme {
        ChatListContent(
            state = ChatListState(loaded = true),
            onQueryChange = {}, onRefresh = {}, onDismissError = {}, resolveAvatar = null,
            onOpenChat = {}, onNewChat = {}, onNewGroup = {}, onSettings = {},
        )
    }
}
