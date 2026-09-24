package com.guftugu.app.data.sync

import android.content.Context
import com.guftugu.app.R
import com.guftugu.app.core.util.Time
import com.guftugu.app.data.db.ConversationEntity
import com.guftugu.app.data.db.MessageEntity
import com.guftugu.app.data.db.MessageStatus
import com.guftugu.app.domain.toEntity
import com.guftugu.app.protocol.CallInfo
import com.guftugu.app.protocol.CallOutcome
import com.guftugu.app.protocol.CallType
import com.guftugu.app.protocol.ContentType
import com.guftugu.app.protocol.Conversation
import com.guftugu.app.protocol.ErrorCode
import com.guftugu.app.protocol.MessageKind
import com.guftugu.app.protocol.ProtocolJson
import com.guftugu.app.protocol.SystemEvent
import com.guftugu.app.protocol.SystemInfo
import kotlinx.serialization.json.Json

/**
 * Pure, JVM-testable decisions of the sync layer: previews, unread bookkeeping, outbox backoff,
 * what to fetch after a reconnect, and how a server conversation merges with the local row.
 * No Room, no network, no Android here (except [PreviewLabels.from], which only reads strings).
 */

/** Human labels used in chat-list previews and notifications. Defaults are English; [from] reads `strings.xml`. */
data class PreviewLabels(
    val you: String = "You",
    val photo: String = "Photo",
    val video: String = "Video",
    val voiceNote: String = "Voice note",
    val file: String = "File",
    val deleted: String = "Message deleted",
    val locked: String = "Waiting for keys…",
    val audioCall: String = "Audio call",
    val videoCall: String = "Video call",
    val missedCall: String = "Missed call",
    val declinedCall: String = "Declined call",
    val cancelledCall: String = "Cancelled call",
    val unreachableCall: String = "Missed call",
    val memberAdded: String = "Member added",
    val memberRemoved: String = "Member removed",
    val memberLeft: String = "Member left",
    val renamed: String = "Group renamed",
    val created: String = "Conversation created",
    val unknownUser: String = "Unknown",
    val newMessage: String = "New message",
) {
    companion object {
        fun from(context: Context): PreviewLabels = PreviewLabels(
            you = context.getString(R.string.preview_you),
            photo = context.getString(R.string.preview_photo),
            video = context.getString(R.string.preview_video),
            voiceNote = context.getString(R.string.preview_voice_note),
            file = context.getString(R.string.preview_file),
            deleted = context.getString(R.string.preview_deleted),
            locked = context.getString(R.string.preview_locked),
            audioCall = context.getString(R.string.preview_audio_call),
            videoCall = context.getString(R.string.preview_video_call),
            missedCall = context.getString(R.string.preview_missed_call),
            declinedCall = context.getString(R.string.preview_declined_call),
            cancelledCall = context.getString(R.string.preview_cancelled_call),
            unreachableCall = context.getString(R.string.preview_missed_call),
            memberAdded = context.getString(R.string.preview_member_added),
            memberRemoved = context.getString(R.string.preview_member_removed),
            memberLeft = context.getString(R.string.preview_member_left),
            renamed = context.getString(R.string.preview_renamed),
            created = context.getString(R.string.preview_created),
            unknownUser = context.getString(R.string.preview_unknown_user),
            newMessage = context.getString(R.string.notification_new_message),
        )
    }
}

object SyncRules {

    // ---------- previews ----------

    /**
     * Chat-list preview for [m]. [senderName] is prefixed in groups for other people's messages;
     * "You:" for mine. Call/system rows get no prefix. Returns null only for rows with nothing to show.
     */
    fun previewOf(
        m: MessageEntity,
        mine: Boolean,
        isGroup: Boolean,
        senderName: String?,
        labels: PreviewLabels,
        json: Json = ProtocolJson,
    ): String? {
        if (m.deletedAt != null) return labels.deleted
        when (m.kind) {
            MessageKind.CALL -> return callPreview(m.callJson, labels, json)
            MessageKind.SYSTEM -> return systemPreview(m.systemJson, labels, json)
        }
        if (m.status == MessageStatus.UNDECRYPTABLE || m.contentType == null) return labels.locked // no sender prefix on locked rows
        val body = bodyPreview(m, labels) ?: return null
        val prefix = when {
            mine -> labels.you
            isGroup && !senderName.isNullOrBlank() -> firstName(senderName)
            else -> null
        }
        return if (prefix != null) "$prefix: $body" else body
    }

    /** Notification line for a decrypted incoming message (no "You:" prefix; groups name the sender). */
    fun notificationText(m: MessageEntity, isGroup: Boolean, senderName: String?, labels: PreviewLabels): String {
        val body = bodyPreview(m, labels) ?: labels.newMessage
        return if (isGroup && !senderName.isNullOrBlank()) "${firstName(senderName)}: $body" else body
    }

