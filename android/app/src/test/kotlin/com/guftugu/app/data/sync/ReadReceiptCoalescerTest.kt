package com.guftugu.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadReceiptCoalescerTest {
    private val c = ReadReceiptCoalescer(windowMs = 5_000)

    @Test
    fun `first read sends immediately, repeats within the window are coalesced into one timer`() {
        assertEquals(ReadReceiptCoalescer.Action.SendNow("m_1"), c.onRead("m_1", now = 10_000))
        // same id again: nothing
        assertEquals(ReadReceiptCoalescer.Action.Nothing, c.onRead("m_1", now = 10_100))
        // newer id inside the window: schedule the remainder of the window once
        assertEquals(ReadReceiptCoalescer.Action.ScheduleIn(4_800), c.onRead("m_2", now = 10_200))
        // more reads while the timer is pending: no second timer
        assertEquals(ReadReceiptCoalescer.Action.Nothing, c.onRead("m_3", now = 10_300))
        assertEquals(ReadReceiptCoalescer.Action.Nothing, c.onRead("m_4", now = 11_000))
        // the timer sends the newest target
        assertEquals("m_4", c.onTimerFired(now = 15_000))
        assertNull(c.onTimerFired(now = 15_001))
        // after the window a new id goes straight out
        assertEquals(ReadReceiptCoalescer.Action.SendNow("m_5"), c.onRead("m_5", now = 20_001))
    }

    @Test
    fun `older ids never go out and a failed send is retried on the next read`() {
        assertEquals(ReadReceiptCoalescer.Action.SendNow("m_5"), c.onRead("m_5", now = 0))
        assertEquals(ReadReceiptCoalescer.Action.Nothing, c.onRead("m_4", now = 6_000))
        c.onSendFailed("m_5")
        assertEquals(ReadReceiptCoalescer.Action.SendNow("m_5"), c.onRead("m_5", now = 6_000))
    }

    @Test
    fun `timer fires with nothing newer than what was sent`() {
        assertEquals(ReadReceiptCoalescer.Action.SendNow("m_1"), c.onRead("m_1", now = 0))
        assertEquals(ReadReceiptCoalescer.Action.ScheduleIn(4_000), c.onRead("m_2", now = 1_000))
        // meanwhile a fresh SendNow is impossible (timer pending) — fire the timer
        assertEquals("m_2", c.onTimerFired(now = 5_000))
        assertNull(c.onTimerFired(now = 5_000))
    }
}
