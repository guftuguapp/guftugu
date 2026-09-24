package com.guftugu.app.core.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformTest {
    @Test
    fun `deterministic for the same key`() {
        val a = Waveform.bars("conv/c_1/abc.bin")
        val b = Waveform.bars("conv/c_1/abc.bin")
        assertArrayEquals(a, b, 0f)
        assertEquals(Waveform.BARS, a.size)
    }

    @Test
    fun `different keys give different bars`() {
        val a = Waveform.bars("conv/c_1/abc.bin")
        val b = Waveform.bars("conv/c_1/abd.bin")
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `bars stay within the drawable range and are not flat`() {
        for (key in listOf("", "x", "conv/c_9/0123456789.bin")) {
            val bars = Waveform.bars(key, 24)
            assertTrue(bars.all { it in 0.18f..1f })
            assertTrue("should vary", bars.max() - bars.min() > 0.05f)
        }
    }

    @Test
    fun `custom bar count`() {
        assertEquals(40, Waveform.bars("k", 40).size)
        assertEquals(1, Waveform.bars("k", 1).size)
    }
}
