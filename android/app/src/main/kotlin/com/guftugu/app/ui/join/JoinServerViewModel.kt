package com.guftugu.app.ui.join

import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.guftugu.app.R
import com.guftugu.app.core.auth.AuthError
import com.guftugu.app.core.auth.InviteCodes
import com.guftugu.app.core.util.Time
import com.guftugu.app.data.api.ApiException
import com.guftugu.app.data.repo.AuthExtras
import com.guftugu.app.data.repo.AuthState
import com.guftugu.app.data.repo.ServerInfo
import com.guftugu.app.di.AppGraph
import com.guftugu.app.protocol.PROTOCOL_VERSION
import com.guftugu.app.ui.navigation.IntentBus
import com.guftugu.app.ui.navigation.JoinIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Join a server (PROTOCOL §1–§2): take an invite from the camera, the clipboard, a deep link or
 * typed fields; validate it against `/.well-known/guftugu`; hand the confirmed server + code to
 * the Enroll screen.
 */
class JoinServerViewModel(private val graph: AppGraph) : ViewModel() {

    enum class Mode { ACTIONS, MANUAL, CHECKING, FOUND, REVOKED }

    data class UiState(
        val mode: Mode = Mode.ACTIONS,
        /** Pre-filled with the builder's server: people only type the invite code. */
        val apiUrl: String = com.guftugu.app.BuildConfig.DEFAULT_SERVER_URL,
        val code: String = "",
        /** Validated server, shown in the confirmation card. */
        val server: ServerInfo? = null,
        val pendingCode: String = "",
        val message: UiMessage? = null,
        val revokedServer: String? = null,
        val resetting: Boolean = false,
    ) {
        val manualReady: Boolean get() = apiUrl.isNotBlank() && code.isNotBlank()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var checkJob: Job? = null

    init {
        viewModelScope.launch {
            graph.authRepository.state.collect { auth ->
                if (auth is AuthState.Revoked) {
                    _state.update { it.copy(mode = Mode.REVOKED, revokedServer = graph.serverConfig.current.value.serverName) }
                } else if (_state.value.mode == Mode.REVOKED) {
                    _state.update { UiState() }
                }
            }
        }
    }

    // ---------- inputs ----------

    /** A `guftugu://join` deep link left on the [IntentBus] by MainActivity. */
    fun consumeDeepLink() {
        val j = IntentBus.consumeJoin() ?: return
        validate(j.apiUrl, j.code)
    }

    fun onScanResult(contents: String?) {
        if (contents == null) return // scan cancelled: stay quiet
        val j = JoinIntent.parse(contents)
        if (j == null) {
            _state.update { it.copy(message = UiMessage.Res(R.string.join_invalid_link)) }
            return
        }
        validate(j.apiUrl, j.code)
    }

    fun onCameraDenied() {
        _state.update { it.copy(message = UiMessage.Res(R.string.join_camera_denied)) }
    }

    fun pasteFromClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        val j = text?.let { JoinIntent.parse(findLink(it)) }
        if (j == null) {
            _state.update { it.copy(message = UiMessage.Res(R.string.join_clipboard_empty)) }
            return
        }
        validate(j.apiUrl, j.code)
    }

    fun showManual() = _state.update { it.copy(mode = Mode.MANUAL, message = null) }

    fun backToActions() {
        checkJob?.cancel()
        _state.update { it.copy(mode = Mode.ACTIONS, message = null, server = null) }
    }

    fun setApiUrl(v: String) = _state.update { it.copy(apiUrl = v, message = null) }

    fun setCode(v: String) = _state.update { it.copy(code = v.uppercase(), message = null) }

    fun submitManual() {
        val s = _state.value
        val url = s.apiUrl.trim().let { if (it.isNotEmpty() && !it.contains("://")) "https://$it" else it }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            _state.update { it.copy(message = UiMessage.Res(R.string.join_invalid_url)) }
            return
        }
        val code = InviteCodes.normalize(s.code)
        if (!InviteCodes.isValid(code)) {
            _state.update { it.copy(code = code, message = UiMessage.Res(R.string.join_invalid_code)) }
            return
        }
        validate(url, code, fromManual = true)
    }

    /** The user confirmed the server card; returns what Enroll needs (apiUrl, code). */
    fun confirmed(): Pair<String, String>? {
        val s = _state.value
        val server = s.server ?: return null
        return server.apiUrl to s.pendingCode
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun startOver() {
        val extras = graph.authRepository as? AuthExtras ?: return
        _state.update { it.copy(resetting = true) }
        viewModelScope.launch {
            runCatching { extras.resetEnrollment() }
            _state.update { UiState() }
        }
    }

    // ---------- discovery ----------

    private fun validate(apiUrl: String, rawCode: String, fromManual: Boolean = false) {
        // A revoked install must be wiped ("Start over") before it can join anything again.
        if (graph.authRepository.state.value is AuthState.Revoked) return
        val code = InviteCodes.normalize(rawCode)
        val fallback = if (fromManual) Mode.MANUAL else Mode.ACTIONS
        if (!InviteCodes.isValid(code)) {
            _state.update { it.copy(mode = fallback, message = UiMessage.Res(R.string.join_invalid_code)) }
            return
        }
        checkJob?.cancel()
        _state.update { it.copy(mode = Mode.CHECKING, message = null, apiUrl = apiUrl, code = code) }
        checkJob = viewModelScope.launch {
            val result: Result<ServerInfo> = try {
                val wk = graph.api.wellKnown(apiUrl)
                if (wk.protocolVersion != PROTOCOL_VERSION) {
                    Result.failure(AuthError.ServerIncompatible(wk.protocolVersion))
                } else {
                    Time.observeServerTime(wk.serverTime)
                    Result.success(
                        ServerInfo(
                            apiUrl = wk.apiUrl.trim().ifBlank { apiUrl }.trimEnd('/'),
                            wsUrl = wk.wsUrl.trim(),
                            name = wk.name.trim().ifBlank { hostOf(apiUrl) },
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                Result.failure(
                    when (e.code) {
                        "bad_response", "invalid_url", "not_found" -> AuthError.ServerIncompatible(null)
                        else -> AuthError.fromApi(e.code, e.status, e.message ?: "", e)
                    },
                )
            } catch (e: Exception) {
                Result.failure(AuthError.Local(e.message ?: e.javaClass.simpleName, e))
            }
            result.onSuccess { server ->
                _state.update { it.copy(mode = Mode.FOUND, server = server, pendingCode = code) }
            }.onFailure { err ->
                val auth = err as? AuthError ?: AuthError.Local(err.message ?: "?", err)
                _state.update { it.copy(mode = fallback, message = UiMessage.Auth(auth)) }
            }
        }
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url

    private fun findLink(text: String): String {
        val i = text.indexOf("${JoinIntent.SCHEME}://")
        if (i < 0) return text.trim()
        val rest = text.substring(i)
        val end = rest.indexOfFirst { it.isWhitespace() }.let { if (it < 0) rest.length else it }
        return rest.substring(0, end)
    }
}
