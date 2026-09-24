package com.guftugu.app.ui.chat

import com.guftugu.app.domain.Member
import com.guftugu.app.domain.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadTicksTest {
    private fun member(id: String, read: String?) = Member(id, isOwner = false, joinedAt = 0L, lastReadMsgId = read)

    @Test
    fun `direct chat watermark is the other member's receipt`() {
        val members = listOf(member("me", "m_09"), member("her", "m_05"))
        assertEquals("m_05", ReadTicks.watermark(members, "me"))
    }

    @Test
    fun `group watermark is the lowest receipt among the others`() {
        val members = listOf(member("me", "m_09"), member("a", "m_07"), member("b", "m_03"))
        assertEquals("m_03", ReadTicks.watermark(members, "me"))
    }

    @Test
    fun `no watermark when someone has read nothing or there is nobody else`() {
        assertNull(ReadTicks.watermark(listOf(member("me", "m_09"), member("a", null)), "me"))
        assertNull(ReadTicks.watermark(listOf(member("me", "m_09")), "me"))
        assertNull(ReadTicks.watermark(emptyList(), "me"))
    }

    @Test
    fun `read when msgId is at or below the watermark`() {
        assertTrue(ReadTicks.isReadByAll("m_05", "m_05"))
        assertTrue(ReadTicks.isReadByAll("m_04", "m_05"))
        assertFalse(ReadTicks.isReadByAll("m_06", "m_05"))
        assertFalse(ReadTicks.isReadByAll("m_01", null))
    }

    @Test
    fun `tick precedence`() {
        assertEquals(Tick.NONE, ReadTicks.tick(isMine = false, status = MessageStatus.SENT, msgId = "m_01", watermark = "m_09"))
        assertEquals(Tick.PENDING, ReadTicks.tick(true, MessageStatus.PENDING, "local_1", "m_09"))
        assertEquals(Tick.FAILED, ReadTicks.tick(true, MessageStatus.FAILED, "local_1", "m_09"))
        assertEquals(Tick.READ, ReadTicks.tick(true, MessageStatus.SENT, "m_05", "m_05"))
        assertEquals(Tick.SENT, ReadTicks.tick(true, MessageStatus.SENT, "m_06", "m_05"))
        assertEquals(Tick.SENT, ReadTicks.tick(true, MessageStatus.SENT, "m_06", null))
    }
}
