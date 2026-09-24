package com.guftugu.app.e2ee

/**
 * Bounded LRU of unwrapped conversation keys (`convId` × `keyId` → 32 raw bytes) so the hot
 * decrypt path never touches Room or the Keystore twice for the same key. Thread-safe.
 *
 * Entries are dropped, not zeroed, on eviction/clear: a caller may still be encrypting with a
 * reference it obtained a moment earlier, and zeroing under it would silently corrupt output.
 */
internal class KeyCache(private val maxEntries: Int = DEFAULT_MAX) {

    private val map = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(convId: String, keyId: String): ByteArray? = map[id(convId, keyId)]

    @Synchronized
    fun put(convId: String, keyId: String, key: ByteArray) {
        map[id(convId, keyId)] = key
    }

    @Synchronized
    fun contains(convId: String, keyId: String): Boolean = map.containsKey(id(convId, keyId))

    @Synchronized
    fun clear() = map.clear()

    val size: Int
        @Synchronized get() = map.size

    private fun id(convId: String, keyId: String): String = convId + '\u0000' + keyId

    companion object {
        const val DEFAULT_MAX = 64
    }
}
