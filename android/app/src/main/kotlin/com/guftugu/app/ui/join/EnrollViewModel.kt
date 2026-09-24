package com.guftugu.app.ui.join

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guftugu.app.R
import com.guftugu.app.core.auth.AuthError
import com.guftugu.app.core.auth.BiometricGate
import com.guftugu.app.core.auth.PasswordStrength
import com.guftugu.app.data.repo.AuthExtras
import com.guftugu.app.data.repo.AuthRepositoryImpl
import com.guftugu.app.data.repo.ServerInfo
import com.guftugu.app.di.AppGraph
import com.guftugu.app.protocol.PROTOCOL_VERSION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Name + device name + fingerprint → `POST /enroll` (PROTOCOL §3).
 *
 * Fingerprint first: when the phone has a usable fingerprint the account is created with it and a
 * password is only an optional backup. A phone with a sensor but no registered fingerprint is told
 * so (and can open its fingerprint settings); a password is required only when there is no sensor,
 * or when the person explicitly chooses to continue without a fingerprint.
 */
class EnrollViewModel(private val graph: AppGraph, private val apiUrl: String, private val code: String) : ViewModel() {

    data class UiState(
        val serverName: String? = null,
        val wsUrl: String = "",
        val name: String = "",
        val deviceName: String = AuthRepositoryImpl.defaultDeviceName(),
        val biometrics: BiometricGate.Availability = BiometricGate.Availability.UNAVAILABLE,
        /** The fingerprint failed on this phone during enrolment: join without it. */
        val fingerprintBroken: Boolean = false,
        val busy: Boolean = false,
        val message: UiMessage? = null,
        val done: Boolean = false,
    ) {
        /** The account will unlock with the fingerprint (the phone has one registered). */
        val fingerprintMode: Boolean get() = biometrics == BiometricGate.Availability.AVAILABLE && !fingerprintBroken

        /** Joining never needs a passcode (created about a week later, WhatsApp-style). */
        val canSubmit: Boolean get() = !busy && name.isNotBlank() && deviceName.isNotBlank()
    }

    private val _state = MutableStateFlow(UiState(biometrics = BiometricGate.availability(graph.app)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Show whose server this is; non-fatal if it fails (enroll() re-discovers anyway).
        viewModelScope.launch {
            runCatching { graph.api.wellKnown(apiUrl) }.onSuccess { wk ->
                if (wk.protocolVersion == PROTOCOL_VERSION) {
                    _state.update { it.copy(serverName = wk.name.trim().ifBlank { null }, wsUrl = wk.wsUrl) }
                } else {
                    _state.update { it.copy(message = UiMessage.Auth(AuthError.ServerIncompatible(wk.protocolVersion))) }
                }
            }
        }
    }

    /** Called on every resume: the person may have just added a fingerprint in Settings. */
    fun refreshBiometrics() {
        val now = BiometricGate.availability(graph.app)
        _state.update { if (it.biometrics == now) it else it.copy(biometrics = now) }
    }

    fun setName(v: String) = _state.update { it.copy(name = v, message = null) }
    fun setDeviceName(v: String) = _state.update { it.copy(deviceName = v, message = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun submit(activity: FragmentActivity?) {
        val s = _state.value
        if (!s.canSubmit) return
        val extras = graph.authRepository as? AuthExtras
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val server = ServerInfo(apiUrl = apiUrl, wsUrl = s.wsUrl, name = s.serverName.orEmpty())
            val password: String? = null // created later (PasscodePolicy)
            val result = try {
                if (extras != null) {
                    extras.enroll(server, code, s.name.trim(), password, s.deviceName.trim(), biometricOptIn = s.fingerprintMode, activity = activity)
                } else {
                    graph.authRepository.enroll(server, code, s.name.trim(), password, s.deviceName.trim())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(AuthError.Local(e.message ?: e.javaClass.simpleName, e))
            }
            result.onSuccess {
                _state.update { it.copy(busy = false, done = true) }
            }.onFailure { err ->
                val auth = err as? AuthError ?: AuthError.Local(err.message ?: "?", err)
                _state.update {
                    when (auth) {
                        // A dismissed fingerprint prompt is not an error worth a banner.
                        is AuthError.Cancelled -> it.copy(busy = false)
                        // Fingerprint hardware/keystore trouble: offer the passcode path with the reason.
                        is AuthError.BiometricFailed, is AuthError.BiometricInvalidated ->
                            it.copy(busy = false, fingerprintBroken = true, message = UiMessage.Auth(auth))
                        else -> it.copy(busy = false, message = UiMessage.Auth(auth))
                    }
                }
            }
        }
    }

    companion object {
        /** Label for the strength bar. */
        fun strengthLabel(score: Int): Int = when (score) {
            0, 1 -> R.string.enroll_strength_weak
            2 -> R.string.enroll_strength_fair
            3 -> R.string.enroll_strength_good
            else -> R.string.enroll_strength_strong
        }
    }
}
