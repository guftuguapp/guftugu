package com.guftugu.app.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheTrimTest {
    private fun e(key: String, size: Long, used: Long) = CacheTrim.Entry(key, size, used)

    @Test
    fun `nothing evicted when the cache fits`() {
        val entries = listOf(e("a", 100, 1), e("b", 200, 2))
        assertTrue(CacheTrim.plan(entries, 300).isEmpty())
        assertTrue(CacheTrim.plan(entries, 10_000).isEmpty())
        assertTrue(CacheTrim.plan(emptyList(), 0).isEmpty())
    }

    @Test
    fun `evicts least recently used first until under budget`() {
        val entries = listOf(e("new", 100, 30), e("old", 100, 10), e("mid", 100, 20))
        val plan = CacheTrim.plan(entries, 150)
        assertEquals(listOf("old", "mid"), plan.map { it.key })
    }

    @Test
    fun `stops as soon as the remaining total fits`() {
        val entries = listOf(e("a", 50, 1), e("b", 500, 2), e("c", 50, 3))
        val plan = CacheTrim.plan(entries, 100)
        assertEquals(listOf("a", "b"), plan.map { it.key })
    }

    @Test
    fun `ties on lastUsedAt evict the bigger file first`() {
        val entries = listOf(e("small", 10, 5), e("big", 400, 5), e("fresh", 100, 9))
        val plan = CacheTrim.plan(entries, 200)
        assertEquals(listOf("big"), plan.map { it.key })
    }

    @Test
    fun `zero budget evicts everything`() {
        val entries = listOf(e("a", 1, 1), e("b", 1, 2))
        assertEquals(listOf("a", "b"), CacheTrim.plan(entries, 0).map { it.key })
    }

    @Test
    fun `negative sizes are treated as zero`() {
        val entries = listOf(e("weird", -5, 1), e("b", 100, 2))
        assertEquals(listOf("weird", "b"), CacheTrim.plan(entries, 50).map { it.key })
    }
}
