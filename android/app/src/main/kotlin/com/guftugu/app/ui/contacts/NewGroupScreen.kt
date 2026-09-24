package com.guftugu.app.ui.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.guftugu.app.R
import com.guftugu.app.ui.common.EmptyState
import com.guftugu.app.ui.common.ErrorBanner
import com.guftugu.app.ui.common.SearchPill
import com.guftugu.app.ui.common.SectionHeader
import com.guftugu.app.ui.common.graphViewModel
import com.guftugu.app.ui.theme.GoldButton
import com.guftugu.app.ui.theme.GuftuguTheme
import com.guftugu.app.ui.theme.ParchmentCard
import com.guftugu.app.ui.theme.RiverbankBackground
import com.guftugu.app.ui.theme.SkyTopBar

/** Name + members → POST /conversations { type: "group" }. */
@Composable
fun NewGroupScreen(onBack: () -> Unit, onCreated: (convId: String) -> Unit) {
    val vm = graphViewModel { ContactsViewModel(it.app, it.userRepository, it.conversationRepository, it.mediaRepository, it.serverConfig, it.api) }
    val state by vm.state.collectAsStateWithLifecycle()
    NewGroupContent(
        state = state,
        onBack = onBack,
        onQueryChange = vm::setQuery,
        onNameChange = vm::setGroupName,
        onToggle = vm::toggle,
        onDismissError = vm::dismissError,
        resolveAvatar = vm.resolveAvatar,
        onCreate = { vm.createGroup(onCreated) },
    )
}

@Composable
fun NewGroupContent(
    state: ContactsState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onDismissError: () -> Unit,
    resolveAvatar: (suspend (String) -> String?)?,
    onCreate: () -> Unit,
) {
    RiverbankBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                SkyTopBar(
                    title = stringResource(R.string.title_new_group),
                    subtitle = if (state.selectedCount > 0) stringResource(R.string.group_selected, state.selectedCount) else null,
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                )
            },
            bottomBar = {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    GoldButton(stringResource(R.string.group_create), onClick = onCreate, enabled = state.canCreateGroup, modifier = Modifier.fillMaxWidth())
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                ParchmentCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = state.groupName,
                        onValueChange = onNameChange,
                        placeholder = { Text(stringResource(R.string.group_name_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.tertiary,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(6.dp),
                    )
                }
                SearchPill(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    hint = stringResource(R.string.contacts_search_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.tertiary)
                ErrorBanner(state.error, onDismiss = onDismissError)
                when {
                    !state.loaded -> Box(Modifier.fillMaxSize())
                    state.items.isEmpty() && state.query.isEmpty() -> EmptyState(
                        title = stringResource(R.string.contacts_empty_title),
                        caption = stringResource(R.string.contacts_empty_caption),
                    )
                    state.items.isEmpty() -> EmptyState(title = stringResource(R.string.chats_no_results), medallion = false)
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item(key = "header", contentType = "header") { SectionHeader(stringResource(R.string.group_members_section)) }
                        items(state.items, key = { it.userId }, contentType = { "contact" }) { c ->
                            ContactRow(contact = c, resolveAvatar = resolveAvatar, onClick = { onToggle(c.userId) }, selectable = true)
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NewGroupPreview() {
    GuftuguTheme {
        NewGroupContent(
            state = ContactsState(
                loaded = true,
                groupName = "Cousins",
                selectedCount = 1,
                items = listOf(ContactUi("u_1", "Ammi", null, true, selected = true), ContactUi("u_2", "Abbu", null, false), ContactUi("u_3", "Sara", null, false)),
            ),
            onBack = {}, onQueryChange = {}, onNameChange = {}, onToggle = {}, onDismissError = {}, resolveAvatar = null, onCreate = {},
        )
    }
}
