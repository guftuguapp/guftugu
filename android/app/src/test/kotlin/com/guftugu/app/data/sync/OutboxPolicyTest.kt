package com.guftugu.app.data.sync

import com.guftugu.app.protocol.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxPolicyTest {
    @Test
    fun `backoff schedule is 1s, 4s, 16s then wait for next connect`() {
        assertEquals(1_000L, OutboxPolicy.backoffAfterFailure(1))
        assertEquals(4_000L, OutboxPolicy.backoffAfterFailure(2))
        assertEquals(16_000L, OutboxPolicy.backoffAfterFailure(3))
        assertNull(OutboxPolicy.backoffAfterFailure(4))
        assertNull(OutboxPolicy.backoffAfterFailure(99))
        // defensive: a zero/negative count still yields the first step
        assertEquals(1_000L, OutboxPolicy.backoffAfterFailure(0))
    }

    @Test
    fun `transport and server errors retry, auth waits, client rejections fail`() {
        assertEquals(OutboxPolicy.Outcome.RETRY, OutboxPolicy.classify(0, ErrorCode.NETWORK))
        assertEquals(OutboxPolicy.Outcome.RETRY, OutboxPolicy.classify(500, ErrorCode.INTERNAL))
        assertEquals(OutboxPolicy.Outcome.RETRY, OutboxPolicy.classify(503, null))
        assertEquals(OutboxPolicy.Outcome.RETRY, OutboxPolicy.classify(429, ErrorCode.RATE_LIMITED))
        assertEquals(OutboxPolicy.Outcome.RETRY, OutboxPolicy.classify(408, null))
        assertEquals(OutboxPolicy.Outcome.WAIT, OutboxPolicy.classify(401, ErrorCode.UNAUTHORIZED))
        assertEquals(OutboxPolicy.Outcome.FAIL, OutboxPolicy.classify(403, ErrorCode.FORBIDDEN))
        assertEquals(OutboxPolicy.Outcome.FAIL, OutboxPolicy.classify(404, ErrorCode.NOT_FOUND))
        assertEquals(OutboxPolicy.Outcome.FAIL, OutboxPolicy.classify(413, ErrorCode.PAYLOAD_TOO_LARGE))
        assertEquals(OutboxPolicy.Outcome.FAIL, OutboxPolicy.classify(400, ErrorCode.INVALID_REQUEST))
        assertEquals(OutboxPolicy.Outcome.FAIL, OutboxPolicy.classify(426, ErrorCode.UPGRADE_REQUIRED))
    }

    @Test
    fun `pending ids sort after every server id and round-trip the client id`() {
        val id = OutboxPolicy.pendingMsgId("01J8ABCDEFGHJKMNPQRSTVWXYZ")
        assertTrue(OutboxPolicy.isPendingMsgId(id))
        assertFalse(OutboxPolicy.isPendingMsgId("m_01J8ABCDEFGHJKMNPQRSTVWXYZ"))
        assertTrue(id > "m_01J8ZZZZZZZZZZZZZZZZZZZZZZ")
    }
}
