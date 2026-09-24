package com.guftugu.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guftugu.app.R
import com.guftugu.app.di.AppGraph
import com.guftugu.app.domain.ConversationKind
import com.guftugu.app.protocol.Invite
import com.guftugu.app.ui.contacts.InviteReadyDialog
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.GoldButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ChatOptionsState(
    val loaded: Boolean = false,
    val isGroup: Boolean = false,
    val groupName: String? = null,
    val otherUserId: String? = null,
    val otherName: String = "",
    /** I blocked the other person (1:1 chats). */
    val blocked: Boolean = false,
    /** The other person left this 1:1 chat. */
    val otherLeft: Boolean = false,
    val busy: Boolean = false,
    val invite: Invite? = null,
    val error: String? = null,
)

/** Block / unblock, leave a chat or group, invite someone into a group (PROTOCOL §5a, §6). */
class ChatOptionsViewModel(private val convId: String, private val graph: AppGraph) : ViewModel() {
    private val blockedIds = MutableStateFlow<Set<String>>(emptySet())
    private val busy = MutableStateFlow(false)
    private val invite = MutableStateFlow<Invite?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val me = graph.serverConfig.config.map { it.userId }

    val state: StateFlow<ChatOptionsState> = combine(
        graph.conversationRepository.conversation(convId), me, graph.userRepository.users(), blockedIds, combine(busy, invite, error, ::Triple),
    ) { conv, myId, people, blocked, (isBusy, inv, err) ->
        if (conv == null) return@combine ChatOptionsState(busy = isBusy, invite = inv, error = err)
        val isGroup = conv.kind == ConversationKind.GROUP
        val otherId = if (isGroup) null else conv.members.firstOrNull { it.userId != myId }?.userId
        ChatOptionsState(
            loaded = true,
            isGroup = isGroup,
            groupName = if (isGroup) conv.title else null,
            otherUserId = otherId,
            otherName = if (isGroup) "" else (people.firstOrNull { it.userId == otherId }?.displayName ?: conv.title),
            blocked = otherId != null && otherId in blocked,
            otherLeft = !isGroup && conv.members.size < 2,
            busy = isBusy,
            invite = inv,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatOptionsState())

    init {
        viewModelScope.launch {
            runCatching { graph.api.friends() }.getOrNull()?.let { list -> blockedIds.value = list.filter { it.blocked }.map { it.user.userId }.toSet() }
        }
    }

    fun block() = act { id -> graph.api.block(id); blockedIds.value = blockedIds.value + id }
    fun unblock() = act { id -> graph.api.unblock(id); blockedIds.value = blockedIds.value - id }

    private fun act(block: suspend (String) -> Unit) {
        val id = state.value.otherUserId ?: return
        run { block(id) }
    }

    fun leave(onLeft: () -> Unit) = run {
        graph.conversationRepository.leave(convId)
        onLeft()
    }

    fun inviteToGroup() = run { invite.value = graph.api.createInvite("group", convId) }

    fun dismissInvite() { invite.value = null }
    fun dismissError() { error.value = null }

    private fun run(block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                error.value = graph.app.getString(R.string.chat_options_failed)
            } finally {
                busy.value = false
            }
        }
    }
}

/** The ⋮ menu in the chat's top bar. */
@Composable
fun ChatOverflowMenu(state: ChatOptionsState, onBlock: () -> Unit, onUnblock: () -> Unit, onLeave: () -> Unit, onInvite: () -> Unit, onDismissInvite: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // "block" | "leave"
    IconButton(onClick = { open = true }, enabled = state.loaded) {
        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.chats_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (state.isGroup) {
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_invite_group)) }, onClick = { open = false; onInvite() })
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_leave_group)) }, onClick = { open = false; confirm = "leave" })
        } else {
            if (state.otherUserId != null) {
                if (state.blocked) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_unblock, state.otherName)) }, onClick = { open = false; onUnblock() })
                } else {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_block, state.otherName)) }, onClick = { open = false; confirm = "block" })
                }
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_leave_chat)) }, onClick = { open = false; confirm = "leave" })
        }
    }
    when (confirm) {
        "block" -> ConfirmDialog(
            title = stringResource(R.string.chat_block_title, state.otherName),
            body = stringResource(R.string.chat_block_body),
            action = stringResource(R.string.chat_block_action),
            onConfirm = { confirm = null; onBlock() },
            onDismiss = { confirm = null },
        )
        "leave" -> ConfirmDialog(
            title = stringResource(if (state.isGroup) R.string.chat_leave_group_title else R.string.chat_leave_chat_title),
            body = stringResource(if (state.isGroup) R.string.chat_leave_group_body else R.string.chat_leave_chat_body),
            action = stringResource(R.string.chat_leave_action),
            onConfirm = { confirm = null; onLeave() },
            onDismiss = { confirm = null },
        )
    }
    state.invite?.let { InviteReadyDialog(invite = it, groupName = state.groupName, onDismiss = onDismissInvite) }
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { GoldButton(text = action, onClick = onConfirm) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** Replaces the composer when I blocked the other person, or they left the chat. */
@Composable
fun ChatClosedBar(state: ChatOptionsState, onUnblock: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Brushes.goldSoft, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (state.blocked) stringResource(R.string.chat_you_blocked, state.otherName) else stringResource(R.string.chat_other_left, state.otherName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (state.blocked) {
            Row { TextButton(onClick = onUnblock) { Text(stringResource(R.string.chat_menu_unblock, state.otherName)) } }
        }
    }
}
