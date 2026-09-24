package com.guftugu.app.data.api

import com.guftugu.app.protocol.AuthChallengeRequest
import com.guftugu.app.protocol.AuthChallengeResponse
import com.guftugu.app.protocol.AuthResponse
import com.guftugu.app.protocol.AuthVerifyRequest
import com.guftugu.app.protocol.Call
import com.guftugu.app.protocol.ClientConfig
import com.guftugu.app.protocol.Conversation
import com.guftugu.app.protocol.ConversationType
import com.guftugu.app.protocol.Device
import com.guftugu.app.protocol.DownloadUrlResponse
import com.guftugu.app.protocol.EnrollRequest
import com.guftugu.app.protocol.ErrorBody
import com.guftugu.app.protocol.ErrorCode
import com.guftugu.app.protocol.Invite
import com.guftugu.app.protocol.Friend
import com.guftugu.app.protocol.RedeemInviteResponse
import com.guftugu.app.protocol.RedeemInviteRequest
import com.guftugu.app.protocol.CreateInviteRequest
import com.guftugu.app.protocol.KeyRecipientsResponse
import com.guftugu.app.protocol.KeysResponse
import com.guftugu.app.protocol.Message
import com.guftugu.app.protocol.MessageResponse
import com.guftugu.app.protocol.PROTOCOL_VERSION
import com.guftugu.app.protocol.Page
import com.guftugu.app.protocol.PostKeysRequest
import com.guftugu.app.protocol.RegisterAuthKeyRequest
import com.guftugu.app.protocol.PostKeysResponse
import com.guftugu.app.protocol.ProtocolJson
import com.guftugu.app.protocol.PublicDevice
import com.guftugu.app.protocol.SendMessageRequest
import com.guftugu.app.protocol.UploadUrlRequest
import com.guftugu.app.protocol.UploadUrlResponse
import com.guftugu.app.protocol.User
import com.guftugu.app.protocol.WellKnown
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source

/** A non-2xx answer from the server (PROTOCOL "Error shape"), or a transport failure ([status] = 0, [code] = "network"). */
class ApiException(
    /** [ErrorCode] */
    val code: String,
    val status: Int,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    val isUnauthorized: Boolean get() = status == 401 || code == ErrorCode.UNAUTHORIZED
    val isNetwork: Boolean get() = status == 0
    override fun toString(): String = "ApiException($status $code: $message)"
}

/**
 * The REST half of PROTOCOL.md. One method per endpoint; every method suspends, runs on OkHttp's
 * dispatcher, and throws [ApiException] on any non-2xx status or transport error.
 *
 * - `X-Guftugu-Protocol: 1` on every request.
 * - `Authorization: Bearer <token>` from [token] on authenticated endpoints.
 * - Base URL from [apiUrl] (set after joining a server). Discovery and enrolment accept an
 *   explicit URL because the store is not populated yet at that point.
 * - Never logs bodies, headers or tokens.
 *
 * Certificate pinning: add a `CertificatePinner` to the [OkHttpClient] built in `AppGraph` — the
 * single place to pin, per SECURITY.md "Transport".
 */
