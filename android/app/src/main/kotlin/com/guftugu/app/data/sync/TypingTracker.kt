package com.guftugu.app.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * In-memory "who is typing" state. The sync engine feeds it from `typing` events;
 * the chat screen observes it. Entries expire after [TTL_MS]. Never persisted.
 *
 * Timestamps are the *local receive time* (the server's `at` is not used, so clock skew between
 * phone and server can never make an indicator expire instantly or linger).
 */
object TypingTracker {
    const val TTL_MS = 6_000L

    data class Entry(val convId: String, val userId: String, val at: Long)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Raw entries (unexpired ones only after [prune]); mostly for tests. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun onTyping(convId: String, userId: String, at: Long = System.currentTimeMillis()) {
        val cutoff = at - TTL_MS
        _entries.value = _entries.value.filterNot { (it.convId == convId && it.userId == userId) || it.at < cutoff } + Entry(convId, userId, at)
    }

    /** User ids currently typing in [convId], excluding [exceptUserId] (usually me). */
    fun typingUsers(convId: String, exceptUserId: String? = null): Flow<Set<String>> = _entries.map { list ->
        val now = System.currentTimeMillis()
        list.filter { it.convId == convId && it.userId != exceptUserId && now - it.at < TTL_MS }.map { it.userId }.toSet()
    }.distinctUntilChanged()

    /** Drop expired entries so observers re-evaluate; call periodically (≈1 s) from the chat screen while shown. */
    fun prune(now: Long = System.currentTimeMillis()) {
        val cutoff = now - TTL_MS
        val current = _entries.value
        if (current.any { it.at < cutoff }) _entries.value = current.filter { it.at >= cutoff }
    }

    /** A message from [userId] in [convId] arrived: they are no longer typing. */
    fun onMessageFrom(convId: String, userId: String) {
        val current = _entries.value
        if (current.any { it.convId == convId && it.userId == userId }) {
            _entries.value = current.filterNot { it.convId == convId && it.userId == userId }
        }
    }

    fun clear() { _entries.value = emptyList() }
}
