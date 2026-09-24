package com.guftugu.app.e2ee

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyCacheTest {
    @Test
    fun `stores by conversation and key id`() {
        val cache = KeyCache(4)
        val k = ByteArray(32) { 1 }
        cache.put("c_1", "x_1", k)
        assertArrayEquals(k, cache.get("c_1", "x_1"))
        assertNull(cache.get("c_2", "x_1"))
        assertNull(cache.get("c_1", "x_2"))
        assertTrue(cache.contains("c_1", "x_1"))
        assertFalse(cache.contains("c_1", "x_9"))
    }

    @Test
    fun `evicts least recently used beyond the limit`() {
        val cache = KeyCache(3)
        cache.put("c", "a", ByteArray(32))
        cache.put("c", "b", ByteArray(32))
        cache.put("c", "c", ByteArray(32))
        cache.get("c", "a") // touch a → b is now the eldest
        cache.put("c", "d", ByteArray(32))
        assertEquals(3, cache.size)
        assertTrue(cache.contains("c", "a"))
        assertFalse(cache.contains("c", "b"))
        assertTrue(cache.contains("c", "c"))
        assertTrue(cache.contains("c", "d"))
    }

    @Test
    fun `clear drops everything`() {
        val cache = KeyCache()
        repeat(10) { cache.put("c", "x_$it", ByteArray(32)) }
        assertEquals(10, cache.size)
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get("c", "x_1"))
    }

    @Test
    fun `default limit is 64`() {
        val cache = KeyCache()
        repeat(100) { cache.put("c", "x_$it", ByteArray(32)) }
        assertEquals(64, cache.size)
        assertFalse(cache.contains("c", "x_0"))
        assertTrue(cache.contains("c", "x_99"))
    }
}