    /** The e2e body without any sender prefix; null for rows with nothing to show. */
    fun bodyPreview(m: MessageEntity, labels: PreviewLabels): String? {
        if (m.status == MessageStatus.UNDECRYPTABLE || m.contentType == null) return labels.locked
        val type = ContentType.fromWire(m.contentType) ?: ContentType.TEXT
        val caption = m.text?.takeIf { it.isNotBlank() }?.let(::singleLine)
        return when (type) {
            ContentType.TEXT -> caption ?: ""
            ContentType.IMAGE -> caption ?: labels.photo
            ContentType.VIDEO -> caption ?: labels.video
            ContentType.AUDIO -> caption ?: labels.voiceNote
            ContentType.FILE -> caption ?: labels.file
        }
    }

    fun callPreview(callJson: String?, labels: PreviewLabels, json: Json = ProtocolJson): String {
        val info = callJson?.let { runCatching { json.decodeFromString(CallInfo.serializer(), it) }.getOrNull() }
            ?: return labels.audioCall
        val kind = if (info.type == CallType.VIDEO) labels.videoCall else labels.audioCall
        return when (info.outcome) {
            CallOutcome.ANSWERED -> if (info.durationMs != null && info.durationMs > 0) "$kind · ${Time.formatDuration(info.durationMs)}" else kind
            CallOutcome.MISSED -> labels.missedCall
            CallOutcome.REJECTED -> labels.declinedCall
            CallOutcome.CANCELLED -> labels.cancelledCall
            CallOutcome.UNREACHABLE -> labels.unreachableCall
            else -> kind
        }
    }

    fun systemPreview(systemJson: String?, labels: PreviewLabels, json: Json = ProtocolJson): String {
        val info = systemJson?.let { runCatching { json.decodeFromString(SystemInfo.serializer(), it) }.getOrNull() }
            ?: return labels.created
        info.text?.takeIf { it.isNotBlank() }?.let { return singleLine(it) }
        return when (info.event) {
            SystemEvent.MEMBER_ADDED -> labels.memberAdded
            SystemEvent.MEMBER_REMOVED -> labels.memberRemoved
            SystemEvent.MEMBER_LEFT -> labels.memberLeft
            SystemEvent.RENAMED -> labels.renamed
            else -> labels.created
        }
    }

    /** Collapses whitespace/newlines and trims to a list-friendly length. */
    fun singleLine(text: String, max: Int = 120): String {
        val sb = StringBuilder(minOf(text.length, max))
        var lastSpace = false
        for (ch in text) {
            val space = ch == '\n' || ch == '\r' || ch == '\t' || ch == ' '
            if (space) {
                if (!lastSpace && sb.isNotEmpty()) sb.append(' ')
                lastSpace = true
            } else {
                sb.append(ch)
                lastSpace = false
            }
            if (sb.length >= max) break
        }
        return sb.toString().trimEnd()
    }

    fun firstName(displayName: String): String = displayName.trim().substringBefore(' ')

    // ---------- unread ----------

    /** Same semantics as `MessageDao.countUnread`: others' non-deleted, decrypted messages after my last-read id. */
    fun countUnread(messages: List<MessageEntity>, myUserId: String?, lastReadMsgId: String?): Int =
        messages.count {
            it.senderId != myUserId && it.deletedAt == null && it.status == MessageStatus.SENT &&
                (lastReadMsgId == null || it.msgId > lastReadMsgId)
        }

    data class IncomingDecision(
        /** Bump the unread count / keep it (recompute from the DB). */
        val countUnread: Boolean,
        /** Post a message notification. */
        val notify: Boolean,
        /** The user is looking at this chat: mark it read locally (and let the repo coalesce the PUT). */
        val markRead: Boolean,
    )

    /**
     * What to do with a freshly arrived `message.new`.
     * - Mine (any of my devices) → nothing.
     * - Already stored (sync race / duplicate frame) → nothing.
     * - Chat open on screen and app visible → mark read, no notification.
     * - Otherwise → unread; notify only when the app is not visible and the text is readable.
     */
    fun decideIncoming(isMine: Boolean, alreadyKnown: Boolean, chatOpen: Boolean, appVisible: Boolean, decrypted: Boolean): IncomingDecision =
        when {
            isMine || alreadyKnown -> IncomingDecision(countUnread = false, notify = false, markRead = false)
            chatOpen -> IncomingDecision(countUnread = false, notify = false, markRead = true)
            else -> IncomingDecision(countUnread = true, notify = !appVisible && decrypted, markRead = false)
        }

    // ---------- reconciliation ----------

    sealed class MessagePlan {
        /** Nothing newer on the server. */
        data object None : MessagePlan()
        /** Never synced: fetch the latest [limit] (descending, no cursor). */
        data class Initial(val limit: Int = INITIAL_LIMIT) : MessagePlan()
        /** Page forward from the local latest id until `hasMore == false`. */
        data class After(val cursor: String, val limit: Int = PAGE_LIMIT) : MessagePlan()
    }

