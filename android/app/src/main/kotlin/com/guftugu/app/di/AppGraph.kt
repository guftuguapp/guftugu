package com.guftugu.app.di

import android.app.Application
import com.guftugu.app.calls.CallManager
import com.guftugu.app.calls.CallManagerImpl
import com.guftugu.app.core.crypto.KeystoreKeys
import com.guftugu.app.core.crypto.SoftwareEcdh
import com.guftugu.app.data.api.GuftuguApi
import com.guftugu.app.data.db.GuftuguDb
import com.guftugu.app.data.prefs.SecureStore
import com.guftugu.app.data.prefs.ServerConfigStore
import com.guftugu.app.data.repo.AuthRepository
import com.guftugu.app.data.repo.AuthRepositoryImpl
import com.guftugu.app.data.repo.CallRepository
import com.guftugu.app.data.repo.CallRepositoryImpl
import com.guftugu.app.data.repo.ConversationRepository
import com.guftugu.app.data.repo.ConversationRepositoryImpl
import com.guftugu.app.data.repo.MediaRepository
import com.guftugu.app.data.repo.MediaRepositoryImpl
import com.guftugu.app.data.repo.MessageRepository
import com.guftugu.app.data.repo.MessageRepositoryImpl
import com.guftugu.app.data.repo.UserRepository
import com.guftugu.app.data.repo.UserRepositoryImpl
import com.guftugu.app.data.sync.PreviewLabels
import com.guftugu.app.data.sync.SyncEngine
import com.guftugu.app.data.sync.SyncEngineImpl
import com.guftugu.app.data.ws.OkHttpRealtimeClient
import com.guftugu.app.data.ws.RealtimeClient
import com.guftugu.app.e2ee.ConversationKeyManager
import com.guftugu.app.e2ee.ConversationKeyManagerImpl
import com.guftugu.app.notifications.Notifier
import com.guftugu.app.protocol.ProtocolJson
import com.guftugu.app.service.BackgroundConnection
import com.guftugu.app.service.NoPush
import com.guftugu.app.service.PushProvider
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Hand-written singletons (no Hilt). Everything is lazy so cold start does only what the first
 * screen needs: the auth repository (one bounded DataStore read) and the config store. Room, the
 * key manager, the sync engine and the call manager are built on first use — after unlock, or when
 * the realtime engine starts. The interfaces are the contract (docs/ANDROID_MODULES.md).
 */
class AppGraph(val app: Application) {

    /** App-wide background scope (never cancelled). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json: Json = ProtocolJson

    /**
     * Single OkHttp client (connection pool shared by REST, WebSocket, media). This is the one
     * place to add a `CertificatePinner` (SECURITY.md "Transport").
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val serverConfig: ServerConfigStore by lazy { ServerConfigStore(app) }

    val secureStore: SecureStore by lazy { SecureStore(File(app.filesDir, SecureStore.FILE_NAME)) }

    val ecdh: SoftwareEcdh by lazy { SoftwareEcdh(secureStore) }

    val db: GuftuguDb by lazy { GuftuguDb.build(app) }

    val notifier: Notifier by lazy { Notifier(app) }

    val api: GuftuguApi by lazy {
        GuftuguApi(
            client = httpClient,
            json = json,
            apiUrl = { serverConfig.current.value.apiUrl },
            token = { authRepository.sessionToken() },
        )
    }

    val realtime: RealtimeClient by lazy {
        OkHttpRealtimeClient(
            client = httpClient,
            wsUrl = { serverConfig.current.value.wsUrl },
            token = { authRepository.sessionToken() },
            json = json,
        )
    }

    val pushProvider: PushProvider = NoPush

    /** Preview labels ("You:", "Photo", …) read once from strings.xml; shared by the sync/message layer. */
    val previewLabels: PreviewLabels by lazy { PreviewLabels.from(app) }

    // ---------- feature bindings ----------

