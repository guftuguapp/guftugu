package com.guftugu.app.data.sync

import com.guftugu.app.data.db.ConversationEntity
import com.guftugu.app.data.db.MessageEntity
import com.guftugu.app.data.db.MessageStatus
import com.guftugu.app.protocol.CallInfo
import com.guftugu.app.protocol.CallOutcome
import com.guftugu.app.protocol.CallType
import com.guftugu.app.protocol.Conversation
import com.guftugu.app.protocol.ConversationType
import com.guftugu.app.protocol.Member
import com.guftugu.app.protocol.MemberRole
import com.guftugu.app.protocol.MessageKind
import com.guftugu.app.protocol.ProtocolJson
import com.guftugu.app.protocol.SystemEvent
import com.guftugu.app.protocol.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRulesTest {
    private val labels = PreviewLabels()

    private fun row(
        msgId: String = "m_1",
        senderId: String = "u_other",
        kind: String = MessageKind.E2E,
        contentType: String? = "text",
        text: String? = "hello",
        attachmentJson: String? = null,
        deletedAt: Long? = null,
        status: String = MessageStatus.SENT,
        callJson: String? = null,
        systemJson: String? = null,
    ) = MessageEntity(
        msgId = msgId, convId = "c_1", senderId = senderId, kind = kind, sentAt = 1, createdAt = 1, deletedAt = deletedAt,
        contentType = contentType, text = text, attachmentJson = attachmentJson, callJson = callJson, systemJson = systemJson, status = status,
    )

    // ---------- previews ----------

    @Test
    fun `text previews get You or the sender's first name in groups`() {
        assertEquals("hello", SyncRules.previewOf(row(), mine = false, isGroup = false, senderName = "Ammi Jaan", labels = labels))
        assertEquals("Ammi: hello", SyncRules.previewOf(row(), mine = false, isGroup = true, senderName = "Ammi Jaan", labels = labels))
        assertEquals("You: hello", SyncRules.previewOf(row(senderId = "u_me"), mine = true, isGroup = true, senderName = null, labels = labels))
    }

    @Test
    fun `media previews use the caption or a type label`() {
        assertEquals("Photo", SyncRules.previewOf(row(contentType = "image", text = null), false, false, null, labels))
        assertEquals("nice one", SyncRules.previewOf(row(contentType = "image", text = "nice one"), false, false, null, labels))
        assertEquals("Video", SyncRules.previewOf(row(contentType = "video", text = ""), false, false, null, labels))
        assertEquals("Voice note", SyncRules.previewOf(row(contentType = "audio", text = null), false, false, null, labels))
        assertEquals("File", SyncRules.previewOf(row(contentType = "file", text = null), false, false, null, labels))
    }

    @Test
    fun `deleted, locked, call and system rows`() {
        assertEquals("Message deleted", SyncRules.previewOf(row(deletedAt = 5, text = null), true, true, null, labels))
        assertEquals("Waiting for keys…", SyncRules.previewOf(row(status = MessageStatus.UNDECRYPTABLE, contentType = null, text = null), false, true, "X", labels))
        val json = ProtocolJson
        val missed = json.encodeToString(CallInfo.serializer(), CallInfo("k_1", CallType.VIDEO, CallOutcome.MISSED))
        assertEquals("Missed call", SyncRules.previewOf(row(kind = MessageKind.CALL, callJson = missed, contentType = null, text = null), false, true, "X", labels))
        val answered = json.encodeToString(CallInfo.serializer(), CallInfo("k_1", CallType.AUDIO, CallOutcome.ANSWERED, durationMs = 61_000))
        assertEquals("Audio call · 1:01", SyncRules.previewOf(row(kind = MessageKind.CALL, callJson = answered, contentType = null, text = null), false, false, null, labels))
        val sys = json.encodeToString(SystemInfo.serializer(), SystemInfo(SystemEvent.RENAMED))
        assertEquals("Group renamed", SyncRules.previewOf(row(kind = MessageKind.SYSTEM, systemJson = sys, contentType = null, text = null), false, true, "X", labels))
        val sysText = json.encodeToString(SystemInfo.serializer(), SystemInfo(SystemEvent.MEMBER_ADDED, text = "Abbu joined"))
        assertEquals("Abbu joined", SyncRules.previewOf(row(kind = MessageKind.SYSTEM, systemJson = sysText, contentType = null, text = null), false, true, "X", labels))
    }

    @Test
    fun `previews are single line and bounded`() {
        assertEquals("a b c", SyncRules.singleLine("a\n\n b\t c  "))
        assertEquals(120, SyncRules.singleLine("x".repeat(500)).length)
        assertEquals("Ammi: line one line two", SyncRules.previewOf(row(text = "line one\nline two"), false, true, "Ammi", labels))
    }

    @Test
    fun `notification text names the sender only in groups and never says You`() {
        assertEquals("hello", SyncRules.notificationText(row(), isGroup = false, senderName = "Ammi", labels = labels))
        assertEquals("Ammi: hello", SyncRules.notificationText(row(), isGroup = true, senderName = "Ammi Jaan", labels = labels))
        assertEquals("Photo", SyncRules.notificationText(row(contentType = "image", text = null), isGroup = false, senderName = "Ammi", labels = labels))
    }

    // ---------- unread ----------

    @Test
    fun `unread counts others' readable rows after my last read`() {
        val rows = listOf(
            row(msgId = "m_1"),
            row(msgId = "m_2", senderId = "u_me"),
            row(msgId = "m_3"),
            row(msgId = "m_4", deletedAt = 1),
            row(msgId = "m_5", status = MessageStatus.UNDECRYPTABLE, contentType = null),
            row(msgId = "m_6"),
        )
        assertEquals(3, SyncRules.countUnread(rows, "u_me", lastReadMsgId = null))
        assertEquals(1, SyncRules.countUnread(rows, "u_me", lastReadMsgId = "m_3"))
        assertEquals(0, SyncRules.countUnread(rows, "u_me", lastReadMsgId = "m_6"))
    }

    @Test
    fun `incoming decision - mine or known rows do nothing`() {
        val d = SyncRules.decideIncoming(isMine = true, alreadyKnown = false, chatOpen = false, appVisible = false, decrypted = true)
        assertEquals(SyncRules.IncomingDecision(countUnread = false, notify = false, markRead = false), d)
        val k = SyncRules.decideIncoming(isMine = false, alreadyKnown = true, chatOpen = false, appVisible = false, decrypted = true)
        assertEquals(SyncRules.IncomingDecision(countUnread = false, notify = false, markRead = false), k)
    }

    @Test
    fun `incoming decision - open chat marks read, background notifies only when readable`() {
        val open = SyncRules.decideIncoming(isMine = false, alreadyKnown = false, chatOpen = true, appVisible = true, decrypted = true)
        assertEquals(SyncRules.IncomingDecision(countUnread = false, notify = false, markRead = true), open)
        val visibleElsewhere = SyncRules.decideIncoming(isMine = false, alreadyKnown = false, chatOpen = false, appVisible = true, decrypted = true)
        assertEquals(SyncRules.IncomingDecision(countUnread = true, notify = false, markRead = false), visibleElsewhere)
        val background = SyncRules.decideIncoming(isMine = false, alreadyKnown = false, chatOpen = false, appVisible = false, decrypted = true)
        assertEquals(SyncRules.IncomingDecision(countUnread = true, notify = true, markRead = false), background)
        val locked = SyncRules.decideIncoming(isMine = false, alreadyKnown = false, chatOpen = false, appVisible = false, decrypted = false)
        assertEquals(SyncRules.IncomingDecision(countUnread = true, notify = false, markRead = false), locked)
    }

    // ---------- reconciliation ----------

    @Test
    fun `message plan - initial page, forward paging, or nothing`() {
        assertEquals(SyncRules.MessagePlan.None, SyncRules.planMessageSync(null, null))
        assertEquals(SyncRules.MessagePlan.None, SyncRules.planMessageSync(null, "m_1"))
        assertEquals(SyncRules.MessagePlan.Initial(50), SyncRules.planMessageSync("m_9", null))
        assertEquals(SyncRules.MessagePlan.After("m_5", 200), SyncRules.planMessageSync("m_9", "m_5"))
        assertEquals(SyncRules.MessagePlan.None, SyncRules.planMessageSync("m_5", "m_5"))
        assertEquals(SyncRules.MessagePlan.None, SyncRules.planMessageSync("m_4", "m_5"))
    }

    @Test
    fun `removed conversations are the local ones missing from the server list`() {
        assertEquals(setOf("c_2"), SyncRules.removedConversationIds(listOf("c_1", "c_2"), listOf("c_1", "c_3")))
        assertTrue(SyncRules.removedConversationIds(emptyList(), listOf("c_1")).isEmpty())
    }

    private fun wire(convId: String = "c_1", keyId: String? = "x_1", rotation: Boolean = false, myRead: String? = null, lastMsgId: String? = "m_3") = Conversation(
        convId = convId, type = ConversationType.GROUP, name = "Cousins", createdBy = "u_me", createdAt = 1,
        members = listOf(Member("u_me", MemberRole.OWNER, 1, myRead), Member("u_other", MemberRole.MEMBER, 1, null)),
        currentKeyId = keyId, keyRotationRequired = rotation, lastMsgId = lastMsgId, lastMessageAt = 3,
    )

    @Test
    fun `merge keeps local preview and unread, takes the newer last-read id`() {
        val existing = ConversationEntity(
            convId = "c_1", type = ConversationType.GROUP, name = "Old", createdBy = "u_me", createdAt = 1,
            currentKeyId = "x_1", localPreview = "You: hi", localPreviewAt = 2, unreadCount = 4, myLastReadMsgId = "m_2",
        )
        val merged = SyncRules.mergeConversation(wire(myRead = "m_1"), existing, "u_me")
        assertEquals("Cousins", merged.name)
        assertEquals("You: hi", merged.localPreview)
        assertEquals(4, merged.unreadCount)
        assertEquals("m_2", merged.myLastReadMsgId) // local ahead (PUT pending)
        assertEquals("m_3", merged.lastMsgId)

        val serverAhead = SyncRules.mergeConversation(wire(myRead = "m_3"), existing, "u_me")
        assertEquals("m_3", serverAhead.myLastReadMsgId)

        val fresh = SyncRules.mergeConversation(wire(myRead = "m_1"), null, "u_me")
        assertEquals("m_1", fresh.myLastReadMsgId)
        assertNull(fresh.localPreview)
        assertEquals(0, fresh.unreadCount)
    }

    @Test
    fun `key attention on new, rotated, keyless or changed-epoch conversations only`() {
        val existing = ConversationEntity(convId = "c_1", type = ConversationType.GROUP, createdBy = "u", createdAt = 1, currentKeyId = "x_1")
        assertTrue(SyncRules.needsKeyAttention(wire(), null))
        assertTrue(SyncRules.needsKeyAttention(wire(rotation = true), existing))
        assertTrue(SyncRules.needsKeyAttention(wire(keyId = null), existing))
        assertTrue(SyncRules.needsKeyAttention(wire(keyId = "x_2"), existing))
        assertFalse(SyncRules.needsKeyAttention(wire(keyId = "x_1"), existing))
    }

    @Test
    fun `key attention when the member set changes (a new member's devices need the key wrapped)`() {
        val existing = ConversationEntity(convId = "c_1", type = ConversationType.GROUP, createdBy = "u", createdAt = 1, currentKeyId = "x_1")
        val same = listOf("u_me", "u_other")
        assertFalse(SyncRules.needsKeyAttention(wire(keyId = "x_1"), existing, same))
        assertFalse(SyncRules.needsKeyAttention(wire(keyId = "x_1"), existing, same.reversed()))
        // Someone joined (our local row only knew "u_me")
        assertTrue(SyncRules.needsKeyAttention(wire(keyId = "x_1"), existing, listOf("u_me")))
        // Someone left / was replaced
        assertTrue(SyncRules.needsKeyAttention(wire(keyId = "x_1"), existing, listOf("u_me", "u_other", "u_gone")))
        assertTrue(SyncRules.needsKeyAttention(wire(keyId = "x_1"), existing, listOf("u_me", "u_x")))
        // Epoch rules still apply independently of membership
        assertTrue(SyncRules.needsKeyAttention(wire(keyId = "x_2"), existing, same))
        assertTrue(SyncRules.needsKeyAttention(wire(keyId = "x_1"), null, emptyList()))
        // A conversation summary without members (never sent for a live conversation) is not a membership change
        assertFalse(SyncRules.membersChanged(wire().copy(members = emptyList()), same))
    }
}
