/**
 * Guftugu Protocol v1 — wire types.
 *
 * One-to-one Kotlin mirror of `server/src/protocol/types.ts` (and PROTOCOL.md §13).
 * Keep the three in sync: if a request/response shape changes, change the doc,
 * the TS types and these DTOs together.
 *
 * Conventions
 * - Timestamps are epoch milliseconds (UTC) as numbers → `Long`.
 * - Binary values (keys, signatures, nonces, ciphertext) are base64url without padding → `String`.
 * - TS string unions are `String` fields with the allowed values as constants
 *   (objects below) so an unknown value from a newer/other server never crashes decoding.
 * - Optional/nullable TS fields are nullable with default `null`.
 *
 * Decode with [ProtocolJson] (ignoreUnknownKeys, explicitNulls = false).
 */
package com.guftugu.app.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

const val PROTOCOL_VERSION = 1

/** The one Json instance used for everything that crosses the wire. */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = false
    coerceInputValues = false
}

// ---------- identity & auth ----------

@Serializable
data class Session(
    val token: String,
    val expiresAt: Long,
)

object UserRole {
    const val MEMBER = "member"
    const val ADMIN = "admin"
}

object UserStatus {
    const val ACTIVE = "active"
    const val DISABLED = "disabled"
}

@Serializable
data class User(
    val userId: String,
    val displayName: String,
    val avatarKey: String? = null,
    /** [UserRole] */
    val role: String,
    /** [UserStatus] */
    val status: String,
    val createdAt: Long,
    val lastSeenAt: Long? = null,
    /** Only present on the caller's own account (`Me` in PROTOCOL §13): whether a backup password is set. */
    val hasPassword: Boolean? = null,
)

/** Public part of a device: what other members need to wrap keys and verify signatures. */
@Serializable
data class PublicDevice(
    val deviceId: String,
    val userId: String,
    val name: String,
    /** base64url SPKI DER, P-256, ECDSA — proves the installed app instance, signs key wraps. */
    val devicePublicKey: String,
    /** base64url SPKI DER, P-256, ECDH — receives wrapped conversation keys. */
    val encryptionPublicKey: String,
    val enrolledAt: Long,
)

/** `Device extends PublicDevice` in TS; flattened here (Kotlin data classes cannot inherit). */
@Serializable
data class Device(
    val deviceId: String,
    val userId: String,
    val name: String,
    val devicePublicKey: String,
    val encryptionPublicKey: String,
    val enrolledAt: Long,
    val model: String? = null,
    val os: String? = null,
    val appVersion: String? = null,
    val hasBiometricKey: Boolean,
    val lastSeenAt: Long? = null,
    val revokedAt: Long? = null,
) {
    fun asPublic(): PublicDevice =
        PublicDevice(deviceId, userId, name, devicePublicKey, encryptionPublicKey, enrolledAt)
}