    /**
     * Enrolment + unlock. `api`/`realtime` read [AuthRepository.sessionToken] lazily so there is no
     * init cycle. `db`/`mediaCacheDir` are lambdas so Room is not built at cold start.
     */
    val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(
            api = api,
            keys = KeystoreKeys,
            ecdh = ecdh,
            secureStore = secureStore,
            serverConfig = serverConfig,
            appContext = app,
            appScope = appScope,
            onUnlocked = { onUnlocked() },
            onLockedOut = { onLockedOut() },
            db = { db },
            mediaCacheDir = { mediaCacheDir },
        )
    }

    val userRepository: UserRepository by lazy { UserRepositoryImpl(api = api, db = db, serverConfig = serverConfig) }

    val conversationRepository: ConversationRepository by lazy {
        ConversationRepositoryImpl(
            api = api,
            db = db,
            serverConfig = serverConfig,
            keyManager = keyManager,
            syncEngine = syncEngine,
            labels = previewLabels,
        )
    }

    val messageRepository: MessageRepository by lazy {
        MessageRepositoryImpl(
            context = app,
            api = api,
            db = db,
            keyManager = keyManager,
            mediaRepository = mediaRepository,
            serverConfig = serverConfig,
            realtime = realtime,
            appScope = appScope,
            json = json,
            notifier = notifier,
            labels = previewLabels,
        )
    }

    val mediaRepository: MediaRepository by lazy {
        MediaRepositoryImpl(api = api, db = db, appContext = app, cacheDir = mediaCacheDir, serverConfig = serverConfig, appScope = appScope)
    }

    val callRepository: CallRepository by lazy { CallRepositoryImpl(api = api, realtime = realtime) }

    val keyManager: ConversationKeyManager by lazy {
        ConversationKeyManagerImpl(api = api, db = db, keys = KeystoreKeys, ecdh = ecdh, serverConfig = serverConfig, json = json)
    }

    /**
     * Subscribes to `realtime.events` in its constructor, so it must be built once the socket is
     * in use — [BackgroundConnection.startEngine] and the NavGraph's call observer both read it.
     */
    val callManager: CallManager by lazy {
        CallManagerImpl(
            context = app,
            repo = callRepository,
            realtime = realtime,
            keyManager = keyManager,
            serverConfig = serverConfig,
            db = db,
            notifier = notifier,
            appScope = appScope,
        )
    }

    val syncEngine: SyncEngine by lazy {
        SyncEngineImpl(
            api = api,
            realtime = realtime,
            db = db,
            keyManager = keyManager,
            serverConfig = serverConfig,
            notifier = notifier,
            appScope = appScope,
            json = json,
            labels = previewLabels,
            messages = { messageRepository }, // lazy: breaks the engine <-> message-repo cycle
            hasSession = { authRepository.sessionToken() != null }, // a sync while locked is skipped, not "expired"
        ).also { engine ->
            // 401 / rejected handshake → the session is gone: drop the token and gate the UI again.
            appScope.launch { engine.authExpired.collect { runCatching { authRepository.logout() } } }
        }
    }

    /** Decrypted media cache directory (app-private; cleared on logout). */
    val mediaCacheDir: File by lazy { File(app.cacheDir, "media").apply { mkdirs() } }

    /** Camera capture directory shared through FileProvider (`captures` in res/xml/file_paths.xml). */
    val captureDir: File by lazy { File(app.cacheDir, "captures").apply { mkdirs() } }

    // ---------- auth lifecycle hooks ----------

    /** True once the session-scoped lazies (engine, key cache, media cache) may have been built. */
    private val sessionStarted = AtomicBoolean(false)

    /**
     * After every successful enrol / unlock (may run off the main thread). [BackgroundConnection]'s
     * state machine does the same once installed; these direct calls make the first sync start
     * immediately instead of on the next state-machine tick.
     */
    private fun onUnlocked() {
        sessionStarted.set(true)
        appScope.launch {
            runCatching { BackgroundConnection.startEngine(this@AppGraph) }
            if (serverConfig.current.value.backgroundConnectionEnabled) runCatching { BackgroundConnection.ensureRunning(app) }
            // Keep the decrypted media cache bounded (LRU, off the main thread).
            runCatching { (mediaRepository as? MediaRepositoryImpl)?.trimInBackground() }
        }
    }

    /** Logout / revocation / "Start over" — never a lock-timeout (the token stays so the service keeps receiving). */
    private fun onLockedOut() {
        runCatching { BackgroundConnection.stop(app) }
        if (sessionStarted.compareAndSet(true, false)) {
            // Only touch the lazies that were actually built: a lock-out while nothing ran must not build Room.
            runCatching { BackgroundConnection.stopEngine(this) }
            runCatching { syncEngine.stop() }
            runCatching { realtime.disconnect() }
            runCatching { keyManager.clearCache() }
            appScope.launch { runCatching { mediaRepository.clearCache() } }
        }
    }
}
