package com.guftugu.app.data.repo

import com.guftugu.app.domain.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Conversations (PROTOCOL §6) — Room-backed lists with local previews and unread counts. */
interface ConversationRepository {
    fun conversations(): Flow<List<Conversation>>
    fun conversation(convId: String): Flow<Conversation?>
    /** `GET /conversations` → Room (the SyncEngine calls this on connect / periodically). */
    suspend fun refresh()
    /** Existing or new direct conversation (idempotent); generates + distributes the key if new. */
    suspend fun createDirect(userId: String): Conversation
    suspend fun createGroup(name: String, memberIds: List<String>): Conversation
    suspend fun rename(convId: String, name: String)
    suspend fun setAvatar(convId: String, avatarKey: String?)
    suspend fun addMembers(convId: String, userIds: List<String>)
    suspend fun removeMember(convId: String, userId: String)
    suspend fun leave(convId: String)
}

class StubConversationRepository : ConversationRepository {
    override fun conversations(): Flow<List<Conversation>> = flowOf(emptyList())
    override fun conversation(convId: String): Flow<Conversation?> = flowOf(null)
    override suspend fun refresh() = throw NotImplementedError("implemented in feature phase")
    override suspend fun createDirect(userId: String): Conversation = throw NotImplementedError("implemented in feature phase")
    override suspend fun createGroup(name: String, memberIds: List<String>): Conversation = throw NotImplementedError("implemented in feature phase")
    override suspend fun rename(convId: String, name: String) = throw NotImplementedError("implemented in feature phase")
    override suspend fun setAvatar(convId: String, avatarKey: String?) = throw NotImplementedError("implemented in feature phase")
    override suspend fun addMembers(convId: String, userIds: List<String>) = throw NotImplementedError("implemented in feature phase")
    override suspend fun removeMember(convId: String, userId: String) = throw NotImplementedError("implemented in feature phase")
    override suspend fun leave(convId: String) = throw NotImplementedError("implemented in feature phase")
}
