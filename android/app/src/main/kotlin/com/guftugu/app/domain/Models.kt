/**
 * Plain models used by the UI. They are mapped from Room entities / protocol DTOs so screens
 * never depend on wire or storage shapes.
 */
package com.guftugu.app.domain

import com.guftugu.app.data.db.ConversationEntity
import com.guftugu.app.data.db.MemberEntity
import com.guftugu.app.data.db.MessageEntity
import com.guftugu.app.data.db.MessageStatus as DbMessageStatus
import com.guftugu.app.data.db.UserEntity
import com.guftugu.app.protocol.Attachment
import com.guftugu.app.protocol.CallInfo
import com.guftugu.app.protocol.ContentType
import com.guftugu.app.protocol.ConversationType
import com.guftugu.app.protocol.MessageKind
import com.guftugu.app.protocol.ProtocolJson
import com.guftugu.app.protocol.SystemInfo
import com.guftugu.app.protocol.UserRole
import com.guftugu.app.protocol.UserStatus

data class User(
    val userId: String,
    val displayName: String,
    val avatarKey: String? = null,
    val isAdmin: Boolean = false,
    val isDisabled: Boolean = false,
    val lastSeenAt: Long? = null,
) {
    val initials: String
        get() = displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }.ifEmpty { "?" }
}

enum class ConversationKind { DIRECT, GROUP }

data class Member(
    val userId: String,
    val isOwner: Boolean,
    val joinedAt: Long,
    val lastReadMsgId: String? = null,
)

data class Conversation(
    val convId: String,
    val kind: ConversationKind,
    /** Group name, or the other member's display name for direct chats (resolved by the repository). */
    val title: String,
    val avatarKey: String? = null,
    val members: List<Member> = emptyList(),
    val preview: String? = null,
    val previewAt: Long? = null,
    val unreadCount: Int = 0,
    val lastMessageAt: Long? = null,
    val hasKey: Boolean = true,
    val keyRotationRequired: Boolean = false,
)

enum class MessageStatus { SENT, PENDING, FAILED, UNDECRYPTABLE }

sealed class MessageBody {
    data class Text(val text: String) : MessageBody()
    data class Media(val type: ContentType, val attachment: Attachment, val caption: String?) : MessageBody()
    data class CallLog(val info: CallInfo) : MessageBody()
    data class System(val info: SystemInfo) : MessageBody()
    /** Key not available yet (PROTOCOL §7 rule 3). */
    data object Locked : MessageBody()
    data object Deleted : MessageBody()
}

data class Message(
    val msgId: String,
    val convId: String,
    val senderId: String,
    val clientId: String? = null,
    val body: MessageBody,
    val replyTo: String? = null,
    val sentAt: Long,
    val status: MessageStatus = MessageStatus.SENT,
    val isMine: Boolean = false,
)

// ---------- mappers ----------

fun UserEntity.toDomain(): User = User(
    userId = userId,
    displayName = displayName,
    avatarKey = avatarKey,
    isAdmin = role == UserRole.ADMIN,
    isDisabled = status == UserStatus.DISABLED,
    lastSeenAt = lastSeenAt,
)

fun com.guftugu.app.protocol.User.toEntity(): UserEntity = UserEntity(
    userId = userId, displayName = displayName, avatarKey = avatarKey, role = role, status = status, lastSeenAt = lastSeenAt,
)

fun MemberEntity.toDomain(): Member = Member(userId, role == com.guftugu.app.protocol.MemberRole.OWNER, joinedAt, lastReadMsgId)

fun ConversationEntity.toDomain(title: String, members: List<Member>, hasKey: Boolean): Conversation = Conversation(
    convId = convId,
    kind = if (type == ConversationType.GROUP) ConversationKind.GROUP else ConversationKind.DIRECT,
    title = title,
    avatarKey = avatarKey,
    members = members,
    preview = localPreview,
    previewAt = localPreviewAt,
    unreadCount = unreadCount,
    lastMessageAt = lastMessageAt,
    hasKey = hasKey,
    keyRotationRequired = keyRotationRequired,
)

fun com.guftugu.app.protocol.Conversation.toEntity(existing: ConversationEntity? = null): ConversationEntity = ConversationEntity(
    convId = convId,
    type = type,
    name = name,
    avatarKey = avatarKey,
    createdBy = createdBy,
    createdAt = createdAt,
    currentKeyId = currentKeyId,
    keyRotationRequired = keyRotationRequired,
    lastMsgId = lastMsgId,
    lastMessageAt = lastMessageAt,
    localPreview = existing?.localPreview,
    localPreviewAt = existing?.localPreviewAt,
    unreadCount = existing?.unreadCount ?: 0,
    myLastReadMsgId = existing?.myLastReadMsgId,
)

fun MessageEntity.toDomain(myUserId: String?): Message {
    val status = when (this.status) {
        DbMessageStatus.PENDING -> MessageStatus.PENDING
        DbMessageStatus.FAILED -> MessageStatus.FAILED
        DbMessageStatus.UNDECRYPTABLE -> MessageStatus.UNDECRYPTABLE
        else -> MessageStatus.SENT
    }
    val body: MessageBody = when {
        deletedAt != null -> MessageBody.Deleted
        kind == MessageKind.CALL -> callJson?.let { runCatching { ProtocolJson.decodeFromString(CallInfo.serializer(), it) }.getOrNull() }
            ?.let { MessageBody.CallLog(it) } ?: MessageBody.Deleted
        kind == MessageKind.SYSTEM -> systemJson?.let { runCatching { ProtocolJson.decodeFromString(SystemInfo.serializer(), it) }.getOrNull() }
            ?.let { MessageBody.System(it) } ?: MessageBody.Deleted
        status == MessageStatus.UNDECRYPTABLE || contentType == null -> MessageBody.Locked
        else -> {
            val type = ContentType.fromWire(contentType) ?: ContentType.TEXT
            val attachment = attachmentJson?.let { runCatching { ProtocolJson.decodeFromString(Attachment.serializer(), it) }.getOrNull() }
            if (type != ContentType.TEXT && attachment != null) MessageBody.Media(type, attachment, text) else MessageBody.Text(text ?: "")
        }
    }
    return Message(
        msgId = msgId,
        convId = convId,
        senderId = senderId,
        clientId = clientId,
        body = body,
        replyTo = replyTo,
        sentAt = sentAt,
        status = status,
        isMine = myUserId != null && senderId == myUserId,
    )
}
