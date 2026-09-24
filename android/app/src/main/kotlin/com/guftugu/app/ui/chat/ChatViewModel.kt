package com.guftugu.app.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guftugu.app.R
import com.guftugu.app.calls.CallManager
import com.guftugu.app.calls.CallState
import com.guftugu.app.core.util.Time
import com.guftugu.app.data.prefs.ServerConfigStore
import com.guftugu.app.data.repo.ConversationRepository
import com.guftugu.app.data.repo.MessageRepository
import com.guftugu.app.data.repo.UserRepository
import com.guftugu.app.data.sync.TypingTracker
import com.guftugu.app.domain.Conversation
import com.guftugu.app.domain.ConversationKind
import com.guftugu.app.domain.Message
import com.guftugu.app.domain.MessageBody
import com.guftugu.app.domain.MessageStatus
import com.guftugu.app.domain.User
import com.guftugu.app.protocol.CallOutcome
import com.guftugu.app.protocol.CallType
import com.guftugu.app.protocol.ContentType
import com.guftugu.app.protocol.SystemEvent
import com.guftugu.app.ui.chats.PreviewLabels
import com.guftugu.app.ui.chats.PreviewText
import com.guftugu.app.ui.common.RelativeTime
import com.guftugu.app.ui.common.RelativeTimeLabels
import com.guftugu.app.ui.media.PickedAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One conversation: header (title/subtitle/typing), the reversed item list with date chips,
 * composer state, read receipts, calls. All list work runs on [Dispatchers.Default] and produces
 * `@Immutable` models; the composables only draw.
 */
