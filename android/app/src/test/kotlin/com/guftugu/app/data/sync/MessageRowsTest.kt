package com.guftugu.app.data.sync

import com.guftugu.app.data.db.MessageStatus
import com.guftugu.app.protocol.CallInfo
import com.guftugu.app.protocol.CallOutcome
import com.guftugu.app.protocol.CallType
import com.guftugu.app.protocol.Content
import com.guftugu.app.protocol.Envelope
import com.guftugu.app.protocol.Message
import com.guftugu.app.protocol.MessageKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Event/sync application against a fake decrypt function (no Room, no key manager). */
class MessageRowsTest {
    private val envelope = Envelope(keyId = "x_1", iv = "AAAAAAAAAAAAAAAA", ct = "AAAA")

    private fun wire(kind: String = MessageKind.E2E, envelope: Envelope? = this.envelope, deletedAt: Long? = null, call: CallInfo? = null) = Message(
        msgId = "m_1", convId = "c_1", senderId = "u_a", senderDeviceId = "d_a", clientId = "cid", kind = kind,
        envelope = envelope, call = call, sentAt = 10, createdAt = 11, deletedAt = deletedAt,
    )

    @Test
    fun `decrypted messages fill the content columns and drop the envelope`() = runTest {
        val row = MessageRows.fromWire(wire()) { convId, env, senderId, clientId ->
            assertEquals("c_1", convId); assertEquals("x_1", env.keyId); assertEquals("u_a", senderId); assertEquals("cid", clientId)
            Content.text("salaam", replyTo = "m_0")
        }
        assertEquals(MessageStatus.SENT, row.status)
        assertEquals("text", row.contentType)
        assertEquals("salaam", row.text)
        assertEquals("m_0", row.replyTo)
        assertNull(row.envelopeJson)
    }

    @Test
    fun `missing key or a failing decrypt keeps the envelope as UNDECRYPTABLE`() = runTest {
        val noKey = MessageRows.fromWire(wire()) { _, _, _, _ -> null }
        assertEquals(MessageStatus.UNDECRYPTABLE, noKey.status)
        assertNotNull(noKey.envelopeJson)
        assertEquals("x_1", MessageRows.pendingKeyId(noKey))
        assertNull(noKey.contentType)

        val boom = MessageRows.fromWire(wire()) { _, _, _, _ -> throw IllegalStateException("bad tag") }
        assertEquals(MessageStatus.UNDECRYPTABLE, boom.status)
    }

    @Test
    fun `call, system and tombstoned messages never touch the decrypt function`() = runTest {
        val call = MessageRows.fromWire(wire(kind = MessageKind.CALL, envelope = null, call = CallInfo("k_1", CallType.AUDIO, CallOutcome.MISSED))) { _, _, _, _ -> error("must not decrypt") }
        assertEquals(MessageStatus.SENT, call.status)
        assertNotNull(call.callJson)
        val deleted = MessageRows.fromWire(wire(deletedAt = 99)) { _, _, _, _ -> error("must not decrypt") }
        assertEquals(99L, deleted.deletedAt)
        assertNull(deleted.envelopeJson)
    }

    @Test
    fun `a readable local row is never downgraded, everything else takes the server row`() = runTest {
        val readable = MessageRows.fromWire(wire()) { _, _, _, _ -> Content.text("hi") }
        val locked = MessageRows.fromWire(wire()) { _, _, _, _ -> null }
        assertSame(readable, MessageRows.reconcile(readable, locked))
        assertSame(readable, MessageRows.reconcile(locked, readable))
        val tomb = MessageRows.fromWire(wire(deletedAt = 5)) { _, _, _, _ -> null }
        assertSame(tomb, MessageRows.reconcile(readable, tomb))
        assertSame(locked, MessageRows.reconcile(null, locked))
    }
}
