package com.guftugu.app.ui.settings

import androidx.fragment.app.FragmentActivity
import com.guftugu.app.core.auth.BiometricGate
import com.guftugu.app.data.repo.AuthExtras

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guftugu.app.BuildConfig
import com.guftugu.app.R
import com.guftugu.app.data.prefs.ServerConfigStore
import com.guftugu.app.data.repo.AuthRepository
import com.guftugu.app.data.repo.MediaRepository
import com.guftugu.app.data.repo.UserRepository
import com.guftugu.app.service.BackgroundConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class SettingsState(
    val displayName: String = "",
    val avatarKey: String? = null,
    val serverName: String = "",
    val version: String = BuildConfig.VERSION_NAME,
    /** Minutes; [SettingsViewModel.LOCK_EVERY_TIME] / [SettingsViewModel.LOCK_NEVER] are the sentinels. */
    val lockTimeoutMinutes: Int = 5,
    val backgroundEnabled: Boolean = true,
    val batteryExempt: Boolean = true,
    /** `guftugu_auth` exists: this phone unlocks with the fingerprint. */
    val fingerprintOn: Boolean = false,
    /** What the phone's own settings say about fingerprints right now. */
    val biometrics: BiometricGate.Availability = BiometricGate.Availability.UNAVAILABLE,
    val hasPassword: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

class SettingsViewModel(
    private val app: Application,
    private val users: UserRepository,
    private val media: MediaRepository,
    private val auth: AuthRepository,
    private val serverConfig: ServerConfigStore,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val batteryExempt = MutableStateFlow(BackgroundConnection.isIgnoringBatteryOptimizations(app))
    private val extras = auth as? AuthExtras
    /** Re-read on every resume: fingerprints may have been added/removed in the phone's settings. */
    private val fingerprint = MutableStateFlow(readFingerprint())

    private fun readFingerprint(): Pair<Boolean, BiometricGate.Availability> =
        (extras?.hasBiometricKey() ?: false) to BiometricGate.availability(app)

    /** One-shot notices ("Password changed") for a snackbar; cleared by [consumeNotice]. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    val state: StateFlow<SettingsState> = combine(
        combine(users.me(), serverConfig.config, ::Pair),
        busy, error, batteryExempt, fingerprint,
    ) { (me, cfg), busy, err, battery, fp ->
        SettingsState(
            fingerprintOn = fp.first,
            biometrics = fp.second,
            hasPassword = cfg.hasPassword,
            displayName = me?.displayName ?: cfg.displayName.orEmpty(),
            avatarKey = me?.avatarKey,
            serverName = cfg.serverName ?: app.getString(R.string.settings_unknown),
            lockTimeoutMinutes = cfg.lockTimeoutMinutes,
            backgroundEnabled = cfg.backgroundConnectionEnabled,
            batteryExempt = battery,
            busy = busy,
            error = err,
            loaded = true,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun onResume() {
        batteryExempt.value = BackgroundConnection.isIgnoringBatteryOptimizations(app)
        fingerprint.value = readFingerprint()
    }

    /** Turn fingerprint unlock on (one BiometricPrompt, then PUT /me/device/auth-key). */
    fun enableFingerprint(activity: FragmentActivity) {
        val e = extras ?: return
        run(R.string.settings_fingerprint_failed, R.string.settings_fingerprint_on) {
            e.enableBiometric(activity).getOrThrow()
            fingerprint.value = readFingerprint()
        }
    }

    fun dismissError() { error.value = null }
    fun consumeNotice() { _notice.value = null }

    val resolveAvatar: suspend (String) -> String? = { key -> runCatching { media.avatarUrl(key) }.getOrNull() }

    fun setDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        run(R.string.settings_profile_failed, R.string.settings_profile_saved) {
            users.updateProfile(displayName = trimmed, avatarKey = null)
            serverConfig.setDisplayName(trimmed)
        }
    }

    fun onAvatarPicked(uri: Uri, mime: String) {
        run(R.string.settings_photo_failed, R.string.settings_profile_saved) {
            val key = withContext(Dispatchers.IO) { media.uploadAvatar(uri, mime) }
            users.updateProfile(displayName = null, avatarKey = key)
        }
    }

    /** Sets a first backup passcode ([current] empty) or changes it. */
    fun changePassword(current: String, new: String) {
        run(R.string.settings_password_failed, R.string.settings_password_changed) {
            val e = extras
            if (e != null) e.setBackupPassword(current.ifEmpty { null }, new).getOrThrow() else users.changePassword(current, new)
        }
    }

    fun setLockTimeout(minutes: Int) {
        viewModelScope.launch { runCatching { serverConfig.setLockTimeoutMinutes(minutes) } }
    }

    fun setBackgroundConnection(enabled: Boolean) {
        viewModelScope.launch { runCatching { BackgroundConnection.setEnabled(app, enabled) } }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                auth.logout()
                runCatching { BackgroundConnection.stop(app) }
                onDone()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                error.value = app.getString(R.string.settings_profile_failed)
            } finally {
                busy.value = false
            }
        }
    }

    private fun run(failure: Int, success: Int, block: suspend () -> Unit) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                block()
                _notice.value = app.getString(success)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // The reason, for `adb logcat -s Guftugu` (no tokens or URLs: ApiException messages read
                // like "upload failed with HTTP 403")
                android.util.Log.w("Guftugu", "${app.getString(failure)}: ${t.javaClass.simpleName}: ${t.message}" +
                    ((t as? com.guftugu.app.data.api.ApiException)?.let { " [code=${it.code} status=${it.status}]" } ?: "") +
                    (t.cause?.let { " cause=${it.javaClass.simpleName}: ${it.message}" } ?: ""))
                error.value = app.getString(failure)
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        /** Lock the app every time it goes to the background. */
        const val LOCK_EVERY_TIME = 0
        /** Never auto-lock (only on cold start). */
        const val LOCK_NEVER = -1
        val LOCK_CHOICES = listOf(LOCK_EVERY_TIME, 1, 5, 30, LOCK_NEVER)
    }
}
