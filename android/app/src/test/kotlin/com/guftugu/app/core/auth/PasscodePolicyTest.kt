package com.guftugu.app.core.auth

import com.guftugu.app.core.auth.PasscodePolicy.DAY_MS
import com.guftugu.app.core.auth.PasscodePolicy.Prompt
import org.junit.Assert.assertEquals
import org.junit.Test

class PasscodePolicyTest {
    private val t0 = 1_790_000_000_000L

    private fun decide(now: Long, has: Boolean = false, first: Long = t0, last: Long = 0L, snooze: Long = 0L) =
        PasscodePolicy.decide(now, has, first, last, snooze)

    @Test fun `nothing before any activity`() {
        assertEquals(Prompt.None, decide(now = t0 + 60 * DAY_MS, first = 0L))
    }

    @Test fun `nothing during the first week`() {
        assertEquals(Prompt.None, decide(now = t0 + 7 * DAY_MS - 1))
    }

    @Test fun `create after a week, snoozable`() {
        assertEquals(Prompt.Create(canSnooze = true), decide(now = t0 + 7 * DAY_MS))
    }

    @Test fun `later hides it for a day`() {
        val now = t0 + 8 * DAY_MS
        assertEquals(Prompt.None, decide(now = now, snooze = now + DAY_MS))
        assertEquals(Prompt.Create(canSnooze = true), decide(now = now + DAY_MS, snooze = now + DAY_MS))
    }

    @Test fun `after three weeks it can no longer be postponed`() {
        val now = t0 + 21 * DAY_MS
        assertEquals(Prompt.Create(canSnooze = false), decide(now = now, snooze = now + DAY_MS))
    }

    @Test fun `re-enter every 30 days once a passcode exists`() {
        val set = t0 + 10 * DAY_MS
        assertEquals(Prompt.None, decide(now = set + 30 * DAY_MS - 1, has = true, last = set))
        assertEquals(Prompt.Reenter, decide(now = set + 30 * DAY_MS, has = true, last = set))
    }

    @Test fun `an untracked existing passcode does not nag immediately`() {
        assertEquals(Prompt.None, decide(now = t0 + 90 * DAY_MS, has = true, last = 0L))
    }
}
