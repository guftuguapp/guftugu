package com.guftugu.app.data.repo

import com.guftugu.app.data.db.ConversationEntity
import com.guftugu.app.data.db.MemberEntity
import com.guftugu.app.data.db.UserEntity
import com.guftugu.app.data.sync.PreviewLabels
import com.guftugu.app.domain.ConversationKind
import com.guftugu.app.protocol.ConversationType
import com.guftugu.app.protocol.MemberRole
import com.guftugu.app.protocol.UserRole
import com.guftugu.app.protocol.UserStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMappingTest {
    private val labels = PreviewLabels()
    private val users = mapOf(
        "u_me" to UserEntity("u_me", "Me", role = UserRole.MEMBER, status = UserStatus.ACTIVE),
        "u_ammi" to UserEntity("u_ammi", "Ammi Jaan", role = UserRole.MEMBER, status = UserStatus.ACTIVE),
    )
    private val members = listOf(MemberEntity("c_1", "u_me", MemberRole.MEMBER, 1), MemberEntity("c_1", "u_ammi", MemberRole.OWNER, 1, "m_3"))

    @Test
    fun `direct chats are titled with the other member`() {
        val c = ConversationEntity("c_1", ConversationType.DIRECT, createdBy = "u_me", createdAt = 1, currentKeyId = "x_1", localPreview = "hi", unreadCount = 2)
        val d = mapConversation(c, members, users, keyIds = listOf("x_1"), me = "u_me", labels = labels)
        assertEquals("Ammi Jaan", d.title)
        assertEquals(ConversationKind.DIRECT, d.kind)
        assertEquals(2, d.unreadCount)
        assertEquals("hi", d.preview)
        assertTrue(d.hasKey)
        assertTrue(d.members.first { it.userId == "u_ammi" }.isOwner)
    }

    @Test
    fun `unknown member falls back to the label and groups use their name`() {
        val direct = ConversationEntity("c_1", ConversationType.DIRECT, createdBy = "u_me", createdAt = 1)
        assertEquals("Unknown", mapConversation(direct, members, emptyMap(), null, "u_me", labels).title)
        val group = ConversationEntity("c_1", ConversationType.GROUP, name = "Cousins", createdBy = "u_me", createdAt = 1)
        assertEquals("Cousins", mapConversation(group, members, users, null, "u_me", labels).title)
    }

    @Test
    fun `hasKey - no epoch yet counts as ok, a current epoch must be stored locally`() {
        val noEpoch = ConversationEntity("c_1", ConversationType.GROUP, name = "g", createdBy = "u_me", createdAt = 1, currentKeyId = null)
        assertTrue(mapConversation(noEpoch, members, users, null, "u_me", labels).hasKey)
        val epoch = noEpoch.copy(currentKeyId = "x_2")
        assertFalse(mapConversation(epoch, members, users, listOf("x_1"), "u_me", labels).hasKey)
        assertTrue(mapConversation(epoch, members, users, listOf("x_1", "x_2"), "u_me", labels).hasKey)
    }
}
