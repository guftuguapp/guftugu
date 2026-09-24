package com.guftugu.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UlidTest {
    @Test
    fun `is 26 crockford chars and encodes the timestamp`() {
        val t = 1_758_500_000_000L
        val id = Ulid.generate(t)
        assertEquals(26, id.length)
        assertTrue(id.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
        assertEquals(t, Ulid.timestampOf(id))
        assertEquals(t, Ulid.timestampOf("m_$id"))
    }

    @Test
    fun `monotonic within the same millisecond`() {
        val t = 1_758_500_000_000L
        val ids = List(50) { Ulid.generate(t) }
        assertEquals(ids, ids.sorted())
        assertEquals(50, ids.toSet().size)
    }
}
