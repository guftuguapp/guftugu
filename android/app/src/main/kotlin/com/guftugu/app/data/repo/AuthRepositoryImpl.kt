package com.guftugu.app.data.repo

import android.content.Context
import android.os.Build
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.guftugu.app.BuildConfig
import com.guftugu.app.R
import com.guftugu.app.core.auth.AuthCrypto
import com.guftugu.app.core.auth.AuthError
import com.guftugu.app.core.auth.BiometricGate
import com.guftugu.app.core.auth.InviteCodes
import com.guftugu.app.core.crypto.KeystoreKeys
import com.guftugu.app.core.crypto.Signatures
import com.guftugu.app.core.crypto.SoftwareEcdh
import com.guftugu.app.core.util.Time
import com.guftugu.app.data.api.ApiException
import com.guftugu.app.data.api.GuftuguApi
import com.guftugu.app.data.db.GuftuguDb
import com.guftugu.app.data.prefs.SecureStore
import com.guftugu.app.data.prefs.ServerConfig
import com.guftugu.app.data.prefs.ServerConfigStore
import com.guftugu.app.protocol.AuthMethod
import com.guftugu.app.protocol.AuthResponse
import com.guftugu.app.protocol.AuthVerifyRequest
import com.guftugu.app.protocol.DeviceInfo
import com.guftugu.app.protocol.EnrollRequest
import com.guftugu.app.protocol.PROTOCOL_VERSION
import com.guftugu.app.protocol.RegisterAuthKeyRequest
import com.guftugu.app.core.util.Base64Url
import com.guftugu.app.protocol.Session
import com.guftugu.app.protocol.WellKnown
import java.io.File
import java.security.Signature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Extras the Join / Enroll / Unlock screens need beyond the [AuthRepository] contract.
 * `AppGraph.authRepository` is typed as the interface; screens do `as? AuthExtras`.
 */
interface AuthExtras {
    /**
     * [AuthRepository.enroll] with an explicit fingerprint choice. With [biometricOptIn] and an
     * [activity], the fingerprint is confirmed once with a BiometricPrompt *before* the account is
     * created, so nobody ends up with a key their phone cannot use. [password] is an optional backup.
     */
    suspend fun enroll(
        server: ServerInfo,
        code: String,
        displayName: String?,
        password: String?,
        deviceName: String,
        biometricOptIn: Boolean,
        activity: FragmentActivity? = null,
    ): Result<Unit>

    /** Turns fingerprint unlock on for this (already enrolled, unlocked) phone: PUT /me/device/auth-key. */
    suspend fun enableBiometric(activity: FragmentActivity): Result<Unit>

    /** Sets a first backup password ([current] = null) or changes it. */
    suspend fun setBackupPassword(current: String?, new: String): Result<Unit>

    /**
     * Signs in with the phone's device key alone — only for accounts with neither a passcode nor a
     * fingerprint key (the WhatsApp-style first weeks, and phones without a screen lock).
     */
    suspend fun unlockWithDevice(): Result<Unit>

    /** Whether this account has a backup password (so the unlock screen may offer it). */
    fun hasPassword(): Boolean

    /** Wipes everything (keys, session, config, Room, media cache) and returns to [AuthState.NotEnrolled]. */
    suspend fun resetEnrollment()

    /** Hint that the person is interacting (used by the lock policy; cheap, may be a no-op). */
    fun noteUserActivity()

    /** True when `guftugu_auth` exists — i.e. fingerprint unlock is possible on this phone. */
    fun hasBiometricKey(): Boolean

    /** True when a session token is held in memory or in the encrypted store (offline unlock can reuse it). */
    fun hasStoredSession(): Boolean

    /** The server's display name, once enrolled. */
    fun serverName(): String?
}

/**
 * Device enrolment and user login exactly per PROTOCOL §3–§4 and SECURITY.md.
 *
 * - Cold start is **never** `Unlocked`: the constructor reads the persisted config and starts in
 *   `Locked(deviceId)` (enrolled) or `NotEnrolled`. Re-authenticating on every launch *is* the
 *   unlock screen.
 * - The session token lives in memory ([sessionToken]) and, encrypted by `guftugu_wrap`, in
 *   [SecureStore] so the offline unlock can reuse it.
 * - Lock policy: when the app returns from the background after `lockTimeoutMinutes`
 *   (0 = every time, -1 = never) the state flips back to `Locked` but the token stays in memory —
 *   the background connection keeps receiving; only the UI is gated.
 * - `device_revoked` / `user_disabled` → [AuthState.Revoked]; the UI offers "Start over"
 *   ([resetEnrollment]).
 *
 * Constructor arguments are all `AppGraph` fields (plus two lambdas the wiring agent supplies).
 * Never logs tokens, nonces, passwords or keys.
 */