class ChatViewModel(
    val convId: String,
    private val app: Application,
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val users: UserRepository,
    private val callManager: CallManager,
    serverConfig: ServerConfigStore,
) : ViewModel() {

    // ---------- labels (resolved once) ----------
    private val previewLabels = PreviewLabels(
        you = app.getString(R.string.preview_you),
        photo = app.getString(R.string.preview_photo),
        video = app.getString(R.string.preview_video),
        voiceNote = app.getString(R.string.preview_voice_note),
        file = app.getString(R.string.preview_file),
        missedCall = app.getString(R.string.preview_missed_call),
        audioCall = app.getString(R.string.preview_audio_call),
        videoCall = app.getString(R.string.preview_video_call),
        callRejected = app.getString(R.string.preview_call_rejected),
        callCancelled = app.getString(R.string.preview_call_cancelled),
        callUnreachable = app.getString(R.string.preview_call_unreachable),
        deleted = app.getString(R.string.chat_message_deleted),
        locked = app.getString(R.string.chat_waiting_for_keys),
        system = app.getString(R.string.preview_system),
    )
    private val timeLabels = RelativeTimeLabels(
        justNow = app.getString(R.string.time_just_now),
        minutesAgo = app.getString(R.string.time_minutes_ago),
        hoursAgo = app.getString(R.string.time_hours_ago),
        yesterday = app.getString(R.string.time_yesterday),
    )
    private val someone = app.getString(R.string.chat_someone)
    private val replyUnavailable = app.getString(R.string.chat_reply_unavailable)

    // ---------- inputs ----------
    private val myUserId: Flow<String?> = serverConfig.config.map { it.userId }.distinctUntilChanged()
    private val callsEnabled: Flow<Boolean> = serverConfig.config.map { it.callsEnabled }.distinctUntilChanged()
    private val conversation: Flow<Conversation?> = conversations.conversation(convId)
    private val userMap: Flow<Map<String, User>> = users.users().map { list -> list.associateBy { it.userId } }
    private val typing: Flow<Set<String>> = myUserId.flatMapLatest { me -> TypingTracker.typingUsers(convId, me) }.distinctUntilChanged()

    /** Ticks once a minute while the header is observed so "last seen 5 min ago" stays honest. */
    private val minuteTick: Flow<Long> = flow {
        while (true) { emit(Time.nowMs()); delay(60_000) }
    }

    // ---------- outputs ----------
    val header: StateFlow<ChatHeader> = combine(conversation, userMap, typing, callsEnabled, myUserId, minuteTick) { values ->
        @Suppress("UNCHECKED_CAST")
        val conv = values[0] as Conversation?
        @Suppress("UNCHECKED_CAST")
        val people = values[1] as Map<String, User>
        @Suppress("UNCHECKED_CAST")
        val typers = values[2] as Set<String>
        val calls = values[3] as Boolean
        val me = values[4] as String?
        val now = values[5] as Long
        buildHeader(conv, people, typers, calls, me, now)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatHeader())

    private val openedAtLatest = MutableStateFlow<String?>(null)

    val list: StateFlow<ChatListUi> = combine(messages.messages(convId), userMap, conversation, myUserId) { msgs, people, conv, me ->
        // Watermark for the slide-in: the newest server-assigned id (pending sends carry local ids).
        val opened = openedAtLatest.value ?: (msgs.lastOrNull { it.status == MessageStatus.SENT }?.msgId ?: "").also { openedAtLatest.value = it }
        ChatListUi(items = buildItems(msgs, people, conv, me, opened), loaded = true)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUi())

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _composer = MutableStateFlow(ComposerUi())
    val composer: StateFlow<ComposerUi> = _composer.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val effectChannel = Channel<ChatEffect>(Channel.BUFFERED)
    val effects: Flow<ChatEffect> = effectChannel.receiveAsFlow()

    // ---------- visibility, read receipts, typing pruning ----------
    private val visible = MutableStateFlow(false)
    private var pruneJob: Job? = null
    private var lastMarkedLatest: String? = null

    init {
        // While on screen: mark read whenever a new incoming message lands. Off screen: nothing is collected.
        viewModelScope.launch {
            visible.flatMapLatest { v ->
                if (!v) emptyFlow()
                else list.map { ui -> (ui.items.firstOrNull { it is ChatItem.Bubble } as? ChatItem.Bubble)?.message }
                    .filter { it != null && !it.isMine }
                    .distinctUntilChanged { a, b -> a?.msgId == b?.msgId }
            }.collect { markRead(it?.msgId) }
        }
    }

    fun setVisible(isVisible: Boolean) {
        if (isVisible) {
            // Only messages that arrive from now on slide in; whatever landed while paused is just there.
            list.value.items.firstOrNull { it is ChatItem.Bubble && it.message.tick != Tick.PENDING && it.message.tick != Tick.FAILED }
                ?.let { openedAtLatest.value = it.key }
            visible.value = true
            markRead(null)
            if (pruneJob?.isActive != true) {
                pruneJob = viewModelScope.launch {
                    while (isActive) { delay(1_000); TypingTracker.prune() }
                }
            }
        } else {
            visible.value = false
            pruneJob?.cancel()
            pruneJob = null
        }
    }

    private fun markRead(latestIncoming: String?) {
        if (latestIncoming != null && latestIncoming == lastMarkedLatest) return
        lastMarkedLatest = latestIncoming ?: lastMarkedLatest
        viewModelScope.launch { runCatching { messages.markRead(convId) } }
    }

    // ---------- composer ----------
    fun onDraftChange(text: String) {
        _draft.value = text
        if (text.isNotBlank()) messages.sendTyping(convId)
    }

    fun send() {
        val text = _draft.value.trim()
        if (text.isEmpty()) return
        val reply = _composer.value.replyTo?.msgId
        _draft.value = ""
        _composer.value = _composer.value.copy(replyTo = null)
        viewModelScope.launch {
            try {
                messages.sendText(convId, text, reply)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _error.value = app.getString(R.string.chat_send_error)
            }
        }
    }

    fun replyTo(message: MessageUi?) {
        _composer.value = _composer.value.copy(
            replyTo = message?.let { ReplyQuote(it.msgId, if (it.isMine) previewLabels.you else it.senderName, quoteText(it)) },
        )
    }

    fun onPicked(picked: PickedAttachment) {
        when (picked.type) {
            ContentType.IMAGE, ContentType.VIDEO ->
                _composer.value = _composer.value.copy(pendingCaption = PendingMedia(picked.uri, picked.type, picked.fileName))
            else -> sendAttachment(picked.uri, picked.type, null)
        }
    }

    fun confirmCaption(caption: String) {
        val pending = _composer.value.pendingCaption ?: return
        _composer.value = _composer.value.copy(pendingCaption = null)
        sendAttachment(pending.uri, pending.type, caption.trim().takeIf { it.isNotEmpty() })
    }

    fun cancelCaption() {
        _composer.value = _composer.value.copy(pendingCaption = null)
    }

    fun onVoiceRecorded(uri: Uri, @Suppress("UNUSED_PARAMETER") durationMs: Long) = sendAttachment(uri, ContentType.AUDIO, null)

    private fun sendAttachment(uri: Uri, type: ContentType, caption: String?) {
        viewModelScope.launch {
            _composer.value = _composer.value.copy(sending = true)
            try {
                messages.sendAttachment(convId, uri, type, caption)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _error.value = app.getString(R.string.chat_send_error)
            } finally {
                _composer.value = _composer.value.copy(sending = false)
            }
        }
    }

    fun delete(msgId: String) {
        viewModelScope.launch {
            try {
                messages.delete(convId, msgId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _error.value = app.getString(R.string.chat_send_error)
            }
        }
    }

    fun retryFailed() {
        viewModelScope.launch { runCatching { messages.retryOutbox() } }
    }

    fun dismissError() {
        _error.value = null
    }

    // ---------- paging ----------
    private var loadingOlder = false
    private var lastOlderAt = 0L

    fun loadOlder() {
        val now = Time.nowMs()
        if (loadingOlder || now - lastOlderAt < 1_500) return
        loadingOlder = true
        lastOlderAt = now
        viewModelScope.launch {
            try {
                messages.loadOlder(convId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _error.value = app.getString(R.string.chat_load_error)
            } finally {
                loadingOlder = false
            }
        }
    }

    // ---------- calls ----------
    private var callJob: Job? = null

    /** Starts a call through [CallManager] and emits [ChatEffect.OpenCall] once the call exists. */
    fun startCall(video: Boolean) {
        if (callJob?.isActive == true) return
        callJob = viewModelScope.launch {
            val watcher = launch {
                val id = withTimeoutOrNull(20_000) {
                    callManager.state.map { s -> if (s is CallState.Outgoing || s is CallState.Active) s.callOrNull?.callId else null }
                        .filter { it != null }.first()
                }
                if (id != null) effectChannel.send(ChatEffect.OpenCall(id))
            }
            try {
                callManager.startCall(convId, video)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                watcher.cancel()
                _error.value = app.getString(R.string.chat_call_failed)
            }
        }
    }

    // ---------- builders (Default dispatcher) ----------
    private fun buildHeader(conv: Conversation?, people: Map<String, User>, typers: Set<String>, calls: Boolean, me: String?, now: Long): ChatHeader {
        if (conv == null) return ChatHeader()
        val isGroup = conv.kind == ConversationKind.GROUP
        val subtitle: String? = when {
            typers.isNotEmpty() -> {
                val names = typers.map { people[it]?.displayName ?: someone }
                when (names.size) {
                    1 -> app.getString(R.string.chat_typing_one, names[0])
                    2 -> app.getString(R.string.chat_typing_two, names[0], names[1])
                    else -> app.getString(R.string.chat_typing_many)
                }
            }
            isGroup -> app.getString(R.string.chat_members, conv.members.size)
            else -> {
                val other = conv.members.firstOrNull { it.userId != me }?.let { people[it.userId] }
                val seen = other?.lastSeenAt
                when {
                    seen == null -> null
                    RelativeTime.isOnline(seen, now) -> app.getString(R.string.time_online)
                    else -> app.getString(R.string.time_last_seen, RelativeTime.format(seen, now, timeLabels))
                }
            }
        }
        return ChatHeader(title = conv.title, subtitle = subtitle, isGroup = isGroup, canCall = !isGroup && calls)
    }

    private fun buildItems(msgs: List<Message>, people: Map<String, User>, conv: Conversation?, me: String?, opened: String): List<ChatItem> {
        if (msgs.isEmpty()) return emptyList()
        val isGroup = conv?.kind == ConversationKind.GROUP
        val watermark = ReadTicks.watermark(conv?.members.orEmpty(), me)
        val byId = HashMap<String, Message>(msgs.size * 2)
        for (m in msgs) byId[m.msgId] = m
        val now = Time.nowMs()
        val out = ArrayList<ChatItem>(msgs.size + 8)
        var lastDay = Long.MIN_VALUE
        var lastSender: String? = null
        for (m in msgs) {
            val day = Time.toLocalDate(m.sentAt).toEpochDay()
            if (day != lastDay) {
                out.add(ChatItem.DateChip("d_$day", Time.formatDayLabel(m.sentAt, now)))
                lastDay = day
                lastSender = null
            }
            val senderName = people[m.senderId]?.displayName ?: someone
            val isContent = m.body !is MessageBody.System && m.body !is MessageBody.CallLog
            val showSender = isGroup && !m.isMine && isContent && lastSender != m.senderId
            lastSender = if (isContent) m.senderId else null
            out.add(ChatItem.Bubble(toUi(m, senderName, showSender, people, byId, watermark, opened)))
        }
        out.reverse()
        return out
    }

    private fun toUi(
        m: Message,
        senderName: String,
        showSender: Boolean,
        people: Map<String, User>,
        byId: Map<String, Message>,
        watermark: String?,
        opened: String,
    ): MessageUi {
        val body: BubbleBody = when (val b = m.body) {
            is MessageBody.Text -> BubbleBody.Text(b.text, Linkify.find(b.text))
            is MessageBody.Media -> BubbleBody.Media(b.type, b.attachment, b.caption?.takeIf { it.isNotBlank() }, b.caption?.let(Linkify::find).orEmpty())
            is MessageBody.CallLog -> callLog(b, m.isMine)
            is MessageBody.System -> BubbleBody.System(systemText(b, senderName, people))
            MessageBody.Locked -> BubbleBody.Locked
            MessageBody.Deleted -> BubbleBody.Deleted
        }
        val reply = m.replyTo?.let { id ->
            val original = byId[id]
            if (original == null) ReplyQuote(id, "", replyUnavailable)
            else ReplyQuote(id, if (original.isMine) previewLabels.you else people[original.senderId]?.displayName ?: someone, PreviewText.build(original.body, false, null, previewLabels))
        }
        return MessageUi(
            msgId = m.msgId,
            isMine = m.isMine,
            senderName = senderName,
            showSender = showSender,
            body = body,
            time = Time.formatTime(m.sentAt),
            tick = ReadTicks.tick(m.isMine, m.status, m.msgId, watermark),
            reply = reply,
            copyText = when (body) {
                is BubbleBody.Text -> body.text
                is BubbleBody.Media -> body.caption
                else -> null
            },
            isNew = !m.isMine && m.msgId > opened,
        )
    }

    private fun quoteText(ui: MessageUi): String = when (val b = ui.body) {
        is BubbleBody.Text -> PreviewText.collapse(b.text)
        is BubbleBody.Media -> when (b.type) {
            ContentType.IMAGE -> "${PreviewText.PHOTO} ${b.caption ?: previewLabels.photo}"
            ContentType.VIDEO -> "${PreviewText.VIDEO} ${b.caption ?: previewLabels.video}"
            ContentType.AUDIO -> "${PreviewText.VOICE} ${previewLabels.voiceNote}"
            else -> "${PreviewText.FILE} ${b.attachment.fileName ?: previewLabels.file}"
        }
        is BubbleBody.CallLog -> b.text
        is BubbleBody.System -> b.text
        BubbleBody.Locked -> previewLabels.locked
        BubbleBody.Deleted -> previewLabels.deleted
    }

    private fun callLog(b: MessageBody.CallLog, mine: Boolean): BubbleBody.CallLog {
        val video = b.info.type == CallType.VIDEO
        val kind = app.getString(if (video) R.string.chat_video_call else R.string.chat_audio_call)
        val text = when (b.info.outcome) {
            CallOutcome.ANSWERED -> app.getString(R.string.chat_call_answered, kind, Time.formatDuration(b.info.durationMs ?: 0L))
            CallOutcome.MISSED -> if (mine) app.getString(R.string.chat_call_unreachable, kind) else app.getString(R.string.chat_call_missed, kind)
            CallOutcome.REJECTED -> app.getString(R.string.chat_call_rejected, kind)
            CallOutcome.CANCELLED -> app.getString(R.string.chat_call_cancelled, kind)
            CallOutcome.UNREACHABLE -> app.getString(R.string.chat_call_unreachable, kind)
            else -> kind
        }
        val missed = b.info.outcome == CallOutcome.MISSED && !mine
        return BubbleBody.CallLog(text, video, missed)
    }

    private fun systemText(b: MessageBody.System, actor: String, people: Map<String, User>): String {
        val ids = b.info.userIds.orEmpty()
        val names = ids.map { people[it]?.displayName ?: someone }
        // Joining through an invite is recorded as "actor added [actor]": read it as "joined".
        if (b.info.event == SystemEvent.MEMBER_ADDED && ids.size == 1 && names[0] == actor) {
            return app.getString(R.string.chat_system_joined, actor)
        }
        val joined = when (names.size) {
            0 -> someone
            1 -> names[0]
            2 -> app.getString(R.string.chat_names_and, names[0], names[1])
            else -> app.getString(R.string.chat_names_more, names[0], names.size - 1)
        }
        return when (b.info.event) {
            SystemEvent.CREATED -> app.getString(R.string.chat_system_created, actor)
            SystemEvent.MEMBER_ADDED -> app.getString(R.string.chat_system_added, actor, joined)
            SystemEvent.MEMBER_REMOVED -> app.getString(R.string.chat_system_removed, actor, joined)
            SystemEvent.MEMBER_LEFT -> app.getString(R.string.chat_system_left, if (names.isNotEmpty()) joined else actor)
            SystemEvent.RENAMED -> app.getString(R.string.chat_system_renamed, actor, b.info.text.orEmpty())
            else -> b.info.text ?: previewLabels.system
        }
    }
}
