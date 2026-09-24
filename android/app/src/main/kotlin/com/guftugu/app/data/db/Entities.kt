package com.guftugu.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room schema per docs/ANDROID_MODULES.md "Room schema (skeleton)". */

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val avatarKey: String? = null,
    /** [com.guftugu.app.protocol.UserRole] */
    val role: String,
    /** [com.guftugu.app.protocol.UserStatus] */
    val status: String,
    val lastSeenAt: Long? = null,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val convId: String,
    /** [com.guftugu.app.protocol.ConversationType] */
    val type: String,
    val name: String? = null,
    val avatarKey: String? = null,
    val createdBy: String,
    val createdAt: Long,
    val currentKeyId: String? = null,
    val keyRotationRequired: Boolean = false,
    /** Server's latest message id (from GET /conversations); compare with the local latest to decide whether to sync. */
    val lastMsgId: String? = null,
    val lastMessageAt: Long? = null,
    /** Derived locally from decrypted messages (the server cannot compute previews). */
    val localPreview: String? = null,
    val localPreviewAt: Long? = null,
    val unreadCount: Int = 0,
    val myLastReadMsgId: String? = null,
)

@Entity(tableName = "members", primaryKeys = ["convId", "userId"], indices = [Index("userId")])
data class MemberEntity(
    val convId: String,
    val userId: String,
    /** [com.guftugu.app.protocol.MemberRole] */
    val role: String,
    val joinedAt: Long,
    val lastReadMsgId: String? = null,
)

object MessageStatus {
    const val SENT = "sent"
    const val PENDING = "pending"
    const val FAILED = "failed"
    /** Envelope kept in [MessageEntity.envelopeJson] until the key arrives. */
    const val UNDECRYPTABLE = "undecryptable"
}

@Entity(
    tableName = "messages",
    indices = [Index(value = ["convId", "msgId"]), Index(value = ["clientId"], unique = false), Index(value = ["status"])],
)
data class MessageEntity(
    @PrimaryKey val msgId: String,
    val convId: String,
    val senderId: String,
    val senderDeviceId: String? = null,
    val clientId: String? = null,
    /** [com.guftugu.app.protocol.MessageKind] */
    val kind: String,
    val sentAt: Long,
    val createdAt: Long,
    val deletedAt: Long? = null,
    /** Decrypted [com.guftugu.app.protocol.ContentType] wire value, null until decrypted. */
    val contentType: String? = null,
    val text: String? = null,
    /** JSON of [com.guftugu.app.protocol.Attachment] (decrypted). */
    val attachmentJson: String? = null,
    /** JSON of [com.guftugu.app.protocol.CallInfo]. */
    val callJson: String? = null,
    /** JSON of [com.guftugu.app.protocol.SystemInfo]. */
    val systemJson: String? = null,
    /** JSON of the [com.guftugu.app.protocol.Envelope]; kept only while undecryptable. */
    val envelopeJson: String? = null,
    /** Decrypted `replyTo` message id. */
    val replyTo: String? = null,
    /** [MessageStatus] */
    val status: String = MessageStatus.SENT,
)

@Entity(tableName = "conv_keys", primaryKeys = ["convId", "keyId"])
data class ConvKeyEntity(
    val convId: String,
    val keyId: String,
    /** The 32-byte conversation key, wrapped by `guftugu_wrap` (iv ‖ ct+tag). */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val keyWrapped: ByteArray,
    val receivedAt: Long = 0L,
) {
    override fun equals(other: Any?): Boolean =
        other is ConvKeyEntity && other.convId == convId && other.keyId == keyId && other.keyWrapped.contentEquals(keyWrapped)

    override fun hashCode(): Int = 31 * convId.hashCode() + keyId.hashCode()
}

@Entity(tableName = "outbox", indices = [Index("convId")])
data class OutboxEntity(
    /** Also the idempotency key sent to the server. */
    @PrimaryKey val clientId: String,
    val convId: String,
    val createdAt: Long,
    /** JSON of the plaintext [com.guftugu.app.protocol.Content] (attachment metadata filled in after upload). */
    val contentJson: String,
    /** Local content:// or file:// to encrypt + upload before sending, if any. */
    val localUri: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "media_cache")
data class MediaCacheEntity(
    /** Blob-store object key. */
    @PrimaryKey val key: String,
    /** Decrypted file in the app-private cache dir. */
    val localPath: String,
    val sizeBytes: Long,
    val lastUsedAt: Long,
)
