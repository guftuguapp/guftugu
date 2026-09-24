# Android app — module map and internal contracts

Single Gradle module `:app`, package `com.guftugu.app`. Kotlin 2.3.20, Jetpack
Compose (BOM 2026.09.00, Material 3), minSdk 26, compileSdk/targetSdk 36,
JDK 17, AGP 8.13.1, KSP 2.3.12, Gradle 8.14. No Hilt — a hand-written
`AppGraph` provides singletons. Coroutines + Flow everywhere; no RxJava.

Libraries (version catalog `gradle/libs.versions.toml`) — pinned to what builds
with AGP 8.13.1 / compileSdk 36 (newer androidx releases require AGP 9.1 + SDK 37):
compose BOM 2026.06.01 (ui 1.11.4, material3 1.4.0, material-icons-extended 1.7.8), activity-compose 1.13.0,
navigation-compose 2.9.8, lifecycle-viewmodel-compose / runtime-compose / lifecycle-service 2.10.0,
core-ktx 1.18.0, core-splashscreen 1.2.0, biometric 1.1.0, fragment-ktx 1.9.0, room 2.8.5 (KSP 2.3.12),
datastore-preferences 1.2.1, work-runtime-ktx 2.11.2, okhttp 5.4.0, kotlinx-serialization-json 1.11.0,
kotlinx-coroutines-android 1.11.0, coil3 3.5.0 (coil-compose, coil-network-okhttp, coil-video),
media3-exoplayer + media3-ui 1.11.1, stream-webrtc-android 1.3.10 (`io.getstream:stream-webrtc-android`),
zxing core 3.5.4 + journeyapps zxing-android-embedded 4.3.0 (QR scan/generate).

## Package layout

```
com.guftugu.app
├── GuftuguApp.kt                 Application: builds AppGraph, creates notification channels
├── MainActivity.kt               single Activity, hosts NavHost, handles guftugu://join deep link, call intents
├── di/AppGraph.kt                lazy singletons: prefs, db, api, ws, crypto, repos, managers
├── domain/                       plain models used by UI (Conversation, Message, Attachment, User, CallState…)
├── protocol/Dto.kt               @Serializable Kotlin mirrors of server/src/protocol/types.ts (wire types)
├── protocol/Content.kt           @Serializable Content / Attachment / Signal (decrypted payloads)
├── core/
│   ├── crypto/KeystoreKeys.kt    guftugu_device (P-256 sign), guftugu_auth (biometric-gated), guftugu_wrap (AES)
│   ├── crypto/SoftwareEcdh.kt    identity ECDH keypair, stored wrapped by guftugu_wrap
│   ├── crypto/Ecies.kt           wrap/unwrap conversation keys (HKDF + AES-GCM) per PROTOCOL §7
│   ├── crypto/Signatures.kt      sign/verify, DER <-> P1363 conversion, base64url helpers
│   ├── crypto/ContentCipher.kt   Envelope encrypt/decrypt (AES-256-GCM, AAD convId:senderId:clientId)
│   ├── crypto/AttachmentCipher.kt aes-256-gcm-chunked-v1 streaming encrypt/decrypt of files
│   ├── auth/BiometricGate.kt     BiometricPrompt + CryptoObject(Signature); detects invalidated key
│   └── util/                     Base64Url, Ulid (client ids), Time, Result helpers
├── data/
│   ├── prefs/ServerConfigStore.kt DataStore: apiUrl, wsUrl, serverName, userId, deviceId, settings
│   ├── prefs/SecureStore.kt      AES-GCM (guftugu_wrap) encrypted key/value file for session token, ECDH key, conv keys
│   ├── api/GuftuguApi.kt         every REST endpoint of PROTOCOL.md; OkHttp + kotlinx.serialization; ApiException(code)
│   ├── ws/RealtimeClient.kt      WebSocket with bearer header, ping every 4 min, reconnect w/ backoff, events: SharedFlow<ServerEvent>
│   ├── db/GuftuguDb.kt           Room: UserEntity, ConversationEntity, MemberEntity, MessageEntity, ConvKeyEntity, OutboxEntity, MediaCacheEntity, DAOs
│   ├── sync/SyncEngine.kt        reconcile on connect / periodic: GET /conversations → deltas; applies ServerEvents to Room
│   └── repo/                     AuthRepository, UserRepository, ConversationRepository, MessageRepository, MediaRepository, CallRepository
├── e2ee/ConversationKeyManager.kt  ensureKeys/rotate/wrap-for-missing, encrypt/decrypt content & signals, key cache
├── calls/                        PeerConnectionFactoryProvider, CallManager (state machine), AudioRouter
├── service/RealtimeService.kt    foreground service (specialUse) owning RealtimeClient + SyncEngine; notifications
├── service/CallService.kt        foreground service (microphone|camera) during a call
├── service/BootReceiver.kt       restarts RealtimeService after boot if enabled
├── notifications/Notifier.kt     channels: messages, calls (full-screen intent), service
└── ui/
    ├── theme/                    Material 3 dynamic color, typography
    ├── navigation/NavGraph.kt    routes: join, enroll, unlock, chats, chat/{convId}, newChat, newGroup, media/{convId}/{msgId}, call/{callId}, settings, devices, linkDevice
    ├── join/                     JoinServerScreen (scan QR / paste link / manual), EnrollScreen (name, password, biometric opt-in)
    ├── unlock/UnlockScreen.kt    biometric prompt → password fallback → offline unlock
    ├── chats/ChatListScreen.kt   conversations with local previews + unread badges
    ├── chat/                     ChatScreen, MessageBubble(s), Composer (text, attach, camera, voice note), TypingIndicator
    ├── media/                    ImageViewer, VideoPlayer (media3), AudioPlayer, picker/camera launchers, VoiceRecorder
    ├── calls/                    IncomingCallScreen, CallScreen (video views, mute/camera/speaker/hangup)
    ├── contacts/                 NewChatScreen (users), NewGroupScreen
    └── settings/                 SettingsScreen (profile, background connection, lock timeout), DevicesScreen, LinkDeviceScreen (QR)
```

