package com.guftugu.app.core.media

/** Pure LRU eviction planning for the decrypted media cache (`media_cache` rows). */
object CacheTrim {
    data class Entry(val key: String, val sizeBytes: Long, val lastUsedAt: Long)

    /**
     * Returns the entries to evict, least-recently-used first, so that the remaining total is
     * `<= maxBytes`. Never evicts anything when the total already fits. Ties on `lastUsedAt` are
     * broken by size (bigger first) so a single sweep frees as much as possible.
     */
    fun plan(entries: List<Entry>, maxBytes: Long): List<Entry> {
        var total = entries.sumOf { it.sizeBytes.coerceAtLeast(0) }
        if (total <= maxBytes || entries.isEmpty()) return emptyList()
        val ordered = entries.sortedWith(compareBy<Entry> { it.lastUsedAt }.thenByDescending { it.sizeBytes })
        val out = ArrayList<Entry>()
        for (e in ordered) {
            if (total <= maxBytes) break
            out += e
            total -= e.sizeBytes.coerceAtLeast(0)
        }
        return out
    }
}