class AuthRepositoryImpl(
    private val api: GuftuguApi,
    private val keys: KeystoreKeys = KeystoreKeys,
    private val ecdh: SoftwareEcdh,
    private val secureStore: SecureStore,
    private val serverConfig: ServerConfigStore,
    private val appContext: Context,
    private val appScope: CoroutineScope,
    /** Called after every successful unlock/enrol: start sync + `BackgroundConnection.ensureRunning`. */
    private val onUnlocked: () -> Unit,
    /** Called on logout / revocation / reset: stop the service. Not called for a timeout lock. */
    private val onLockedOut: () -> Unit,
    /** Lazy so the Room database is not built before the first screen needs it. */
    private val db: () -> GuftuguDb? = { null },
    private val mediaCacheDir: () -> File? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) : AuthRepository, AuthExtras {

    /** One small DataStore read before the first frame (DESIGN.md "Startup"), bounded by a timeout. */
    private val startupConfig: ServerConfig =
        runBlocking { withTimeoutOrNull(INITIAL_LOAD_TIMEOUT_MS) { serverConfig.awaitLoaded() } } ?: serverConfig.current.value

    /**
     * Passcode phones (no fingerprint key) keep their session for its whole 30-day lifetime: the
     * owner's rule for phones without a sensor. Fingerprint phones unlock on every launch.
     */
    private val startupSession: Session? = passcodeSessionAtStartup(startupConfig)

    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Serialises enrol / unlock / logout so two prompts can never race. */
    private val authMutex = Mutex()

    @Volatile private var token: String? = startupSession?.token
    @Volatile private var session: Session? = startupSession
    @Volatile private var backgroundedAt: Long? = null
    @Volatile private var lastActivityAt: Long = clock()

    init {
        // Lock policy: observe the whole process (all activities) going to / returning from background.
        appScope.launch(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) { backgroundedAt = clock() }
                override fun onStart(owner: LifecycleOwner) { onForeground() }
            })
        }
        // A passcode phone still inside its 30-day session starts unlocked; start sync once the
        // graph is fully built (never synchronously from this constructor).
        if (startupSession != null) appScope.launch { runCatching { onUnlocked() } }
        // Belt and braces: if DataStore had not loaded within the constructor's budget, fix the state up.
        if (_state.value is AuthState.NotEnrolled) {
            appScope.launch {
                val cfg = serverConfig.awaitLoaded()
                if (cfg.isEnrolled && _state.value is AuthState.NotEnrolled) _state.value = AuthState.Locked(cfg.deviceId!!)
            }
        }
    }

    // ---------- state ----------

    private fun initialState(): AuthState {
        val cfg = startupConfig
        startupSession?.let { return AuthState.Unlocked(it) }
        return if (cfg.isEnrolled) AuthState.Locked(cfg.deviceId!!) else AuthState.NotEnrolled
    }

    /** The stored session of an enrolled phone without a fingerprint key, if still valid for a minute or more. */
    private fun passcodeSessionAtStartup(cfg: ServerConfig): Session? {
        if (!cfg.isEnrolled) return null
        if (runCatching { keys.hasBiometricKey() }.getOrDefault(true)) return null
        val stored = runCatching { loadStoredSession() }.getOrNull() ?: return null
        return stored.takeIf { it.expiresAt > clock() + 60_000L }
    }

    override fun sessionToken(): String? = token

    override fun noteUserActivity() { lastActivityAt = clock() }

    override fun hasBiometricKey(): Boolean = keys.hasBiometricKey()

    override fun hasStoredSession(): Boolean = token != null || secureStore.contains(SecureStore.KEY_SESSION_TOKEN)

    override fun serverName(): String? = serverConfig.current.value.serverName

    override fun hasPassword(): Boolean = serverConfig.current.value.hasPassword

    private fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (_state.value !is AuthState.Unlocked) return
        // Passcode phones stay signed in for the whole session (30 days); only fingerprint phones re-lock.
        if (!keys.hasBiometricKey()) return
        val timeoutMinutes = serverConfig.current.value.lockTimeoutMinutes
        if (timeoutMinutes < 0) return // never lock
        val elapsed = clock() - since
        if (timeoutMinutes == 0 || elapsed >= timeoutMinutes * 60_000L) lockForTimeout()
    }

    /** Gate the UI again; the token stays in memory so the background connection carries on. */
    private fun lockForTimeout() {
        val deviceId = serverConfig.current.value.deviceId ?: return
        _state.value = AuthState.Locked(deviceId)
    }

    // ---------- §3 enrolment ----------

    override suspend fun enroll(server: ServerInfo, code: String, displayName: String?, password: String?, deviceName: String): Result<Unit> =
        enroll(server, code, displayName, password, deviceName, biometricOptIn = true, activity = null)

    override suspend fun enroll(
        server: ServerInfo,
        code: String,
        displayName: String?,
        password: String?,
        deviceName: String,
        biometricOptIn: Boolean,
        activity: FragmentActivity?,
    ): Result<Unit> = authMutex.withLock {
        runAuth {
            val wk = discover(server.apiUrl)
            // Enrolling over a revoked / stale install: start from a clean slate so nothing from the
            // previous identity (keys, session, cached messages) survives into the new one.
            if (_state.value !is AuthState.NotEnrolled) wipeLocalData()
            val apiUrl = wk.apiUrl.trim().ifBlank { server.apiUrl }.trimEnd('/')
            val wsUrl = wk.wsUrl.trim().ifBlank { server.wsUrl }
            val name = wk.name.trim().ifBlank { server.name }

            // Keys: device (always), wrap (always), ECDH identity (always), auth (only if asked and possible).
            val wantAuthKey = biometricOptIn && BiometricGate.canAuthenticate(appContext)
            val material = withContext(Dispatchers.IO) {
                keys.ensureWrapKey()
                val devicePub = Signatures.encodeSpkiBase64Url(keys.ensureDeviceKey())
                val authPub = if (wantAuthKey) {
                    // Throws when the phone cannot create a biometric-gated key → password-only account.
                    runCatching { Signatures.encodeSpkiBase64Url(keys.ensureAuthKey()) }.getOrNull()
                } else {
                    keys.deleteAuthKey() // a leftover from an earlier attempt must not lie about hasBiometricKey
                    null
                }
                val encPub = ecdh.ensureIdentityKey()
                KeyMaterial(devicePub, authPub, encPub)
            }

            // Fingerprint first: prove the new key works (and that the person is here) before the
            // invite is spent. A cancelled prompt aborts enrolment; the invite stays usable.
            if (material.authPublicKey != null && activity != null) {
                val signature = promptForSignature(
                    activity,
                    BiometricGate.PromptText(
                        title = appContext.getString(R.string.biometric_enroll_title),
                        subtitle = appContext.getString(R.string.biometric_enroll_subtitle),
                        negative = appContext.getString(R.string.cancel),
                    ),
                )
                val nonce = AuthCrypto.randomNonce()
                val ok = withContext(Dispatchers.IO) {
                    val sig = AuthCrypto.signLocalProof(signature, "enrol", nonce)
                    val pub = keys.publicKey(KeystoreKeys.AUTH) ?: return@withContext false
                    AuthCrypto.verifyLocalProof(pub, "enrol", nonce, sig)
                }
                if (!ok) throw AuthError.BiometricFailed(-1, "fingerprint key check failed")
            }
            if (wantAuthKey && material.authPublicKey == null) {
                // The phone said a fingerprint is usable but refused to create a fingerprint-bound key
                // (an OEM quirk): let the screen fall back to a passcode instead of dead-ending.
                throw AuthError.BiometricFailed(-2, "fingerprint key could not be created")
            }

            val request = EnrollRequest(
                inviteCode = InviteCodes.normalize(code),
                displayName = displayName?.trim()?.ifEmpty { null },
                password = password?.ifEmpty { null },
                devicePublicKey = material.devicePublicKey,
                authPublicKey = material.authPublicKey,
                encryptionPublicKey = material.encryptionPublicKey,
                device = DeviceInfo(
                    name = deviceName.trim().ifEmpty { defaultDeviceName() },
                    model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    os = "Android ${Build.VERSION.RELEASE}",
                    appVersion = BuildConfig.VERSION_NAME,
                ),
            )
            val resp = api.enroll(request, serverUrl = apiUrl)

            serverConfig.setServer(apiUrl, wsUrl, name)
            serverConfig.setIdentity(resp.user.userId, resp.device.deviceId, resp.user.displayName)
            serverConfig.applyClientConfig(resp.config)
            serverConfig.setHasPassword(resp.user.hasPassword ?: !password.isNullOrEmpty())
            awaitConfig { it.deviceId == resp.device.deviceId && it.apiUrl == apiUrl }

            storeSession(resp.session)
            unlocked(resp.session)
        }
    }

    /** `GET /.well-known/guftugu` + protocol check; non-Guftugu URLs become [AuthError.ServerIncompatible]. */
    private suspend fun discover(apiUrl: String): WellKnown {
        val wk = try {
            api.wellKnown(apiUrl)
        } catch (e: ApiException) {
            when (e.code) {
                "bad_response", "invalid_url", "not_found" -> throw AuthError.ServerIncompatible(null)
                else -> throw e
            }
        }
        if (wk.protocolVersion != PROTOCOL_VERSION) throw AuthError.ServerIncompatible(wk.protocolVersion)
        Time.observeServerTime(wk.serverTime)
        return wk
    }

    // ---------- §4 login ----------

    override suspend fun unlockWithBiometric(activity: FragmentActivity): Result<Unit> = authMutex.withLock {
        runAuth {
            val deviceId = enrolledDeviceId()
            if (!keys.hasBiometricKey()) throw AuthError.NoBiometrics
            val challenge = api.authChallenge(deviceId)
            val negative = if (hasPassword()) null else appContext.getString(R.string.unlock_cancel_prompt)
            val signature = promptForSignature(activity, BiometricGate.PromptText(negative = negative))
            val sig = withContext(Dispatchers.IO) { AuthCrypto.signChallenge(signature, deviceId, challenge.nonce) }
            val resp = api.authVerify(AuthVerifyRequest(deviceId, challenge.nonce, AuthMethod.BIOMETRIC, sig))
            applyAuthResponse(resp)
        }
    }

    override suspend fun unlockWithPassword(password: String): Result<Unit> = authMutex.withLock {
        runAuth {
            val deviceId = enrolledDeviceId()
            if (password.isEmpty()) throw AuthError.BadCredentials
            val challenge = api.authChallenge(deviceId)
            val sig = withContext(Dispatchers.IO) {
                AuthCrypto.signChallenge(keys.signatureFor(KeystoreKeys.DEVICE), deviceId, challenge.nonce)
            }
            val resp = api.authVerify(AuthVerifyRequest(deviceId, challenge.nonce, AuthMethod.PASSWORD, sig, password))
            applyAuthResponse(resp)
            serverConfig.setLastPasscodeAt(clock())
        }
    }

    override suspend fun unlockOffline(activity: FragmentActivity): Result<Unit> = authMutex.withLock {
        runAuth {
            val deviceId = enrolledDeviceId()
            if (!keys.hasBiometricKey()) throw AuthError.NoBiometrics
            val signature = promptForSignature(
                activity,
                BiometricGate.PromptText(subtitle = appContext.getString(R.string.biometric_offline_subtitle)),
            )
            // Prove the person locally: sign a fresh random nonce and verify with our own public key.
            val nonce = AuthCrypto.randomNonce()
            val verified = withContext(Dispatchers.IO) {
                val sigB64 = AuthCrypto.signLocalProof(signature, deviceId, nonce)
                val publicKey = keys.publicKey(KeystoreKeys.AUTH) ?: return@withContext false
                AuthCrypto.verifyLocalProof(publicKey, deviceId, nonce, sigB64)
            }
            if (!verified) throw AuthError.BiometricFailed(-1, "local proof did not verify")

            val existing = session
                ?: withContext(Dispatchers.IO) { loadStoredSession() }
                ?: throw AuthError.NeedsNetwork
            if (existing.expiresAt in 1 until clock()) {
                withContext(Dispatchers.IO) { clearStoredSession() }
                throw AuthError.NeedsNetwork
            }
            unlocked(existing)
        }
    }

    override suspend fun enableBiometric(activity: FragmentActivity): Result<Unit> = authMutex.withLock {
        runAuth {
            if (token == null) throw AuthError.NeedsNetwork
            val deviceId = enrolledDeviceId()
            if (BiometricGate.availability(appContext) != BiometricGate.Availability.AVAILABLE) throw AuthError.NoBiometrics
            val authPub = withContext(Dispatchers.IO) {
                keys.deleteAuthKey()
                Signatures.encodeSpkiBase64Url(keys.ensureAuthKey())
            }
            try {
                val challenge = api.authChallenge(deviceId)
                val message = AuthCrypto.authKeyMessage(deviceId, challenge.nonce, authPub)
                val authSignature = promptForSignature(
                    activity,
                    BiometricGate.PromptText(
                        title = appContext.getString(R.string.biometric_enable_title),
                        subtitle = appContext.getString(R.string.biometric_enroll_subtitle),
                        negative = appContext.getString(R.string.cancel),
                    ),
                )
                val (authSig, deviceSig) = withContext(Dispatchers.IO) {
                    Base64Url.encode(Signatures.signP256(authSignature, message)) to
                        Base64Url.encode(Signatures.signP256(keys.signatureFor(KeystoreKeys.DEVICE), message))
                }
                api.registerAuthKey(RegisterAuthKeyRequest(challenge.nonce, authPub, deviceSig, authSig))
            } catch (e: Throwable) {
                // Never leave a local key the server doesn't know about: hasBiometricKey() must not lie.
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { keys.deleteAuthKey() }
                throw e
            }
        }
    }

    override suspend fun setBackupPassword(current: String?, new: String): Result<Unit> = authMutex.withLock {
        runAuth {
            if (token == null) throw AuthError.NeedsNetwork
            api.changePassword(current, new)
            serverConfig.setHasPassword(true)
            serverConfig.setLastPasscodeAt(clock())
        }
    }

    override suspend fun unlockWithDevice(): Result<Unit> = authMutex.withLock {
        runAuth {
            val deviceId = enrolledDeviceId()
            val challenge = api.authChallenge(deviceId)
            val sig = withContext(Dispatchers.IO) {
                AuthCrypto.signChallenge(keys.signatureFor(KeystoreKeys.DEVICE), deviceId, challenge.nonce)
            }
            val resp = api.authVerify(AuthVerifyRequest(deviceId, challenge.nonce, AuthMethod.DEVICE, sig))
            applyAuthResponse(resp)
        }
    }

    override suspend fun logout() {
        authMutex.withLock {
            if (token != null) runCatching { withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { api.logout() } }
            token = null
            session = null
            withContext(Dispatchers.IO) { clearStoredSession() }
            val deviceId = serverConfig.current.value.deviceId
            _state.value = if (deviceId != null) AuthState.Locked(deviceId) else AuthState.NotEnrolled
            runCatching { onLockedOut() }
        }
    }

    override suspend fun resetEnrollment() {
        authMutex.withLock { wipeLocalData() }
    }

    /** Must hold [authMutex]. Logs out (best effort), stops the service, wipes every local trace, → NotEnrolled. */
    private suspend fun wipeLocalData() {
        if (token != null) runCatching { withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { api.logout() } }
        token = null
        session = null
        backgroundedAt = null
        runCatching { onLockedOut() }
        withContext(Dispatchers.IO) {
            runCatching { db()?.clearAllTables() }
            runCatching { mediaCacheDir()?.let { dir -> dir.deleteRecursively(); dir.mkdirs() } }
            runCatching { secureStore.clear() }       // also drops the ECDH identity key
            runCatching { keys.deleteAll() }          // guftugu_device, guftugu_auth, guftugu_wrap
        }
        runCatching { serverConfig.clear() }
        awaitConfig { !it.isEnrolled }
        _state.value = AuthState.NotEnrolled
    }

    // ---------- helpers ----------

    private class KeyMaterial(val devicePublicKey: String, val authPublicKey: String?, val encryptionPublicKey: String)

    private fun enrolledDeviceId(): String =
        serverConfig.current.value.deviceId ?: (state.value as? AuthState.Locked)?.deviceId ?: throw AuthError.NotEnrolled

    /** BiometricPrompt on the main thread; maps every non-success outcome to an [AuthError]. */
    private suspend fun promptForSignature(activity: FragmentActivity, text: BiometricGate.PromptText): Signature {
        val outcome = withContext(Dispatchers.Main.immediate) { BiometricGate.authenticate(activity, text) }
        return when (outcome) {
            is BiometricGate.Outcome.Authenticated -> outcome.signature
            BiometricGate.Outcome.Cancelled -> throw AuthError.Cancelled
            BiometricGate.Outcome.KeyInvalidated -> {
                // v1: clear the dead key and continue password-only (re-enrol with a link code to get it back).
                withContext(Dispatchers.IO) { keys.deleteAuthKey() }
                throw AuthError.BiometricInvalidated
            }
            is BiometricGate.Outcome.Failed -> throw AuthError.BiometricFailed(outcome.code, outcome.message)
        }
    }

    private suspend fun applyAuthResponse(resp: AuthResponse) {
        serverConfig.setIdentity(resp.user.userId, resp.device.deviceId, resp.user.displayName)
        serverConfig.applyClientConfig(resp.config)
        resp.user.hasPassword?.let { serverConfig.setHasPassword(it) }
        storeSession(resp.session)
        unlocked(resp.session)
    }

    private fun unlocked(newSession: Session) {
        token = newSession.token
        session = newSession
        backgroundedAt = null
        _state.value = AuthState.Unlocked(newSession)
        runCatching { onUnlocked() }
    }

    private suspend fun storeSession(s: Session) = withContext(Dispatchers.IO) {
        secureStore.putString(SecureStore.KEY_SESSION_TOKEN, s.token)
        secureStore.putString(SecureStore.KEY_SESSION_EXPIRES_AT, s.expiresAt.toString())
    }

    private fun loadStoredSession(): Session? {
        val t = secureStore.getString(SecureStore.KEY_SESSION_TOKEN) ?: return null
        val exp = secureStore.getString(SecureStore.KEY_SESSION_EXPIRES_AT)?.toLongOrNull() ?: 0L
        return Session(t, exp)
    }

    private fun clearStoredSession() {
        secureStore.remove(SecureStore.KEY_SESSION_TOKEN)
        secureStore.remove(SecureStore.KEY_SESSION_EXPIRES_AT)
    }

    private fun markRevoked(reason: String) {
        token = null
        session = null
        runCatching { clearStoredSession() }
        _state.value = AuthState.Revoked(reason)
        runCatching { onLockedOut() }
    }

    /** Waits (briefly) until the synchronous config snapshot used by the API/WS providers reflects a write. */
    private suspend fun awaitConfig(predicate: (ServerConfig) -> Boolean) {
        withTimeoutOrNull(CONFIG_SETTLE_TIMEOUT_MS) { serverConfig.current.first(predicate) }
    }

    /** Runs an auth step and turns every failure into a typed [AuthError] (revocation also flips the state). */
    private suspend fun runAuth(block: suspend () -> Unit): Result<Unit> {
        val error: AuthError = try {
            block()
            return Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthError) {
            e
        } catch (e: ApiException) {
            AuthError.fromApi(e.code, e.status, e.message ?: "", e)
        } catch (e: Exception) {
            AuthError.Local(e.message ?: e.javaClass.simpleName, e)
        }
        if (error is AuthError.Revoked) markRevoked(error.reason)
        return Result.failure(error)
    }

    companion object {
        private const val INITIAL_LOAD_TIMEOUT_MS = 1_500L
        private const val CONFIG_SETTLE_TIMEOUT_MS = 2_000L
        private const val LOGOUT_TIMEOUT_MS = 5_000L

        /** "HUAWEI AQM-LX1" → "Huawei AQM-LX1", "samsung SM-G991B" → "Samsung SM-G991B": a prefill the user can edit. */
        fun defaultDeviceName(): String = deviceName(Build.MANUFACTURER, Build.MODEL)

        fun deviceName(manufacturer: String?, model: String?): String {
            val brand = manufacturer.orEmpty().trim().lowercase().replaceFirstChar { it.uppercaseChar() }
            val m = model.orEmpty().trim()
            return when {
                brand.isEmpty() -> m
                m.isEmpty() -> brand
                m.startsWith(brand, ignoreCase = true) -> m
                else -> "$brand $m"
            }
        }
    }
}
