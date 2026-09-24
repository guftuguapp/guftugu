package com.guftugu.app.ui.common

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelativeTimeTest {
    private val zone = ZoneOffset.UTC
    // Wednesday 2026-09-23 15:00 UTC
    private val now = ZonedDateTime.of(2026, 9, 23, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val labels = RelativeTimeLabels(justNow = "just now", minutesAgo = "%1\$d min ago", hoursAgo = "%1\$d h ago", yesterday = "yesterday")

    @Test
    fun `just now, minutes, hours`() {
        assertEquals("just now", RelativeTime.format(now - 10_000, now, labels, zone))
        assertEquals("just now", RelativeTime.format(now + 60_000, now, labels, zone)) // clock skew
        assertEquals("5 min ago", RelativeTime.format(now - 5 * 60_000, now, labels, zone))
        assertEquals("3 h ago", RelativeTime.format(now - 3 * 3_600_000, now, labels, zone))
    }

    @Test
    fun `plain %d placeholders also work`() {
        assertEquals("5 min ago", RelativeTime.format(now - 5 * 60_000, now, RelativeTimeLabels.DEFAULT, zone))
    }

    @Test
    fun `yesterday and older`() {
        val yesterday = ZonedDateTime.of(2026, 9, 22, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("yesterday", RelativeTime.format(yesterday, now, labels, zone))
        val older = ZonedDateTime.of(2026, 1, 5, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val label = RelativeTime.format(older, now, labels, zone)
        assertTrue(label, label.contains("2026"))
    }

    @Test
    fun `online window`() {
        assertTrue(RelativeTime.isOnline(now - 30_000, now))
        assertFalse(RelativeTime.isOnline(now - 10 * 60_000, now))
        assertFalse(RelativeTime.isOnline(null, now))
    }
}
