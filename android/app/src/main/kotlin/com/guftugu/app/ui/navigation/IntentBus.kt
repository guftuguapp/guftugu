package com.guftugu.app.ui.navigation

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Parsed `guftugu://join?api=<urlencoded apiUrl>&code=GFT-XXXX-XXXX` (PROTOCOL §2). */
data class JoinIntent(val apiUrl: String, val code: String) {
    companion object {
        const val SCHEME = "guftugu"
        const val HOST = "join"

        fun parse(uri: Uri?): JoinIntent? {
            if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
            val api = uri.getQueryParameter("api")?.trim().orEmpty()
            val code = uri.getQueryParameter("code")?.trim()?.uppercase().orEmpty()
            if (api.isEmpty() || code.isEmpty()) return null
            if (!api.startsWith("https://") && !api.startsWith("http://")) return null
            return JoinIntent(apiUrl = api.trimEnd('/'), code = code)
        }

        fun parse(text: String?): JoinIntent? = text?.trim()?.let { runCatching { Uri.parse(it) }.getOrNull() }?.let(::parse)

        fun link(apiUrl: String, code: String): String =
            "$SCHEME://$HOST?api=${Uri.encode(apiUrl)}&code=${Uri.encode(code)}"
    }
}

/**
 * Simple in-memory bus for things an Activity intent asks the UI to do: a join deep link, or
 * opening a conversation / call from a notification. Values stay until consumed.
 */
object IntentBus {
    private val _join = MutableStateFlow<JoinIntent?>(null)
    val join: StateFlow<JoinIntent?> = _join.asStateFlow()

    private val _openConversation = MutableStateFlow<String?>(null)
    val openConversation: StateFlow<String?> = _openConversation.asStateFlow()

    private val _incomingCall = MutableStateFlow<String?>(null)
    val incomingCall: StateFlow<String?> = _incomingCall.asStateFlow()

    private val _callAction = MutableStateFlow<Pair<String, String>?>(null)
    /** (action, callId) from notification buttons; see Notifier.ACTION_*. */
    val callAction: StateFlow<Pair<String, String>?> = _callAction.asStateFlow()

    fun publishJoin(intent: JoinIntent) { _join.value = intent }
    fun consumeJoin(): JoinIntent? = _join.value.also { _join.value = null }

    fun publishOpenConversation(convId: String) { _openConversation.value = convId }
    fun consumeOpenConversation(): String? = _openConversation.value.also { _openConversation.value = null }

    fun publishIncomingCall(callId: String) { _incomingCall.value = callId }
    fun consumeIncomingCall(): String? = _incomingCall.value.also { _incomingCall.value = null }

    fun publishCallAction(action: String, callId: String) { _callAction.value = action to callId }
    fun consumeCallAction(): Pair<String, String>? = _callAction.value.also { _callAction.value = null }

    // ---------- calls (appended by the calls feature) ----------

    private val _openCall = MutableStateFlow<String?>(null)
    /** Tap on the ongoing-call notification (MainActivity.EXTRA_CALL_ID without an action): show call/{callId}. */
    val openCall: StateFlow<String?> = _openCall.asStateFlow()

    fun publishOpenCall(callId: String) { _openCall.value = callId }
    fun consumeOpenCall(): String? = _openCall.value.also { _openCall.value = null }
}
