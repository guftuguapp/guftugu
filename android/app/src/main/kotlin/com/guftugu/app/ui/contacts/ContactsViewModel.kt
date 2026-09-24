package com.guftugu.app.ui.contacts

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guftugu.app.R
import com.guftugu.app.data.prefs.ServerConfigStore
import com.guftugu.app.data.repo.ConversationRepository
import com.guftugu.app.data.repo.MediaRepository
import com.guftugu.app.data.repo.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ContactUi(
    val userId: String,
    val name: String,
    val avatarKey: String?,
    val isAdmin: Boolean,
    val selected: Boolean = false,
)

@Immutable
data class ContactsState(
    val items: List<ContactUi> = emptyList(),
    val loaded: Boolean = false,
    val query: String = "",
    val groupName: String = "",
    val selectedCount: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canCreateGroup: Boolean get() = groupName.isNotBlank() && selectedCount > 0 && !busy
}

/**
 * Shared by NewChatScreen (tap → direct conversation) and NewGroupScreen (name + multi-select).
 * The list is everyone on the server except me, filtered locally by [setQuery].
 */
class ContactsViewModel(
    private val app: Application,
    private val users: UserRepository,
    private val conversations: ConversationRepository,
    private val media: MediaRepository,
    serverConfig: ServerConfigStore,
    private val api: com.guftugu.app.data.api.GuftuguApi? = null,
) : ViewModel() {

    /** People I blocked: hidden from "your people". */
    private val blocked = MutableStateFlow<Set<String>>(emptySet())

    /** A freshly created invite, shown in the "invite ready" dialog. */
    private val _invite = MutableStateFlow<com.guftugu.app.protocol.Invite?>(null)
    val invite: StateFlow<com.guftugu.app.protocol.Invite?> = _invite

    private val query = MutableStateFlow("")
    private val groupName = MutableStateFlow("")
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val me = serverConfig.config.map { it.userId }.distinctUntilChanged()

    val state: StateFlow<ContactsState> = combine(users.users(), me, query, groupName, selected, busy, error, blocked) { values ->
        @Suppress("UNCHECKED_CAST")
        val all = values[0] as List<com.guftugu.app.domain.User>
        val myId = values[1] as String?
        val q = (values[2] as String).trim()
        val name = values[3] as String
        @Suppress("UNCHECKED_CAST")
        val picked = values[4] as Set<String>
        val isBusy = values[5] as Boolean
        val err = values[6] as String?
        @Suppress("UNCHECKED_CAST")
        val hidden = values[7] as Set<String>
        val rows = all.asSequence()
            .filter { it.userId != myId && !it.isDisabled && it.userId !in hidden }
            .filter { q.isEmpty() || it.displayName.contains(q, ignoreCase = true) }
            .map { ContactUi(it.userId, it.displayName, it.avatarKey, it.isAdmin, it.userId in picked) }
            .toList()
        ContactsState(rows, loaded = true, query = q, groupName = name, selectedCount = picked.size, busy = isBusy, error = err)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactsState())

    init {
        viewModelScope.launch { runCatching { users.refresh() } }
        viewModelScope.launch {
            runCatching { api?.friends() }.getOrNull()?.let { list -> blocked.value = list.filter { it.blocked }.map { it.user.userId }.toSet() }
        }
    }

    /** "Invite someone": a single-use friend code, then the share dialog. */
    fun createInvite() {
        val a = api ?: return
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                _invite.value = a.createInvite("friend")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                error.value = app.getString(R.string.invite_error_create)
            } finally {
                busy.value = false
            }
        }
    }

    fun dismissInvite() { _invite.value = null }

    /** "I have an invite code": become friends and open the chat it was for. */
    fun redeem(code: String, onOpen: (String) -> Unit) {
        val a = api ?: return
        val normalized = com.guftugu.app.core.auth.InviteCodes.normalize(code)
        if (busy.value || normalized.isBlank()) return
        viewModelScope.launch {
            busy.value = true
            try {
                val res = a.redeemInvite(normalized)
                runCatching { users.refresh() }
                runCatching { conversations.refresh() }
                res.conversation?.convId?.let(onOpen)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val code2 = (t as? com.guftugu.app.data.api.ApiException)?.code
                error.value = app.getString(
                    when (code2) {
                        "invite_expired" -> R.string.auth_err_invite_expired
                        "invite_invalid" -> R.string.auth_err_invite_invalid
                        "invalid_request" -> R.string.invite_error_own_or_wrong
                        else -> R.string.invite_error_redeem
                    },
                )
            } finally {
                busy.value = false
            }
        }
    }

    fun setQuery(value: String) { query.value = value }
    fun setGroupName(value: String) { groupName.value = value }
    fun toggle(userId: String) {
        selected.value = selected.value.let { if (userId in it) it - userId else it + userId }
    }
    fun dismissError() { error.value = null }

    /** One stable instance so contact rows can skip recomposition. */
    val resolveAvatar: suspend (String) -> String? = { key -> runCatching { media.avatarUrl(key) }.getOrNull() }

    /** Existing or new direct conversation; [onOpen] receives its id. */
    fun openDirect(userId: String, onOpen: (String) -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                val conv = conversations.createDirect(userId)
                onOpen(conv.convId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                error.value = app.getString(R.string.contacts_error_open)
            } finally {
                busy.value = false
            }
        }
    }

    fun createGroup(onCreated: (String) -> Unit) {
        val name = groupName.value.trim()
        val members = selected.value.toList()
        if (name.isEmpty() || members.isEmpty() || busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                val conv = conversations.createGroup(name, members)
                onCreated(conv.convId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                error.value = app.getString(R.string.group_error_create)
            } finally {
                busy.value = false
            }
        }
    }
}
