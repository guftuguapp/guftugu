package com.guftugu.app.core.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Base64UrlTest {
    @Test
    fun `rfc4648 vectors without padding`() {
        assertEquals("", Base64Url.encode(ByteArray(0)))
        assertEquals("Zg", Base64Url.encode("f".toByteArray()))
        assertEquals("Zm8", Base64Url.encode("fo".toByteArray()))
        assertEquals("Zm9v", Base64Url.encode("foo".toByteArray()))
        assertEquals("Zm9vYg", Base64Url.encode("foob".toByteArray()))
        assertEquals("Zm9vYmE", Base64Url.encode("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", Base64Url.encode("foobar".toByteArray()))
    }

    @Test
    fun `uses url-safe alphabet`() {
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte(), 0xFE.toByte())
        val encoded = Base64Url.encode(bytes)
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
        assertFalse(encoded.contains('='))
        assertTrue(encoded.contains('-') || encoded.contains('_'))
        assertArrayEquals(bytes, Base64Url.decode(encoded))
    }

    @Test
    fun `decodes padded and standard base64 too`() {
        assertArrayEquals("foob".toByteArray(), Base64Url.decode("Zm9vYg=="))
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xBF.toByte(), 0xFE.toByte())
        assertArrayEquals(bytes, Base64Url.decode(java.util.Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun `round trips random data`() {
        val rnd = java.util.Random(42)
        repeat(200) { n ->
            val bytes = ByteArray(n).also(rnd::nextBytes)
            assertArrayEquals(bytes, Base64Url.decode(Base64Url.encode(bytes)))
        }
    }

    @Test
    fun `invalid input yields null`() {
        assertNull(Base64Url.decodeOrNull("not base64!!"))
        assertNull(Base64Url.decodeOrNull(null))
    }
}
