package com.guftugu.app.ui.chats

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guftugu.app.R
import com.guftugu.app.core.util.Time
import com.guftugu.app.data.repo.ConversationRepository
import com.guftugu.app.data.repo.MediaRepository
import com.guftugu.app.domain.Conversation
import com.guftugu.app.domain.ConversationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of the chats list. Everything is pre-formatted so the row composable only draws. */
@Immutable
data class ConversationUi(
    val convId: String,
    val title: String,
    val isGroup: Boolean,
    val preview: String,
    val stamp: String,
    val unread: Int,
    val keyPending: Boolean,
    val avatarKey: String?,
)

@Immutable
data class ChatListState(
    val items: List<ConversationUi> = emptyList(),
    /** False until the first Room emission, so the empty state does not flash on open. */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val query: String = "",
    /** True when there are conversations but none match [query]. */
    val filteredOut: Boolean = false,
)

class ChatListViewModel(
    private val app: Application,
    private val conversations: ConversationRepository,
    private val media: MediaRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    /** Bumped on resume so "Today"/weekday stamps re-evaluate after midnight. */
    private val clockTick = MutableStateFlow(0)

    private val locked = app.getString(R.string.chats_key_pending)
    private val noMessages = app.getString(R.string.chats_no_messages)

    val state: StateFlow<ChatListState> = combine(
        conversations.conversations(),
        query,
        refreshing,
        error,
        clockTick,
    ) { list, q, refreshing, error, _ ->
        val now = Time.nowMs()
        val rows = list.map { it.toUi(now) }
        val needle = q.trim()
        val shown = if (needle.isEmpty()) rows else rows.filter { it.title.contains(needle, ignoreCase = true) || it.preview.contains(needle, ignoreCase = true) }
        ChatListState(
            items = shown,
            loaded = true,
            refreshing = refreshing,
            error = error,
            query = q,
            filteredOut = rows.isNotEmpty() && shown.isEmpty(),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun onResume() {
        clockTick.update { it + 1 }
    }

    fun dismissError() {
        error.value = null
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                conversations.refresh()
                error.value = null
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                error.value = app.getString(R.string.chats_refresh_failed)
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Presigned avatar URL for a row; the media repository caches it briefly. One stable instance so rows skip. */
    val resolveAvatar: suspend (String) -> String? = { key -> runCatching { media.avatarUrl(key) }.getOrNull() }

    private fun Conversation.toUi(now: Long): ConversationUi {
        val at = previewAt ?: lastMessageAt
        return ConversationUi(
            convId = convId,
            title = title,
            isGroup = kind == ConversationKind.GROUP,
            preview = preview?.takeIf { it.isNotBlank() } ?: if (!hasKey) locked else noMessages,
            stamp = at?.let { Time.formatShort(it, now) }.orEmpty(),
            unread = unreadCount,
            keyPending = !hasKey,
            avatarKey = avatarKey,
        )
    }
}