class GuftuguApi(
    private val client: OkHttpClient,
    private val json: Json = ProtocolJson,
    private val apiUrl: () -> String?,
    private val token: () -> String?,
) {
    // ---------- §1 discovery ----------

    /** `GET <serverUrl>/.well-known/guftugu`. [serverUrl] may be the apiUrl from an invite link. */
    suspend fun wellKnown(serverUrl: String): WellKnown =
        get(url(serverUrl, ".well-known/guftugu"), WellKnown.serializer(), auth = false)

    // ---------- §3 enrolment ----------

    suspend fun enroll(request: EnrollRequest, serverUrl: String? = null): AuthResponse =
        post(url(serverUrl ?: base(), "enroll"), request, EnrollRequest.serializer(), AuthResponse.serializer(), auth = false)

    // ---------- §4 login ----------

    suspend fun authChallenge(deviceId: String): AuthChallengeResponse =
        post(url(base(), "auth/challenge"), AuthChallengeRequest(deviceId), AuthChallengeRequest.serializer(), AuthChallengeResponse.serializer(), auth = false)

    suspend fun authVerify(request: AuthVerifyRequest): AuthResponse =
        post(url(base(), "auth/verify"), request, AuthVerifyRequest.serializer(), AuthResponse.serializer(), auth = false)

    suspend fun logout() = postNoContent(url(base(), "auth/logout"))

    // ---------- §5 users, devices, self ----------

    suspend fun me(): User = get(url(base(), "me"), User.serializer())

    suspend fun patchMe(displayName: String? = null, avatarKey: String? = null): User =
        patch(url(base(), "me"), PatchMeRequest(displayName, avatarKey), PatchMeRequest.serializer(), User.serializer())

    /** Sets a first backup password (no [currentPassword] needed) or changes an existing one. */
    suspend fun changePassword(currentPassword: String?, newPassword: String) =
        putNoContent(url(base(), "me/password"), ChangePasswordRequest(currentPassword?.ifEmpty { null }, newPassword), ChangePasswordRequest.serializer())

    /** `PUT /me/device/auth-key`: turn on / replace fingerprint unlock for this device (PROTOCOL §4). */
    suspend fun registerAuthKey(request: RegisterAuthKeyRequest): Device =
        call(Request.Builder().url(url(base(), "me/device/auth-key")).put(jsonBody(request, RegisterAuthKeyRequest.serializer())).common(true).build(), Device.serializer())

    suspend fun myDevices(): List<Device> = get(url(base(), "me/devices"), Page.serializer(Device.serializer())).items

    suspend fun revokeDevice(deviceId: String) = deleteNoContent(url(base(), "me/devices", deviceId))

    suspend fun createLinkCode(): Invite = post(url(base(), "me/link-code"), null, Unit.serializer(), Invite.serializer())

    suspend fun users(): List<User> = get(url(base(), "users"), Page.serializer(User.serializer())).items

    // ---------- §5a friends & invites ----------

    /** A single-use invite for a friend (1:1 chat) or into one of my groups ([convId]). */
    suspend fun createInvite(kind: String, convId: String? = null): Invite =
        post(url(base(), "invites"), CreateInviteRequest(kind, convId), CreateInviteRequest.serializer(), Invite.serializer())

    suspend fun redeemInvite(code: String): RedeemInviteResponse =
        post(url(base(), "invites", "redeem"), RedeemInviteRequest(code), RedeemInviteRequest.serializer(), RedeemInviteResponse.serializer())

    suspend fun friends(): List<Friend> = get(url(base(), "friends"), Page.serializer(Friend.serializer())).items

    suspend fun block(userId: String) = postNoContent(url(base(), "friends", userId, "block"))

    suspend fun unblock(userId: String) = deleteNoContent(url(base(), "friends", userId, "block"))

    suspend fun userDevices(userId: String): List<PublicDevice> =
        get(url(base(), "users", userId, "devices"), Page.serializer(PublicDevice.serializer())).items

    suspend fun config(): ClientConfig = get(url(base(), "config"), ClientConfig.serializer())

    // ---------- §6 conversations ----------

    suspend fun conversations(): List<Conversation> =
        get(url(base(), "conversations"), Page.serializer(Conversation.serializer())).items

    suspend fun createDirect(memberId: String): Conversation =
        post(url(base(), "conversations"), CreateConversationRequest(type = ConversationType.DIRECT, memberId = memberId), CreateConversationRequest.serializer(), Conversation.serializer())

    suspend fun createGroup(name: String, memberIds: List<String>): Conversation =
        post(url(base(), "conversations"), CreateConversationRequest(type = ConversationType.GROUP, name = name, memberIds = memberIds), CreateConversationRequest.serializer(), Conversation.serializer())

    suspend fun conversation(convId: String): Conversation = get(url(base(), "conversations", convId), Conversation.serializer())

    suspend fun patchConversation(convId: String, name: String? = null, avatarKey: String? = null): Conversation =
        patch(url(base(), "conversations", convId), PatchConversationRequest(name, avatarKey), PatchConversationRequest.serializer(), Conversation.serializer())

    suspend fun addMembers(convId: String, userIds: List<String>): Conversation =
        post(url(base(), "conversations", convId, "members"), AddMembersRequest(userIds), AddMembersRequest.serializer(), Conversation.serializer())

    suspend fun removeMember(convId: String, userId: String): Conversation =
        delete(url(base(), "conversations", convId, "members", userId), Conversation.serializer())

    suspend fun markRead(convId: String, msgId: String) =
        putNoContent(url(base(), "conversations", convId, "read"), ReadRequest(msgId), ReadRequest.serializer())

    // ---------- §7 keys ----------

    suspend fun keys(convId: String): KeysResponse = get(url(base(), "conversations", convId, "keys"), KeysResponse.serializer())

    suspend fun keyRecipients(convId: String): KeyRecipientsResponse =
        get(url(base(), "conversations", convId, "key-recipients"), KeyRecipientsResponse.serializer())

    suspend fun postKeys(convId: String, request: PostKeysRequest): PostKeysResponse =
        post(url(base(), "conversations", convId, "keys"), request, PostKeysRequest.serializer(), PostKeysResponse.serializer())

    // ---------- §8 messages ----------

    /**
     * `after` → ascending, exclusive; `before` → descending, exclusive; neither → latest [limit], descending.
     * [limit] ≤ 200.
     */
    suspend fun messages(convId: String, after: String? = null, before: String? = null, limit: Int = 50): Page<Message> {
        val u = url(base(), "conversations", convId, "messages").newBuilder().apply {
            if (after != null) addQueryParameter("after", after)
            if (before != null) addQueryParameter("before", before)
            addQueryParameter("limit", limit.coerceIn(1, 200).toString())
        }.build()
        return get(u, Page.serializer(Message.serializer()))
    }

    /** 201 for a new message, 200 with the original for a repeated `clientId` — both return `{ message }`. */
    suspend fun sendMessage(convId: String, request: SendMessageRequest): Message =
        post(url(base(), "conversations", convId, "messages"), request, SendMessageRequest.serializer(), MessageResponse.serializer()).message

    suspend fun deleteMessage(convId: String, msgId: String): Message =
        delete(url(base(), "conversations", convId, "messages", msgId), MessageResponse.serializer()).message

    // ---------- §9 media ----------

    suspend fun uploadUrl(request: UploadUrlRequest): UploadUrlResponse =
        post(url(base(), "media/upload-url"), request, UploadUrlRequest.serializer(), UploadUrlResponse.serializer())

    suspend fun downloadUrl(key: String): DownloadUrlResponse {
        val u = url(base(), "media/download-url").newBuilder().addQueryParameter("key", key).build()
        return get(u, DownloadUrlResponse.serializer())
    }

    /**
     * Uploads a file's bytes to a presigned URL (no Guftugu headers/auth — the URL is the credential).
     * [headers] come from [UploadUrlResponse.headers]. [onProgress] receives bytes sent so far.
     */
    suspend fun upload(
        uploadUrl: String,
        method: String,
        headers: Map<String, String>,
        file: File,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ) = upload(uploadUrl, method, headers, { file.inputStream() }, file.length(), onProgress)

    suspend fun upload(
        uploadUrl: String,
        method: String,
        headers: Map<String, String>,
        openStream: () -> InputStream,
        contentLength: Long,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ) {
        val mediaType = (headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: "application/octet-stream").toMediaType()
        val body = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = contentLength
            override fun isOneShot() = false
            override fun writeTo(sink: BufferedSink) {
                openStream().use { input ->
                    input.source().use { source ->
                        var sent = 0L
                        val buffer = okio.Buffer()
                        while (true) {
                            val n = source.read(buffer, 64 * 1024L)
                            if (n < 0) break
                            sink.write(buffer, n)
                            sent += n
                            onProgress?.invoke(sent, contentLength)
                        }
                    }
                }
            }
        }
        val req = Request.Builder().url(uploadUrl).method(method.uppercase(), body).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        execute(req).use { resp ->
            if (!resp.isSuccessful) throw ApiException("upload_failed", resp.code, "upload failed with HTTP ${resp.code}")
        }
    }

    /** Downloads a (presigned) URL to [toFile] (written via a temp file; replaced atomically). */
    suspend fun download(url: String, toFile: File, onProgress: ((received: Long, total: Long) -> Unit)? = null) {
        val req = Request.Builder().url(url).get().build()
        execute(req).use { resp ->
            if (!resp.isSuccessful) throw ApiException("download_failed", resp.code, "download failed with HTTP ${resp.code}")
            val body = resp.body
            val total = body.contentLength()
            toFile.parentFile?.mkdirs()
            val tmp = File(toFile.parentFile, toFile.name + ".part")
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            received += n
                            onProgress?.invoke(received, total)
                        }
                    }
                }
                if (toFile.exists()) toFile.delete()
                if (!tmp.renameTo(toFile)) {
                    tmp.copyTo(toFile, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    // ---------- request bodies that PROTOCOL.md defines inline (not in types.ts) ----------

    @Serializable
    private data class PatchMeRequest(val displayName: String? = null, val avatarKey: String? = null)

    @Serializable
    private data class ChangePasswordRequest(val currentPassword: String? = null, val newPassword: String)

    @Serializable
    private data class CreateConversationRequest(
        val type: String,
        val memberId: String? = null,
        val name: String? = null,
        val memberIds: List<String>? = null,
    )

    @Serializable
    private data class PatchConversationRequest(val name: String? = null, val avatarKey: String? = null)

    @Serializable
    private data class AddMembersRequest(val userIds: List<String>)

    @Serializable
    private data class ReadRequest(val msgId: String)

    @Serializable
    private data class CreateCallRequest(val convId: String, val type: String)

    @Serializable
    private data class EndCallRequest(val reason: String)

    // ---------- §11 calls ----------

    suspend fun startCall(convId: String, type: String): Call =
        post(url(base(), "calls"), CreateCallRequest(convId, type), CreateCallRequest.serializer(), Call.serializer())

    suspend fun answerCall(callId: String): Call = post(url(base(), "calls", callId, "answer"), null, Unit.serializer(), Call.serializer())

    suspend fun rejectCall(callId: String): Call = post(url(base(), "calls", callId, "reject"), null, Unit.serializer(), Call.serializer())

    suspend fun endCall(callId: String, reason: String): Call =
        post(url(base(), "calls", callId, "end"), EndCallRequest(reason), EndCallRequest.serializer(), Call.serializer())

    suspend fun call(callId: String): Call = get(url(base(), "calls", callId), Call.serializer())

    // ---------- plumbing ----------

    private fun base(): String = apiUrl() ?: throw ApiException("not_configured", 0, "no server configured")

    private fun url(base: String, vararg segments: String): HttpUrl {
        val root = base.trimEnd('/').toHttpUrlOrNull() ?: throw ApiException("invalid_url", 0, "invalid server url")
        return root.newBuilder().apply { segments.forEach { addPathSegments(it) } }.build()
    }

    private fun Request.Builder.common(auth: Boolean): Request.Builder {
        header("X-Guftugu-Protocol", PROTOCOL_VERSION.toString())
        header("Accept", "application/json")
        if (auth) {
            val t = token() ?: throw ApiException(ErrorCode.UNAUTHORIZED, 401, "no session")
            header("Authorization", "Bearer $t")
        }
        return this
    }

    private fun <T> jsonBody(value: T?, strategy: SerializationStrategy<T>): RequestBody {
        val text = if (value == null) "{}" else json.encodeToString(strategy, value)
        return text.toRequestBody(JSON_MEDIA_TYPE)
    }

    private suspend fun <R> get(url: HttpUrl, response: DeserializationStrategy<R>, auth: Boolean = true): R =
        call(Request.Builder().url(url).get().common(auth).build(), response)

    private suspend fun <T, R> post(url: HttpUrl, body: T?, request: SerializationStrategy<T>, response: DeserializationStrategy<R>, auth: Boolean = true): R =
        call(Request.Builder().url(url).post(jsonBody(body, request)).common(auth).build(), response)

    private suspend fun <T, R> patch(url: HttpUrl, body: T, request: SerializationStrategy<T>, response: DeserializationStrategy<R>, auth: Boolean = true): R =
        call(Request.Builder().url(url).patch(jsonBody(body, request)).common(auth).build(), response)

    private suspend fun <R> delete(url: HttpUrl, response: DeserializationStrategy<R>, auth: Boolean = true): R =
        call(Request.Builder().url(url).delete().common(auth).build(), response)

    private suspend fun postNoContent(url: HttpUrl, auth: Boolean = true) =
        callNoContent(Request.Builder().url(url).post(jsonBody(null, Unit.serializer())).common(auth).build())

    private suspend fun <T> putNoContent(url: HttpUrl, body: T, request: SerializationStrategy<T>, auth: Boolean = true) =
        callNoContent(Request.Builder().url(url).put(jsonBody(body, request)).common(auth).build())

    private suspend fun deleteNoContent(url: HttpUrl, auth: Boolean = true) =
        callNoContent(Request.Builder().url(url).delete().common(auth).build())

    private suspend fun <R> call(request: Request, response: DeserializationStrategy<R>): R =
        execute(request).use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw toApiException(resp.code, text)
            try {
                json.decodeFromString(response, text)
            } catch (e: kotlinx.serialization.SerializationException) {
                throw ApiException("bad_response", resp.code, "unparseable response", e)
            } catch (e: IllegalArgumentException) {
                throw ApiException("bad_response", resp.code, "unexpected response shape", e)
            }
        }

    private suspend fun callNoContent(request: Request) =
        execute(request).use { resp ->
            if (!resp.isSuccessful) throw toApiException(resp.code, resp.body.string())
        }

    private fun toApiException(status: Int, text: String): ApiException {
        val parsed = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
        val code = parsed?.error?.code ?: when (status) {
            401 -> ErrorCode.UNAUTHORIZED
            403 -> ErrorCode.FORBIDDEN
            404 -> ErrorCode.NOT_FOUND
            409 -> ErrorCode.CONFLICT
            413 -> ErrorCode.PAYLOAD_TOO_LARGE
            426 -> ErrorCode.UPGRADE_REQUIRED
            429 -> ErrorCode.RATE_LIMITED
            in 500..599 -> ErrorCode.INTERNAL
            else -> ErrorCode.INVALID_REQUEST
        }
        return ApiException(code, status, parsed?.error?.message?.takeIf { it.isNotBlank() } ?: "HTTP $status")
    }

    /** Executes with cancellation support; transport failures become [ApiException] (status 0). */
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ApiException(ErrorCode.NETWORK, 0, "network error", e))
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                if (cont.isActive) cont.resume(response) { _, _, _ -> response.close() } else response.close()
            }
        })
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