@Serializable
data class DeviceInfo(
    val name: String,
    val model: String? = null,
    val os: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class EnrollRequest(
    val inviteCode: String,
    val displayName: String? = null,
    val password: String? = null,
    val devicePublicKey: String,
    val authPublicKey: String? = null,
    val encryptionPublicKey: String,
    val device: DeviceInfo,
)

@Serializable
data class AuthChallengeRequest(
    val deviceId: String,
)

@Serializable
data class AuthChallengeResponse(
    val nonce: String,
    val expiresAt: Long,
)

object AuthMethod {
    const val BIOMETRIC = "biometric"
    const val PASSWORD = "password"
    /** Device key alone — only for accounts with neither a passcode nor a fingerprint key (PROTOCOL §4). */
    const val DEVICE = "device"
}

@Serializable
data class AuthVerifyRequest(
    val deviceId: String,
    val nonce: String,
    /** [AuthMethod] */
    val method: String,
    /** base64url ECDSA P-256/SHA-256 signature, raw r‖s (DER also accepted by servers). */
    val signature: String,
    val password: String? = null,
)

/** `PUT /me/device/auth-key` (PROTOCOL §4, "Turning on fingerprint unlock"). */
@Serializable
data class RegisterAuthKeyRequest(
    val nonce: String,
    val authPublicKey: String,
    val deviceSignature: String,
    val authSignature: String,
)

@Serializable
data class AuthResponse(
    val user: User,
    val device: Device,
    val session: Session,
    val config: ClientConfig,
)

@Serializable
data class Invite(
    /** admin | link | friend | group (PROTOCOL §5a). */
    val kind: String? = null,
    val convId: String? = null,
    val invitedBy: String? = null,
    val code: String,
    val link: String,
    val displayName: String? = null,
    /** [UserRole] */
    val role: String,
    val forUserId: String? = null,
    val autoJoin: List<String> = emptyList(),
    val createdAt: Long,
    val expiresAt: Long,
    val usedAt: Long? = null,
    val usedByUserId: String? = null,
)

// ---------- friends (PROTOCOL §5a) ----------

@Serializable
data class Friend(
    val user: User,
    /** null = not friends (a row that only records my block). */
    val since: Long? = null,
    val blocked: Boolean = false,
)

@Serializable
data class CreateInviteRequest(val kind: String, val convId: String? = null)

@Serializable
data class RedeemInviteRequest(val code: String)

@Serializable
data class RedeemInviteResponse(val friend: User, val conversation: Conversation? = null)

// ---------- conversations & keys ----------

object ConversationType {
    const val DIRECT = "direct"
    const val GROUP = "group"
}

object MemberRole {
    const val OWNER = "owner"
    const val MEMBER = "member"
}

@Serializable
data class Member(
    val userId: String,
    /** [MemberRole] */
    val role: String,
    val joinedAt: Long,
    val lastReadMsgId: String? = null,
)

@Serializable
data class Conversation(
    val convId: String,
    /** [ConversationType] */
    val type: String,
    val name: String? = null,
    val avatarKey: String? = null,
    val createdBy: String,
    val createdAt: Long,
    val autoJoin: Boolean? = null,
    val members: List<Member> = emptyList(),
    val currentKeyId: String? = null,
    val keyRotationRequired: Boolean = false,
    val lastMsgId: String? = null,
    val lastMessageAt: Long? = null,
)

/** AES-256-GCM ciphertext the server cannot open. */
@Serializable
data class Envelope(
    val v: Int = 1,
    val keyId: String,
    /** base64url, 12 bytes */
    val iv: String,
    /** base64url, ciphertext + 16-byte tag */
    val ct: String,
)

/** A conversation key encrypted (ECIES) for exactly one device and signed by the sender device. */
@Serializable
data class WrappedKey(
    val keyId: String,
    val convId: String,
    val recipientDeviceId: String,
    val senderDeviceId: String,
    val ephemeralPublicKey: String,
    val iv: String,
    val ciphertext: String,
    val signature: String,
    val createdAt: Long,
)

@Serializable
data class KeysResponse(
    val currentKeyId: String? = null,
    val items: List<WrappedKey> = emptyList(),
)

/** `KeyRecipient extends PublicDevice` in TS; flattened. */
@Serializable
data class KeyRecipient(
    val deviceId: String,
    val userId: String,
    val name: String,
    val devicePublicKey: String,
    val encryptionPublicKey: String,
    val enrolledAt: Long,
    val hasCurrentKey: Boolean,
) {
    fun asPublic(): PublicDevice =
        PublicDevice(deviceId, userId, name, devicePublicKey, encryptionPublicKey, enrolledAt)
}

@Serializable
data class KeyRecipientsResponse(
    val currentKeyId: String? = null,
    val keyRotationRequired: Boolean = false,
    val devices: List<KeyRecipient> = emptyList(),
)

@Serializable
data class PostKeysRequest(
    val keyId: String,
    val wrapped: List<WrappedKey>,
)

@Serializable
data class PostKeysResponse(
    val currentKeyId: String? = null,
)

// ---------- messages ----------

object MessageKind {
    const val E2E = "e2e"
    const val CALL = "call"
    const val SYSTEM = "system"
}

object SystemEvent {
    const val MEMBER_ADDED = "member_added"
    const val MEMBER_REMOVED = "member_removed"
    const val MEMBER_LEFT = "member_left"
    const val RENAMED = "renamed"
    const val CREATED = "created"
}

@Serializable
data class SystemInfo(
    /** [SystemEvent] */
    val event: String,
    val userIds: List<String>? = null,
    val text: String? = null,
)

object CallOutcome {
    const val ANSWERED = "answered"
    const val MISSED = "missed"
    const val REJECTED = "rejected"
    const val UNREACHABLE = "unreachable"
    const val CANCELLED = "cancelled"
}

@Serializable
data class CallInfo(
    val callId: String,
    /** [CallType] */
    val type: String,
    /** [CallOutcome] */
    val outcome: String,
    val durationMs: Long? = null,
)

@Serializable
data class Message(
    val msgId: String,
    val convId: String,
    val senderId: String,
    val senderDeviceId: String? = null,
    val clientId: String? = null,
    /** [MessageKind] */
    val kind: String,
    val envelope: Envelope? = null,
    val call: CallInfo? = null,
    val system: SystemInfo? = null,
    val sentAt: Long,
    val createdAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class SendMessageRequest(
    val clientId: String,
    val sentAt: Long,
    val envelope: Envelope,
)

/** `{ message }` wrapper returned by POST/DELETE message endpoints. */
@Serializable
data class MessageResponse(
    val message: Message,
)

@Serializable
data class Page<T>(
    val items: List<T> = emptyList(),
    val hasMore: Boolean = false,
)

// ---------- media ----------

object MediaKind {
    const val ATTACHMENT = "attachment"
    const val THUMBNAIL = "thumbnail"
    const val AVATAR = "avatar"
}

@Serializable
data class UploadUrlRequest(
    val convId: String? = null,
    /** [MediaKind] */
    val kind: String,
    val mime: String,
    val sizeBytes: Long,
)

@Serializable
data class UploadUrlResponse(
    val key: String,
    val uploadUrl: String,
    /** Always "PUT" in v1. */
    val method: String = "PUT",
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Long,
)

@Serializable
data class DownloadUrlResponse(
    val downloadUrl: String,
    val expiresAt: Long,
)

// ---------- calls ----------

object CallType {
    const val AUDIO = "audio"
    const val VIDEO = "video"
}

object CallState {
    const val RINGING = "ringing"
    const val ACTIVE = "active"
    const val ENDED = "ended"
}

object EndReason {
    const val HANGUP = "hangup"
    const val TIMEOUT = "timeout"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val REJECTED = "rejected"
    const val UNREACHABLE = "unreachable"
    const val ANSWERED_ELSEWHERE = "answered_elsewhere"
}

@Serializable
data class Call(
    val callId: String,
    val convId: String,
    /** [CallType] */
    val type: String,
    val callerId: String,
    val callerDeviceId: String,
    val calleeId: String,
    val calleeDeviceId: String? = null,
    /** [CallState] */
    val state: String,
    val createdAt: Long,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    /** [EndReason] (free-form string on the wire) */
    val endReason: String? = null,
)

// ---------- config ----------

@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class ClientFeatures(
    val calls: Boolean = true,
    val media: Boolean = true,
)

@Serializable
data class ClientConfig(
    val serverName: String,
    val iceServers: List<IceServer> = emptyList(),
    val maxUploadBytes: Long,
    val features: ClientFeatures = ClientFeatures(),
)

/** Admin-only view of settings (`ServerSettings extends ClientConfig`), flattened. */
@Serializable
data class ServerSettings(
    val serverName: String,
    val iceServers: List<IceServer> = emptyList(),
    val maxUploadBytes: Long,
    val features: ClientFeatures = ClientFeatures(),
    val defaultAutoJoin: Boolean,
    val inviteTtlHours: Int,
)

@Serializable
data class WellKnown(
    val name: String,
    val protocolVersion: Int,
    val apiUrl: String,
    val wsUrl: String,
    /** "invite" in v1 */
    val enrollment: String = "invite",
    val serverTime: Long,
)

// ---------- errors ----------

object ErrorCode {
    const val INVALID_REQUEST = "invalid_request"
    const val UNAUTHORIZED = "unauthorized"
    const val FORBIDDEN = "forbidden"
    const val NOT_FOUND = "not_found"
    const val CONFLICT = "conflict"
    const val PAYLOAD_TOO_LARGE = "payload_too_large"
    const val UPGRADE_REQUIRED = "upgrade_required"
    const val RATE_LIMITED = "rate_limited"
    const val INTERNAL = "internal"
    const val INVITE_INVALID = "invite_invalid"
    const val INVITE_EXPIRED = "invite_expired"
    const val CHALLENGE_INVALID = "challenge_invalid"
    const val BAD_SIGNATURE = "bad_signature"
    const val BAD_CREDENTIALS = "bad_credentials"
    const val DEVICE_REVOKED = "device_revoked"
    const val USER_DISABLED = "user_disabled"
    const val PASSWORD_LOCKED = "password_locked"

    /** Client-side pseudo code for transport failures (not from the server). */
    const val NETWORK = "network"
}

@Serializable
data class ErrorDetail(
    /** [ErrorCode] */
    val code: String,
    val message: String = "",
)

@Serializable
data class ErrorBody(
    val error: ErrorDetail,
)

// ---------- websocket frames: server → client ----------

/**
 * Server → client frames (`{ "type": …, …payload }`). Decoding is content-based on the
 * `type` discriminator; an unrecognised type becomes [ServerEvent.Unknown] instead of failing.
 */
@Serializable(with = ServerEventSerializer::class)
sealed class ServerEvent {
    @Serializable
    data class Hello(
        val userId: String,
        val deviceId: String,
        val connectionId: String,
        val serverTime: Long,
    ) : ServerEvent()

    @Serializable
    data class Pong(val serverTime: Long) : ServerEvent()

    @Serializable
    data class MessageNew(val message: Message) : ServerEvent()

    @Serializable
    data class MessageDeleted(val convId: String, val msgId: String, val deletedAt: Long) : ServerEvent()

    @Serializable
    data class ConversationUpdated(val conversation: Conversation) : ServerEvent()

    @Serializable
    data class ConversationKeys(val convId: String, val keyId: String) : ServerEvent()

    @Serializable
    data class ConversationRead(val convId: String, val userId: String, val msgId: String, val at: Long) : ServerEvent()

    @Serializable
    data class Typing(val convId: String, val userId: String, val at: Long) : ServerEvent()

    @Serializable
    data class UserUpdated(val user: User) : ServerEvent()

    @Serializable
    data class CallInvite(val call: Call, val caller: User) : ServerEvent()

    @Serializable
    data class CallAnswered(val call: Call) : ServerEvent()

    @Serializable
    data class CallEnded(val call: Call, val reason: String) : ServerEvent()

    @Serializable
    data class CallSignal(val callId: String, val fromDeviceId: String, val envelope: Envelope) : ServerEvent()

    @Serializable
    data class Error(val code: String, val message: String = "") : ServerEvent()

    /** Any frame whose `type` this app version does not know. Never crashes decoding. */
    data class Unknown(val type: String, val raw: JsonObject) : ServerEvent()

    companion object {
        const val HELLO = "hello"
        const val PONG = "pong"
        const val MESSAGE_NEW = "message.new"
        const val MESSAGE_DELETED = "message.deleted"
        const val CONVERSATION_UPDATED = "conversation.updated"
        const val CONVERSATION_KEYS = "conversation.keys"
        const val CONVERSATION_READ = "conversation.read"
        const val TYPING = "typing"
        const val USER_UPDATED = "user.updated"
        const val CALL_INVITE = "call.invite"
        const val CALL_ANSWERED = "call.answered"
        const val CALL_ENDED = "call.ended"
        const val CALL_SIGNAL = "call.signal"
        const val ERROR = "error"
    }
}

/** Wire name of a [ServerEvent] (the `type` field). */
val ServerEvent.type: String
    get() = when (this) {
        is ServerEvent.Hello -> ServerEvent.HELLO
        is ServerEvent.Pong -> ServerEvent.PONG
        is ServerEvent.MessageNew -> ServerEvent.MESSAGE_NEW
        is ServerEvent.MessageDeleted -> ServerEvent.MESSAGE_DELETED
        is ServerEvent.ConversationUpdated -> ServerEvent.CONVERSATION_UPDATED
        is ServerEvent.ConversationKeys -> ServerEvent.CONVERSATION_KEYS
        is ServerEvent.ConversationRead -> ServerEvent.CONVERSATION_READ
        is ServerEvent.Typing -> ServerEvent.TYPING
        is ServerEvent.UserUpdated -> ServerEvent.USER_UPDATED
        is ServerEvent.CallInvite -> ServerEvent.CALL_INVITE
        is ServerEvent.CallAnswered -> ServerEvent.CALL_ANSWERED
        is ServerEvent.CallEnded -> ServerEvent.CALL_ENDED
        is ServerEvent.CallSignal -> ServerEvent.CALL_SIGNAL
        is ServerEvent.Error -> ServerEvent.ERROR
        is ServerEvent.Unknown -> type
    }

/**
 * JSON-only serializer for [ServerEvent]: picks the concrete class from the `type` field,
 * maps unknown types to [ServerEvent.Unknown], and writes `type` back on encode.
 */
object ServerEventSerializer : KSerializer<ServerEvent> {
    private const val DISCRIMINATOR = "type"

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.guftugu.app.protocol.ServerEvent")

    @Suppress("UNCHECKED_CAST")
    private fun deserializerFor(type: String?): DeserializationStrategy<ServerEvent>? = when (type) {
        ServerEvent.HELLO -> ServerEvent.Hello.serializer()
        ServerEvent.PONG -> ServerEvent.Pong.serializer()
        ServerEvent.MESSAGE_NEW -> ServerEvent.MessageNew.serializer()
        ServerEvent.MESSAGE_DELETED -> ServerEvent.MessageDeleted.serializer()
        ServerEvent.CONVERSATION_UPDATED -> ServerEvent.ConversationUpdated.serializer()
        ServerEvent.CONVERSATION_KEYS -> ServerEvent.ConversationKeys.serializer()
        ServerEvent.CONVERSATION_READ -> ServerEvent.ConversationRead.serializer()
        ServerEvent.TYPING -> ServerEvent.Typing.serializer()
        ServerEvent.USER_UPDATED -> ServerEvent.UserUpdated.serializer()
        ServerEvent.CALL_INVITE -> ServerEvent.CallInvite.serializer()
        ServerEvent.CALL_ANSWERED -> ServerEvent.CallAnswered.serializer()
        ServerEvent.CALL_ENDED -> ServerEvent.CallEnded.serializer()
        ServerEvent.CALL_SIGNAL -> ServerEvent.CallSignal.serializer()
        ServerEvent.ERROR -> ServerEvent.Error.serializer()
        else -> null
    } as DeserializationStrategy<ServerEvent>?

    override fun deserialize(decoder: Decoder): ServerEvent {
        val input = decoder as? JsonDecoder ?: error("ServerEvent can only be decoded from JSON")
        val element = input.decodeJsonElement()
        val obj = element as? JsonObject ?: return ServerEvent.Unknown("", JsonObject(emptyMap()))
        val type = obj[DISCRIMINATOR]?.let { (it as? JsonPrimitive)?.contentOrNull }
        val strategy = deserializerFor(type) ?: return ServerEvent.Unknown(type ?: "", obj)
        return try {
            input.json.decodeFromJsonElement(strategy, obj)
        } catch (e: kotlinx.serialization.SerializationException) {
            // A known type with an unexpected shape: surface it as Unknown rather than killing the socket loop.
            ServerEvent.Unknown(type ?: "", obj)
        } catch (e: IllegalArgumentException) {
            ServerEvent.Unknown(type ?: "", obj)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun serialize(encoder: Encoder, value: ServerEvent) {
        val output = encoder as? JsonEncoder ?: error("ServerEvent can only be encoded to JSON")
        val body: JsonObject = when (value) {
            is ServerEvent.Unknown -> value.raw
            else -> {
                val strategy = deserializerFor(value.type) as SerializationStrategy<ServerEvent>
                output.json.encodeToJsonElement(strategy, value).jsonObject
            }
        }
        val withType = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(body.size + 1)
        withType[DISCRIMINATOR] = JsonPrimitive(value.type)
        body.forEach { (k, v) -> if (k != DISCRIMINATOR) withType[k] = v }
        output.encodeJsonElement(JsonObject(withType))
    }
}

// ---------- websocket frames: client → server ----------

/** Client → server frames. Encoded with `type` as the class discriminator. */
@Serializable
@JsonClassDiscriminator("type")
sealed class ClientEvent {
    /** First frame after connecting. */
    @Serializable
    @SerialName("hello")
    data object Hello : ClientEvent()

    /** At least every 4 minutes. */
    @Serializable
    @SerialName("ping")
    data object Ping : ClientEvent()

    /** ≤ 1 per 3 s per conversation. */
    @Serializable
    @SerialName("typing")
    data class Typing(val convId: String) : ClientEvent()

    /** Relayed to the other party's negotiating device. */
    @Serializable
    @SerialName("call.signal")
    data class CallSignal(val callId: String, val envelope: Envelope) : ClientEvent()
}

/** Convenience: parse one raw WebSocket text frame. Never throws on unknown types. */
fun Json.decodeServerEvent(text: String): ServerEvent = decodeFromString(ServerEventSerializer, text)

/** Convenience: encode one client frame. */
fun Json.encodeClientEvent(event: ClientEvent): String = encodeToString(ClientEvent.serializer(), event)
