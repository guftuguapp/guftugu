package com.guftugu.app.data.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TypingTrackerTest {
    @Before
    fun reset() = TypingTracker.clear()

    @Test
    fun `typing users exclude me and expire`() = runTest {
        val now = System.currentTimeMillis()
        TypingTracker.onTyping("c_1", "u_me", now)
        TypingTracker.onTyping("c_1", "u_a", now)
        TypingTracker.onTyping("c_2", "u_b", now)
        assertEquals(setOf("u_a"), TypingTracker.typingUsers("c_1", exceptUserId = "u_me").first())
        // an entry older than the TTL is dropped by prune()
        TypingTracker.onTyping("c_1", "u_old", now - TypingTracker.TTL_MS - 1)
        TypingTracker.prune(now)
        assertEquals(setOf("u_a", "u_me"), TypingTracker.typingUsers("c_1").first())
    }

    @Test
    fun `a message from the typist clears the indicator`() = runTest {
        val now = System.currentTimeMillis()
        TypingTracker.onTyping("c_1", "u_a", now)
        TypingTracker.onMessageFrom("c_1", "u_a")
        assertEquals(emptySet<String>(), TypingTracker.typingUsers("c_1").first())
    }
}
