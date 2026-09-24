package com.guftugu.app.ui.unlock

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guftugu.app.core.auth.AuthError
import com.guftugu.app.data.repo.AuthExtras
import com.guftugu.app.data.repo.AuthState
import com.guftugu.app.di.AppGraph
import com.guftugu.app.ui.join.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unlock (PROTOCOL §4): biometric challenge–response first, password as the fallback, local
 * biometric-only unlock when offline, and the "removed from server" dead end with "Start over".
 */
class UnlockViewModel(private val graph: AppGraph) : ViewModel() {

    enum class Phase { IDLE, PROMPTING, VERIFYING, RESETTING }

    data class UiState(
        val serverName: String? = null,
        val displayName: String? = null,
        val hasBiometricKey: Boolean = false,
        /** A (backup) passcode exists for this account, so the passcode path is worth offering. */
        val hasPassword: Boolean = true,
        val hasStoredSession: Boolean = false,
        val online: Boolean = true,
        val passwordMode: Boolean = false,
        val password: String = "",
        val phase: Phase = Phase.IDLE,
        val message: UiMessage? = null,
        /** Incremented on every failed attempt; the screen shakes when it changes. */
        val failures: Int = 0,
        val revokedReason: String? = null,
        val unlocked: Boolean = false,
        /** The device-key sign-in failed (e.g. the server knows a fingerprint key this phone lost). */
        val deviceFailed: Boolean = false,
    ) {
        /** No fingerprint and no passcode: the phone itself is the credential (WhatsApp-style). */
        val deviceMode: Boolean get() = !hasBiometricKey && !hasPassword
        val busy: Boolean get() = phase != Phase.IDLE
        val canSubmitPassword: Boolean get() = !busy && password.isNotEmpty() && online
        val offlineAvailable: Boolean get() = !online && hasBiometricKey && hasStoredSession
    }

    private val extras: AuthExtras? = graph.authRepository as? AuthExtras
    private val network = NetworkMonitor(graph.app)

    private val _state = MutableStateFlow(
        UiState(
            serverName = graph.serverConfig.current.value.serverName,
            displayName = graph.serverConfig.current.value.displayName,
            hasBiometricKey = extras?.hasBiometricKey() ?: false,
            hasPassword = extras?.hasPassword() ?: true,
            online = network.isOnlineNow(),
        ).let { it.copy(passwordMode = !it.hasBiometricKey) },
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var autoLaunched = false

    init {
        network.start()
        viewModelScope.launch { network.online.collect { on -> _state.update { it.copy(online = on) } } }
        viewModelScope.launch {
            // SecureStore may touch the disk on first read.
            val stored = withContext(Dispatchers.IO) { runCatching { extras?.hasStoredSession() ?: false }.getOrDefault(false) }
            _state.update { it.copy(hasStoredSession = stored) }
        }
        viewModelScope.launch {
            graph.authRepository.state.collect { auth ->
                when (auth) {
                    is AuthState.Revoked -> _state.update { it.copy(revokedReason = auth.reason, phase = Phase.IDLE) }
                    is AuthState.Unlocked -> _state.update { it.copy(unlocked = true, phase = Phase.IDLE, password = "") }
                    else -> Unit
                }
            }
        }
    }

    override fun onCleared() {
        network.stop()
        super.onCleared()
    }

    /** First show: launch the fingerprint prompt once if it makes sense. */
    fun autoLaunch(activity: FragmentActivity?) {
        if (autoLaunched) return
        autoLaunched = true
        val s = _state.value
        if (s.deviceMode && s.revokedReason == null) { unlockWithDevice(); return }
        if (activity == null || s.passwordMode || !s.hasBiometricKey || s.revokedReason != null) return
        if (s.online) unlockWithBiometric(activity) else if (s.offlineAvailable) unlockOffline(activity)
    }

    fun unlockWithBiometric(activity: FragmentActivity) {
        if (_state.value.busy) return
        _state.update { it.copy(phase = Phase.PROMPTING, message = null) }
        viewModelScope.launch {
            val result = guard { graph.authRepository.unlockWithBiometric(activity) }
            handle(result, biometric = true)
        }
    }

    fun unlockOffline(activity: FragmentActivity) {
        if (_state.value.busy) return
        _state.update { it.copy(phase = Phase.PROMPTING, message = null) }
        viewModelScope.launch {
            val result = guard { graph.authRepository.unlockOffline(activity) }
            handle(result, biometric = true)
        }
    }

    /** Sign in with this phone's device key (accounts without fingerprint or passcode). */
    fun unlockWithDevice() {
        val e = extras ?: return
        if (_state.value.busy) return
        _state.update { it.copy(phase = Phase.VERIFYING, message = null, deviceFailed = false) }
        viewModelScope.launch {
            val result = guard { e.unlockWithDevice() }
            result.onFailure { err ->
                if (err is AuthError.BadCredentials) _state.update { it.copy(deviceFailed = true) }
            }
            handle(result, biometric = false)
        }
    }

    fun unlockWithPassword() {
        val s = _state.value
        if (s.busy || s.password.isEmpty()) return
        _state.update { it.copy(phase = Phase.VERIFYING, message = null) }
        viewModelScope.launch {
            val result = guard { graph.authRepository.unlockWithPassword(s.password) }
            handle(result, biometric = false)
        }
    }

    fun setPassword(v: String) = _state.update { it.copy(password = v, message = null) }

    fun usePassword() = _state.update { it.copy(passwordMode = true, message = null) }

    fun useBiometric() = _state.update { it.copy(passwordMode = false, message = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun startOver() {
        val e = extras ?: return
        if (_state.value.phase == Phase.RESETTING) return
        _state.update { it.copy(phase = Phase.RESETTING) }
        viewModelScope.launch {
            runCatching { e.resetEnrollment() }
            _state.update { it.copy(phase = Phase.IDLE) }
        }
    }

    // ---------- internals ----------

    private suspend fun guard(block: suspend () -> Result<Unit>): Result<Unit> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(AuthError.Local(e.message ?: e.javaClass.simpleName, e))
    }

    private fun handle(result: Result<Unit>, biometric: Boolean) {
        result.onSuccess {
            _state.update { it.copy(phase = Phase.IDLE, unlocked = true, password = "", message = null) }
        }.onFailure { err ->
            val auth = err as? AuthError ?: AuthError.Local(err.message ?: "?", err)
            _state.update { s ->
                when (auth) {
                    AuthError.Cancelled -> s.copy(phase = Phase.IDLE)
                    AuthError.BiometricInvalidated, AuthError.NoBiometrics -> s.copy(
                        phase = Phase.IDLE,
                        passwordMode = true,
                        hasBiometricKey = false,
                        message = UiMessage.Auth(auth),
                    )
                    is AuthError.Revoked -> s.copy(phase = Phase.IDLE, revokedReason = auth.reason)
                    is AuthError.Network -> s.copy(
                        phase = Phase.IDLE,
                        online = network.isOnlineNow(),
                        message = UiMessage.Auth(auth),
                        failures = s.failures + 1,
                    )
                    else -> s.copy(
                        phase = Phase.IDLE,
                        message = UiMessage.Auth(auth),
                        failures = s.failures + 1,
                        // a wrong password clears the field; a biometric miss keeps everything
                        password = if (!biometric && auth is AuthError.BadCredentials) "" else s.password,
                    )
                }
            }
        }
    }
}
