package com.guftugu.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.guftugu.app.protocol.ClientConfig
import com.guftugu.app.protocol.IceServer
import com.guftugu.app.protocol.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer

/** Non-secret, persisted app configuration (DataStore preferences). */
data class ServerConfig(
    val apiUrl: String? = null,
    val wsUrl: String? = null,
    val serverName: String? = null,
    val userId: String? = null,
    val deviceId: String? = null,
    val displayName: String? = null,
    val maxUploadBytes: Long = 50L * 1024 * 1024,
    val callsEnabled: Boolean = true,
    val mediaEnabled: Boolean = true,
    val iceServers: List<IceServer> = emptyList(),
    /** Settings → "Background connection" (RealtimeService). */
    val backgroundConnectionEnabled: Boolean = true,
    /** Settings → lock after N minutes in background (0 = immediately). */
    val lockTimeoutMinutes: Int = 5,
    /** Last successful full sync, epoch ms. */
    val lastSyncAt: Long = 0L,
    /** Whether this account has a passcode (created about a week after joining). */
    val hasPassword: Boolean = false,
    /** First time the person opened a chat or sent a message (starts the one-week passcode clock). */
    val firstActivityAt: Long = 0L,
    /** "Later" on the create-passcode prompt: don't ask again before this time. */
    val passcodeSnoozedUntil: Long = 0L,
    /** Last time the passcode was created or entered (the 30-day re-entry clock). */
    val lastPasscodeAt: Long = 0L,
    /** "Not now" on the fingerprint card: hide it until this time. */
    val fingerprintNudgeHiddenUntil: Long = 0L,
    /** The "hide the connected notice" card was dismissed, or both of its steps were opened. */
    val quietSetupDone: Boolean = false,
    /** The phone's keep-running settings page was opened from Guftugu at least once. */
    val keepRunningVisited: Boolean = false,
) {
    val isEnrolled: Boolean get() = apiUrl != null && userId != null && deviceId != null
}

private val Context.serverConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "server_config")

class ServerConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.serverConfigDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val apiUrl = stringPreferencesKey("apiUrl")
        val wsUrl = stringPreferencesKey("wsUrl")
        val serverName = stringPreferencesKey("serverName")
        val userId = stringPreferencesKey("userId")
        val deviceId = stringPreferencesKey("deviceId")
        val displayName = stringPreferencesKey("displayName")
        val maxUploadBytes = longPreferencesKey("maxUploadBytes")
        val callsEnabled = booleanPreferencesKey("callsEnabled")
        val mediaEnabled = booleanPreferencesKey("mediaEnabled")
        val iceServersJson = stringPreferencesKey("iceServersJson")
        val backgroundConnectionEnabled = booleanPreferencesKey("backgroundConnectionEnabled")
        val lockTimeoutMinutes = intPreferencesKey("lockTimeoutMinutes")
        val lastSyncAt = longPreferencesKey("lastSyncAt")
        val hasPassword = booleanPreferencesKey("hasPassword")
        val firstActivityAt = longPreferencesKey("firstActivityAt")
        val passcodeSnoozedUntil = longPreferencesKey("passcodeSnoozedUntil")
        val lastPasscodeAt = longPreferencesKey("lastPasscodeAt")
        val fingerprintNudgeHiddenUntil = longPreferencesKey("fingerprintNudgeHiddenUntil")
        val quietSetupDone = booleanPreferencesKey("quietSetupDone")
        val keepRunningVisited = booleanPreferencesKey("keepRunningVisited")
    }

    private val iceListSerializer = ListSerializer(IceServer.serializer())

    val config: Flow<ServerConfig> = dataStore.data.map { it.toConfig() }

    /**
     * Latest known value for synchronous readers (API/WS providers). Starts with defaults until
     * DataStore has loaded; call [awaitLoaded] once at startup if you need the real value.
     */
    val current: StateFlow<ServerConfig> = config.stateIn(scope, SharingStarted.Eagerly, ServerConfig())

    suspend fun awaitLoaded(): ServerConfig = config.first()

    suspend fun snapshot(): ServerConfig = config.first()

    suspend fun setServer(apiUrl: String, wsUrl: String, serverName: String) = dataStore.edit {
        it[Keys.apiUrl] = apiUrl
        it[Keys.wsUrl] = wsUrl
        it[Keys.serverName] = serverName
    }

    suspend fun setIdentity(userId: String, deviceId: String, displayName: String) = dataStore.edit {
        it[Keys.userId] = userId
        it[Keys.deviceId] = deviceId
        it[Keys.displayName] = displayName
    }

    suspend fun setDisplayName(displayName: String) = dataStore.edit { it[Keys.displayName] = displayName }

    suspend fun applyClientConfig(cfg: ClientConfig) = dataStore.edit {
        it[Keys.serverName] = cfg.serverName
        it[Keys.maxUploadBytes] = cfg.maxUploadBytes
        it[Keys.callsEnabled] = cfg.features.calls
        it[Keys.mediaEnabled] = cfg.features.media
        it[Keys.iceServersJson] = ProtocolJson.encodeToString(iceListSerializer, cfg.iceServers)
    }

    suspend fun setBackgroundConnectionEnabled(enabled: Boolean) = dataStore.edit { it[Keys.backgroundConnectionEnabled] = enabled }

    suspend fun setLockTimeoutMinutes(minutes: Int) = dataStore.edit { it[Keys.lockTimeoutMinutes] = minutes }

    suspend fun setLastSyncAt(at: Long) = dataStore.edit { it[Keys.lastSyncAt] = at }

    suspend fun setHasPassword(has: Boolean) = dataStore.edit { it[Keys.hasPassword] = has }

    /** Records the first chat opened / message sent; later calls are no-ops. */
    suspend fun markFirstActivity(at: Long) = dataStore.edit { if ((it[Keys.firstActivityAt] ?: 0L) == 0L) it[Keys.firstActivityAt] = at }

    suspend fun setPasscodeSnoozedUntil(at: Long) = dataStore.edit { it[Keys.passcodeSnoozedUntil] = at }

    suspend fun setLastPasscodeAt(at: Long) = dataStore.edit { it[Keys.lastPasscodeAt] = at }

    suspend fun setFingerprintNudgeHiddenUntil(at: Long) = dataStore.edit { it[Keys.fingerprintNudgeHiddenUntil] = at }

    suspend fun setQuietSetupDone(done: Boolean) = dataStore.edit { it[Keys.quietSetupDone] = done }

    suspend fun setKeepRunningVisited() = dataStore.edit { it[Keys.keepRunningVisited] = true }

    /** Forget everything (logout after revocation / re-enrol). */
    suspend fun clear() = dataStore.edit { it.clear() }

    private fun Preferences.toConfig(): ServerConfig = ServerConfig(
        apiUrl = this[Keys.apiUrl],
        wsUrl = this[Keys.wsUrl],
        serverName = this[Keys.serverName],
        userId = this[Keys.userId],
        deviceId = this[Keys.deviceId],
        displayName = this[Keys.displayName],
        maxUploadBytes = this[Keys.maxUploadBytes] ?: ServerConfig().maxUploadBytes,
        callsEnabled = this[Keys.callsEnabled] ?: true,
        mediaEnabled = this[Keys.mediaEnabled] ?: true,
        iceServers = this[Keys.iceServersJson]?.let { s ->
            runCatching { ProtocolJson.decodeFromString(iceListSerializer, s) }.getOrDefault(emptyList())
        } ?: emptyList(),
        backgroundConnectionEnabled = this[Keys.backgroundConnectionEnabled] ?: true,
        lockTimeoutMinutes = this[Keys.lockTimeoutMinutes] ?: 5,
        lastSyncAt = this[Keys.lastSyncAt] ?: 0L,
        hasPassword = this[Keys.hasPassword] ?: false,
        firstActivityAt = this[Keys.firstActivityAt] ?: 0L,
        passcodeSnoozedUntil = this[Keys.passcodeSnoozedUntil] ?: 0L,
        lastPasscodeAt = this[Keys.lastPasscodeAt] ?: 0L,
        fingerprintNudgeHiddenUntil = this[Keys.fingerprintNudgeHiddenUntil] ?: 0L,
        quietSetupDone = this[Keys.quietSetupDone] ?: false,
        keepRunningVisited = this[Keys.keepRunningVisited] ?: false,
    )
}
