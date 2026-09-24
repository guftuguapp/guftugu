package com.guftugu.app.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guftugu.app.R
import com.guftugu.app.protocol.Invite
import com.guftugu.app.ui.common.EmptyState
import com.guftugu.app.ui.common.ErrorBanner
import com.guftugu.app.ui.common.InviteShare
import com.guftugu.app.ui.common.SearchPill
import com.guftugu.app.ui.common.SectionHeader
import com.guftugu.app.ui.common.graphViewModel
import com.guftugu.app.ui.common.rememberPhoneContactPicker
import com.guftugu.app.ui.theme.Brushes
import com.guftugu.app.ui.theme.GoldButton
import com.guftugu.app.ui.theme.GoldDeep
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.RiverbankBackground
import com.guftugu.app.ui.theme.SkyTopBar
import com.guftugu.app.ui.theme.goldBorder

/**
 * New chat: invite someone (a code sent by WhatsApp/SMS/email or to a phone contact), use a code
 * someone sent you, or pick one of "your people" (friends + people in your groups).
 */
@Composable
fun NewChatScreen(onBack: () -> Unit, onOpenChat: (convId: String) -> Unit) {
    val vm = graphViewModel { ContactsViewModel(it.app, it.userRepository, it.conversationRepository, it.mediaRepository, it.serverConfig, it.api) }
    val state by vm.state.collectAsStateWithLifecycle()
    val invite by vm.invite.collectAsStateWithLifecycle()
    var redeemOpen by rememberSaveable { mutableStateOf(false) }
    NewChatContent(
        state = state,
        onBack = onBack,
        onQueryChange = vm::setQuery,
        onDismissError = vm::dismissError,
        resolveAvatar = vm.resolveAvatar,
        onPick = { userId -> vm.openDirect(userId, onOpenChat) },
        onInvite = vm::createInvite,
        onHaveCode = { redeemOpen = true },
    )
    invite?.let { InviteReadyDialog(invite = it, groupName = null, onDismiss = vm::dismissInvite) }
    if (redeemOpen) {
        RedeemCodeDialog(
            onRedeem = { code -> redeemOpen = false; vm.redeem(code, onOpenChat) },
            onDismiss = { redeemOpen = false },
        )
    }
}

@Composable
fun NewChatContent(
    state: ContactsState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onDismissError: () -> Unit,
    resolveAvatar: (suspend (String) -> String?)?,
    onPick: (String) -> Unit,
    onInvite: () -> Unit = {},
    onHaveCode: () -> Unit = {},
) {
    RiverbankBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                SkyTopBar(
                    title = stringResource(R.string.title_new_chat),
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                SearchPill(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    hint = stringResource(R.string.contacts_search_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ActionCard(
                    icon = Icons.Outlined.PersonAdd,
                    title = stringResource(R.string.newchat_invite_title),
                    body = stringResource(R.string.newchat_invite_body),
                    onClick = onInvite,
                )
                ActionCard(
                    icon = Icons.Outlined.QrCode2,
                    title = stringResource(R.string.newchat_have_code_title),
                    body = stringResource(R.string.newchat_have_code_body),
                    onClick = onHaveCode,
                )
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.tertiary)
                ErrorBanner(state.error, onDismiss = onDismissError)
                when {
                    !state.loaded -> Box(Modifier.fillMaxSize())
                    state.items.isEmpty() && state.query.isEmpty() -> EmptyState(
                        title = stringResource(R.string.contacts_empty_title),
                        caption = stringResource(R.string.contacts_empty_caption),
                    )
                    state.items.isEmpty() -> EmptyState(title = stringResource(R.string.chats_no_results), medallion = false)
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item(key = "header", contentType = "header") { SectionHeader(stringResource(R.string.contacts_section)) }
                        items(state.items, key = { it.userId }, contentType = { "contact" }) { c ->
                            ContactRow(contact = c, resolveAvatar = resolveAvatar, onClick = { if (!state.busy) onPick(c.userId) })
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NewChatPreview() {
    GuftuguTheme {
        NewChatContent(
            state = ContactsState(
                loaded = true,
                items = listOf(ContactUi("u_1", "Ammi", null, true), ContactUi("u_2", "Abbu", null, false), ContactUi("u_3", "Sara", null, false)),
            ),
            onBack = {}, onQueryChange = {}, onDismissError = {}, resolveAvatar = null, onPick = {}, onInvite = {}, onHaveCode = {},
        )
    }
}


private val CardShape = RoundedCornerShape(18.dp)

/** A tappable parchment card with a gold-ringed icon (invite / have a code). */
@Composable
private fun ActionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Brushes.goldSoft, CardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).border(1.5.dp, Brushes.gold, CircleShape).padding(3.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = GoldDeep, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The invite is ready: show the code, then share it (any app) or text it to a phone contact. */
@Composable
fun InviteReadyDialog(invite: Invite, groupName: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = androidx.compose.runtime.remember(invite, groupName) { InviteShare.message(context, invite, groupName) }
    val pickContact = rememberPhoneContactPicker { phone -> InviteShare.sms(context, phone, text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (groupName != null) R.string.invite_ready_group_title else R.string.invite_ready_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    invite.code,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.goldBorder(shape = RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.invite_ready_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                GoldButton(text = stringResource(R.string.invite_share_any), onClick = { InviteShare.share(context, text) }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = pickContact, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Outlined.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.invite_text_contact), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** "I have an invite code": someone invited me to be friends (or to a group). */
@Composable
private fun RedeemCodeDialog(onRedeem: (String) -> Unit, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.newchat_have_code_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase() },
                label = { Text(stringResource(R.string.join_code)) },
                placeholder = { Text(stringResource(R.string.join_code_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { GoldButton(text = stringResource(R.string.invite_use_code), onClick = { onRedeem(code) }, enabled = code.isNotBlank()) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