## Cross-cutting interfaces (defined in the skeleton, implemented by feature work)

```kotlin
// data/repo/AuthRepository.kt
interface AuthRepository {
    val state: StateFlow<AuthState>            // NotEnrolled, Locked(deviceId), Unlocked(session), Revoked
    suspend fun enroll(server: ServerInfo, code: String, displayName: String?, password: String?, deviceName: String): Result<Unit>
    suspend fun unlockWithBiometric(activity: FragmentActivity): Result<Unit>
    suspend fun unlockWithPassword(password: String): Result<Unit>
    suspend fun unlockOffline(activity: FragmentActivity): Result<Unit>   // local biometric only, no server
    suspend fun logout()
    fun sessionToken(): String?
}

// e2ee/ConversationKeyManager.kt
interface ConversationKeyManager {
    suspend fun ensureKeys(convId: String)                       // rotate if required, wrap current key for devices lacking it
    suspend fun onKeysEvent(convId: String, keyId: String)       // fetch + unwrap new wrapped keys for this device
    suspend fun encrypt(convId: String, content: Content, clientId: String): Envelope
    suspend fun decrypt(convId: String, envelope: Envelope, senderId: String, clientId: String?): Content?   // null = key not yet available
    suspend fun encryptSignal(convId: String, signal: Signal): Envelope
    suspend fun decryptSignal(convId: String, envelope: Envelope): Signal?
}

// data/ws/RealtimeClient.kt
interface RealtimeClient {
    val state: StateFlow<ConnectionState>      // Disconnected, Connecting, Connected
    val events: SharedFlow<ServerEvent>
    fun connect(); fun disconnect()
    fun send(event: ClientEvent)
}

// data/repo/MessageRepository.kt
interface MessageRepository {
    fun messages(convId: String): Flow<List<Message>>            // decrypted, from Room
    suspend fun sendText(convId: String, text: String, replyTo: String? = null)
    suspend fun sendAttachment(convId: String, local: Uri, type: ContentType, caption: String?)   // encrypt+upload+send via outbox
    suspend fun delete(convId: String, msgId: String)
    suspend fun markRead(convId: String)                         // coalesced
    suspend fun loadOlder(convId: String)
    suspend fun retryOutbox()
}

// data/repo/MediaRepository.kt
interface MediaRepository {
    suspend fun openAttachment(att: Attachment): File            // download+decrypt to cache (idempotent)
    suspend fun openThumbnail(att: Attachment): File?
    suspend fun uploadEncrypted(convId: String, local: Uri, mime: String): Attachment  // returns attachment metadata incl. fileKey
}

// calls/CallManager.kt
interface CallManager {
    val state: StateFlow<CallState>            // Idle, Outgoing(call), Incoming(call, caller), Active(call, muted, cameraOn, speaker), Ended(reason)
    suspend fun startCall(convId: String, video: Boolean)
    suspend fun answer(); suspend fun reject(); suspend fun hangup()
    fun toggleMute(); fun toggleCamera(); fun toggleSpeaker(); fun switchCamera()
    val localVideoTrack: StateFlow<VideoTrack?>; val remoteVideoTrack: StateFlow<VideoTrack?>
}
```

## Room schema (skeleton)

- `users(userId PK, displayName, avatarKey, role, status, lastSeenAt)`
- `conversations(convId PK, type, name, avatarKey, createdBy, createdAt, currentKeyId, keyRotationRequired, lastMsgId, lastMessageAt, localPreview, localPreviewAt, unreadCount, myLastReadMsgId)`
- `members(convId, userId, role, joinedAt, lastReadMsgId, PK(convId,userId))`
- `messages(msgId PK, convId, senderId, senderDeviceId, clientId, kind, sentAt, createdAt, deletedAt, contentType, text, attachmentJson, callJson, systemJson, envelopeJson /*kept until decryptable*/, status /*sent|pending|failed|undecryptable*/)` index (convId, msgId)
- `conv_keys(convId, keyId, keyWrapped /*by guftugu_wrap*/, PK(convId,keyId))`
- `outbox(clientId PK, convId, createdAt, contentJson, localUri, attempts, lastError)`
- `media_cache(key PK, localPath, sizeBytes, lastUsedAt)`

## Conventions

- ViewModels take repositories from `AppGraph` via a small `viewModelFactory`.
- All network calls run on `Dispatchers.IO`; UI observes Room `Flow`s.
- Never log tokens, keys, nonces, passwords or decrypted content.
- Strings in `res/values/strings.xml`; the app is English-first, translatable.
- Every screen has a `@Preview` with fake data where practical.