    /** ULID-based ids compare lexicographically in creation order (PROTOCOL "Conventions"). */
    fun planMessageSync(serverLastMsgId: String?, localLatestMsgId: String?): MessagePlan = when {
        serverLastMsgId == null -> MessagePlan.None
        localLatestMsgId == null -> MessagePlan.Initial()
        serverLastMsgId > localLatestMsgId -> MessagePlan.After(localLatestMsgId)
        else -> MessagePlan.None
    }

    fun removedConversationIds(local: Collection<String>, server: Collection<String>): Set<String> {
        val s = server.toHashSet()
        return local.filterNotTo(LinkedHashSet()) { it in s }
    }

    /**
     * Merge a server conversation into the local row: server metadata wins, local derived fields
     * (preview, unread) are preserved, and my last-read id is the newer of the two (the local one
     * can be ahead while the coalesced PUT is still pending).
     */
    fun mergeConversation(server: Conversation, existing: ConversationEntity?, myUserId: String?): ConversationEntity {
        val base = server.toEntity(existing)
        val serverRead = server.members.firstOrNull { it.userId == myUserId }?.lastReadMsgId
        val localRead = existing?.myLastReadMsgId
        val read = maxOfNullable(serverRead, localRead)
        return if (read != base.myLastReadMsgId) base.copy(myLastReadMsgId = read) else base
    }

    /** Whether the key manager should look at this conversation after a sync / `conversation.updated`. */
    fun needsKeyAttention(server: Conversation, existing: ConversationEntity?): Boolean =
        existing == null || server.keyRotationRequired || server.currentKeyId == null || server.currentKeyId != existing.currentKeyId

    /**
     * PROTOCOL §7 rule 2: on `conversation.updated` a *membership* change also needs key work — a
     * newly joined member's devices have no key yet and one of the existing holders must wrap the
     * current key for them. Compares the member user-id sets (a rename alone changes nothing).
     */
    fun needsKeyAttention(server: Conversation, existing: ConversationEntity?, localMemberIds: Collection<String>): Boolean =
        needsKeyAttention(server, existing) || membersChanged(server, localMemberIds)

    fun membersChanged(server: Conversation, localMemberIds: Collection<String>): Boolean {
        if (server.members.isEmpty()) return false // the server never sends an empty member list for a live conversation
        val serverIds = server.members.mapTo(HashSet(server.members.size)) { it.userId }
        return serverIds.size != localMemberIds.size || !localMemberIds.all { it in serverIds }
    }

    fun maxOfNullable(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        else -> if (a >= b) a else b
    }

    const val INITIAL_LIMIT = 50
    const val PAGE_LIMIT = 200
    const val OLDER_LIMIT = 50
}

/** Retry policy for the Room outbox (`MessageRepository.sendText/sendAttachment`). */
object OutboxPolicy {
    /** Delay before retry number n (1-based) after n failures: 1 s, 4 s, 16 s; afterwards wait for the next connect. */
    val BACKOFF_MS: LongArray = longArrayOf(1_000L, 4_000L, 16_000L)

    /** [attempts] = failures so far (≥ 1). Null = stop the timer; `retryOutbox()` on the next connect picks it up. */
    fun backoffAfterFailure(attempts: Int): Long? = if (attempts <= 0) BACKOFF_MS[0] else BACKOFF_MS.getOrNull(attempts - 1)

    enum class Outcome {
        /** Transient: keep PENDING, retry with backoff. */
        RETRY,
        /** Session gone: keep PENDING, no timer; retried after re-login/reconnect. */
        WAIT,
        /** Permanent (rejected by the server): mark FAILED so the user can see it. */
        FAIL,
    }

    /** Classify an HTTP [status] / error [code] ([status] 0 = transport error). */
    fun classify(status: Int, code: String?): Outcome = when {
        status == 0 -> Outcome.RETRY
        status == 401 || code == ErrorCode.UNAUTHORIZED -> Outcome.WAIT
        status == 408 || status == 429 || status >= 500 -> Outcome.RETRY
        status in 400..499 -> Outcome.FAIL
        else -> Outcome.RETRY
    }

    /** Coalescing window for `PUT …/read` (ARCHITECTURE.md "Caching & sync"). */
    const val READ_RECEIPT_WINDOW_MS = 5_000L

    /** `typing` frames: ≤ 1 per 3 s per conversation (PROTOCOL §12). */
    const val TYPING_INTERVAL_MS = 3_000L

    /** Local id of a not-yet-acknowledged message row; sorts after every server `m_` id. */
    const val PENDING_ID_PREFIX = "p_"

    fun pendingMsgId(clientId: String): String = PENDING_ID_PREFIX + clientId
    fun isPendingMsgId(msgId: String): Boolean = msgId.startsWith(PENDING_ID_PREFIX)
}
