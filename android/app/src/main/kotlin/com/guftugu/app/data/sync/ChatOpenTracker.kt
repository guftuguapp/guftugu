package com.guftugu.app.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which conversation the user is looking at right now (at most one). The chat screen calls
 * [opened] in a `DisposableEffect(convId)` and [closed] on dispose; the sync engine uses it to
 * decide whether an incoming message counts as unread / deserves a notification.
 * In-memory only.
 */
object ChatOpenTracker {
    private val _openConvId = MutableStateFlow<String?>(null)
    val openConvId: StateFlow<String?> = _openConvId.asStateFlow()

    fun opened(convId: String) { _openConvId.value = convId }

    /** Only clears when [convId] is still the open one (screens can overlap during transitions). */
    fun closed(convId: String) { _openConvId.compareAndSet(convId, null) }

    /** Open on screen *and* the app is visible — a chat left open in the background is not "open". */
    fun isOpen(convId: String): Boolean = _openConvId.value == convId && AppVisibility.isVisible

    fun isOpenIgnoringVisibility(convId: String): Boolean = _openConvId.value == convId
}
