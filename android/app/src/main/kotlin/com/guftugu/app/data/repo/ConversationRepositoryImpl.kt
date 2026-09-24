package com.guftugu.app.data.repo

import android.util.Log
import androidx.room.withTransaction
import com.guftugu.app.data.api.GuftuguApi
import com.guftugu.app.data.db.ConvKeyRef
import com.guftugu.app.data.db.ConversationEntity
import com.guftugu.app.data.db.GuftuguDb
import com.guftugu.app.data.db.MemberEntity
import com.guftugu.app.data.db.UserEntity
import com.guftugu.app.data.prefs.ServerConfigStore
import com.guftugu.app.data.sync.PreviewLabels
import com.guftugu.app.data.sync.SyncEngine
import com.guftugu.app.data.sync.SyncRules
import com.guftugu.app.data.sync.toEntity
import com.guftugu.app.domain.Conversation
import com.guftugu.app.domain.toDomain
import com.guftugu.app.domain.toEntity
import com.guftugu.app.e2ee.ConversationKeyManager
import com.guftugu.app.protocol.ConversationType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.guftugu.app.protocol.Conversation as WireConversation

/**
 * Conversations (PROTOCOL §6). Lists are Room flows mapped to domain: a direct chat's title is
 * the other member's display name (users table), `hasKey` is derived from the `conv_keys` table
 * holding the conversation's `currentKeyId`. Mutations call the API, upsert the answer, and hand
 * key distribution to the key manager.
 */
class ConversationRepositoryImpl(
    private val api: GuftuguApi,
    private val db: GuftuguDb,
    private val serverConfig: ServerConfigStore,
    private val keyManager: ConversationKeyManager,
    private val syncEngine: SyncEngine? = null,
    private val labels: PreviewLabels = PreviewLabels(),
) : ConversationRepository {

    private val myUserId: Flow<String?> = serverConfig.config.map { it.userId }.distinctUntilChanged()

    override fun conversations(): Flow<List<Conversation>> = combine(
        db.conversations().observeAll(),
        db.members().observeAll(),
        db.users().observeAll(),
        db.convKeys().observeAllRefs(),
        myUserId,
    ) { convs, members, users, keys, me ->
        val membersByConv = members.groupBy { it.convId }
        val usersById = users.associateBy { it.userId }
        val keysByConv = keys.groupBy({ it.convId }, { it.keyId })
        convs.map { c -> mapConversation(c, membersByConv[c.convId].orEmpty(), usersById, keysByConv[c.convId], me, labels) }
    }.distinctUntilChanged()

    override fun conversation(convId: String): Flow<Conversation?> = combine(
        db.conversations().observe(convId),
        db.members().observeMembers(convId),
        db.users().observeAll(),
        db.convKeys().observeAllRefs(),
        myUserId,
    ) { c, members, users, keys, me ->
        c?.let { mapConversation(it, members, users.associateBy { u -> u.userId }, keys.filter { k -> k.convId == convId }.map(ConvKeyRef::keyId), me, labels) }
    }.distinctUntilChanged()

    override suspend fun refresh() {
        val engine = syncEngine
        if (engine != null) {
            engine.syncNow()
        } else {
            db.users().upsertAll(api.users().map { it.toEntity() })
            val me = currentUserId()
            api.conversations().forEach { upsertLocal(it, me) }
        }
    }

    override suspend fun createDirect(userId: String): Conversation {
        val c = api.createDirect(userId)
        upsertLocal(c, currentUserId())
        ensureKeysQuietly(c.convId)
        return load(c.convId)
    }

    override suspend fun createGroup(name: String, memberIds: List<String>): Conversation {
        val c = api.createGroup(name.trim(), memberIds.distinct())
        upsertLocal(c, currentUserId())
        ensureKeysQuietly(c.convId)
        return load(c.convId)
    }

    override suspend fun rename(convId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        upsertLocal(api.patchConversation(convId, name = trimmed), currentUserId())
    }

    override suspend fun setAvatar(convId: String, avatarKey: String?) {
        upsertLocal(api.patchConversation(convId, avatarKey = avatarKey), currentUserId())
    }

    override suspend fun addMembers(convId: String, userIds: List<String>) {
        if (userIds.isEmpty()) return
        upsertLocal(api.addMembers(convId, userIds.distinct()), currentUserId())
        ensureKeysQuietly(convId) // wrap the current key for the new members' devices
    }

    override suspend fun removeMember(convId: String, userId: String) {
        val me = currentUserId()
        if (userId == me) return leave(convId)
        upsertLocal(api.removeMember(convId, userId), me)
        ensureKeysQuietly(convId) // server flagged keyRotationRequired: rotate for the remaining members
    }

    override suspend fun leave(convId: String) {
        val me = currentUserId() ?: return
        api.removeMember(convId, me)
        db.withTransaction {
            db.messages().deleteForConversation(convId)
            db.members().deleteForConversation(convId)
            db.convKeys().deleteForConversation(convId)
            db.conversations().delete(convId)
        }
    }

    // ---------- internals ----------

    private suspend fun currentUserId(): String? = serverConfig.snapshot().userId

    private suspend fun upsertLocal(c: WireConversation, me: String?) {
        val existing = db.conversations().get(c.convId)
        db.withTransaction {
            db.conversations().upsert(SyncRules.mergeConversation(c, existing, me))
            db.members().replaceForConversation(c.convId, c.members.map { it.toEntity(c.convId) })
        }
    }

    private suspend fun ensureKeysQuietly(convId: String) {
        try {
            keyManager.ensureKeys(convId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sending will call ensureKeys again; log the class only.
            Log.w(TAG, "ensureKeys failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun load(convId: String): Conversation = conversation(convId).first()
        ?: throw IllegalStateException("conversation missing after upsert")

    companion object {
        private const val TAG = "ConversationRepo"
    }
}

/**
 * Room row → domain. Direct chats are titled with the other member's display name; `hasKey` is
 * true when no key epoch exists yet (this phone may mint one) or the current epoch is stored locally.
 */
internal fun mapConversation(
    c: ConversationEntity,
    members: List<MemberEntity>,
    usersById: Map<String, UserEntity>,
    keyIds: List<String>?,
    me: String?,
    labels: PreviewLabels,
): Conversation {
    val title = if (c.type == ConversationType.GROUP) {
        c.name?.takeIf { it.isNotBlank() } ?: labels.unknownUser
    } else {
        val other = members.firstOrNull { it.userId != me } ?: members.firstOrNull()
        other?.let { usersById[it.userId]?.displayName } ?: c.name?.takeIf { it.isNotBlank() } ?: labels.unknownUser
    }
    val hasKey = c.currentKeyId == null || (keyIds != null && c.currentKeyId in keyIds)
    return c.toDomain(title = title, members = members.map(MemberEntity::toDomain), hasKey = hasKey)
}
