package com.guftugu.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Upsert suspend fun upsert(user: UserEntity)
    @Upsert suspend fun upsertAll(users: List<UserEntity>)
    @Query("SELECT * FROM users ORDER BY displayName COLLATE NOCASE") fun observeAll(): Flow<List<UserEntity>>
    @Query("SELECT * FROM users WHERE userId = :userId") fun observe(userId: String): Flow<UserEntity?>
    @Query("SELECT * FROM users WHERE userId = :userId") suspend fun get(userId: String): UserEntity?
    @Query("SELECT * FROM users WHERE userId IN (:userIds)") suspend fun getAll(userIds: List<String>): List<UserEntity>
    @Query("DELETE FROM users") suspend fun clear()
}

@Dao
interface ConversationDao {
    @Upsert suspend fun upsert(conversation: ConversationEntity)
    @Upsert suspend fun upsertAll(conversations: List<ConversationEntity>)
    @Query("SELECT * FROM conversations ORDER BY COALESCE(lastMessageAt, createdAt) DESC") fun observeAll(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE convId = :convId") fun observe(convId: String): Flow<ConversationEntity?>
    @Query("SELECT * FROM conversations WHERE convId = :convId") suspend fun get(convId: String): ConversationEntity?
    @Query("SELECT * FROM conversations") suspend fun getAll(): List<ConversationEntity>
    @Query("UPDATE conversations SET localPreview = :preview, localPreviewAt = :at WHERE convId = :convId")
    suspend fun setLocalPreview(convId: String, preview: String?, at: Long?)
    @Query("UPDATE conversations SET unreadCount = :count WHERE convId = :convId") suspend fun setUnreadCount(convId: String, count: Int)
    @Query("UPDATE conversations SET myLastReadMsgId = :msgId, unreadCount = 0 WHERE convId = :convId")
    suspend fun setMyLastRead(convId: String, msgId: String?)
    @Query("UPDATE conversations SET currentKeyId = :keyId, keyRotationRequired = :rotationRequired WHERE convId = :convId")
    suspend fun setKeyState(convId: String, keyId: String?, rotationRequired: Boolean)
    // ---- sync layer (additive) ----
    @Query("SELECT convId FROM conversations") suspend fun ids(): List<String>
    @Query("UPDATE conversations SET myLastReadMsgId = :msgId WHERE convId = :convId") suspend fun setMyLastReadMsgId(convId: String, msgId: String?)
    @Query("UPDATE conversations SET lastMsgId = :lastMsgId, lastMessageAt = :lastMessageAt WHERE convId = :convId AND (lastMsgId IS NULL OR lastMsgId < :lastMsgId)")
    suspend fun advanceLastMessage(convId: String, lastMsgId: String, lastMessageAt: Long)
    @Query("UPDATE conversations SET lastMessageAt = :at WHERE convId = :convId AND (lastMessageAt IS NULL OR lastMessageAt < :at)")
    suspend fun bumpLastMessageAt(convId: String, at: Long)
    @Query("UPDATE conversations SET localPreview = :preview, localPreviewAt = :at, unreadCount = :unread WHERE convId = :convId")
    suspend fun setDerived(convId: String, preview: String?, at: Long?, unread: Int)
    @Query("DELETE FROM conversations WHERE convId = :convId") suspend fun delete(convId: String)
    @Query("DELETE FROM conversations") suspend fun clear()
}

@Dao
interface MemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(members: List<MemberEntity>)
    @Query("DELETE FROM members WHERE convId = :convId") suspend fun deleteForConversation(convId: String)
    @Transaction
    suspend fun replaceForConversation(convId: String, members: List<MemberEntity>) {
        deleteForConversation(convId)
        insertAll(members)
    }
    @Query("SELECT * FROM members WHERE convId = :convId ORDER BY joinedAt") fun observeMembers(convId: String): Flow<List<MemberEntity>>
    @Query("SELECT * FROM members WHERE convId = :convId ORDER BY joinedAt") suspend fun members(convId: String): List<MemberEntity>
    @Query("SELECT * FROM members") fun observeAll(): Flow<List<MemberEntity>>
    @Query("UPDATE members SET lastReadMsgId = :msgId WHERE convId = :convId AND userId = :userId")
    suspend fun setLastRead(convId: String, userId: String, msgId: String)
    // ---- sync layer (additive) ----
    @Query("SELECT * FROM members WHERE convId = :convId AND userId = :userId") suspend fun get(convId: String, userId: String): MemberEntity?
    @Query("DELETE FROM members") suspend fun clear()
}

@Dao
interface MessageDao {
    @Upsert suspend fun upsert(message: MessageEntity)
    @Upsert suspend fun upsertAll(messages: List<MessageEntity>)
    @Query("SELECT * FROM messages WHERE convId = :convId ORDER BY msgId ASC") fun observeMessages(convId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE convId = :convId ORDER BY msgId DESC LIMIT :limit") fun observeLatest(convId: String, limit: Int): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE msgId = :msgId") suspend fun get(msgId: String): MessageEntity?
    @Query("SELECT * FROM messages WHERE clientId = :clientId LIMIT 1") suspend fun getByClientId(clientId: String): MessageEntity?
    @Query("SELECT MAX(msgId) FROM messages WHERE convId = :convId AND status != 'pending' AND status != 'failed'") suspend fun latestMsgId(convId: String): String?
    @Query("SELECT MIN(msgId) FROM messages WHERE convId = :convId AND status != 'pending' AND status != 'failed'") suspend fun oldestMsgId(convId: String): String?
    @Query("SELECT * FROM messages WHERE convId = :convId AND status = 'undecryptable'") suspend fun undecryptable(convId: String): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM messages WHERE convId = :convId AND senderId != :myUserId AND deletedAt IS NULL AND (:afterMsgId IS NULL OR msgId > :afterMsgId) AND status = 'sent'")
    suspend fun countUnread(convId: String, myUserId: String, afterMsgId: String?): Int
    @Query("SELECT * FROM messages WHERE convId = :convId AND kind = 'e2e' AND deletedAt IS NULL AND status = 'sent' ORDER BY msgId DESC LIMIT 1")
    suspend fun latestVisible(convId: String): MessageEntity?
    @Query("UPDATE messages SET deletedAt = :deletedAt, text = NULL, attachmentJson = NULL, envelopeJson = NULL WHERE msgId = :msgId")
    suspend fun markDeleted(msgId: String, deletedAt: Long)
    @Query("UPDATE messages SET status = :status WHERE msgId = :msgId") suspend fun setStatus(msgId: String, status: String)
    // ---- sync layer (additive) ----
    /** Newest row of any kind that is worth previewing (pending ones included, failed ones not). */
    @Query("SELECT * FROM messages WHERE convId = :convId AND status != 'failed' ORDER BY msgId DESC LIMIT 1")
    suspend fun latestForPreview(convId: String): MessageEntity?
    @Query("SELECT convId, COUNT(*) AS count FROM messages WHERE status = 'undecryptable' GROUP BY convId")
    suspend fun undecryptableCounts(): List<ConvCount>
    @Query("SELECT * FROM messages WHERE convId = :convId AND status = 'pending' ORDER BY msgId ASC")
    suspend fun pendingFor(convId: String): List<MessageEntity>
    @Query("UPDATE messages SET status = :status, envelopeJson = NULL WHERE msgId = :msgId") suspend fun setStatusDropEnvelope(msgId: String, status: String)
    @Query("UPDATE messages SET attachmentJson = :attachmentJson WHERE msgId = :msgId") suspend fun setAttachmentJson(msgId: String, attachmentJson: String?)
    @Query("SELECT COUNT(*) FROM messages WHERE convId = :convId AND status != 'pending' AND status != 'failed'") suspend fun countSynced(convId: String): Int
    @Query("DELETE FROM messages WHERE msgId = :msgId") suspend fun delete(msgId: String)
    @Query("DELETE FROM messages WHERE convId = :convId") suspend fun deleteForConversation(convId: String)
    @Query("DELETE FROM messages") suspend fun clear()
}

@Dao
interface ConvKeyDao {
    @Upsert suspend fun upsert(key: ConvKeyEntity)
    @Query("SELECT * FROM conv_keys WHERE convId = :convId AND keyId = :keyId") suspend fun get(convId: String, keyId: String): ConvKeyEntity?
    @Query("SELECT * FROM conv_keys WHERE convId = :convId ORDER BY keyId ASC") suspend fun keysFor(convId: String): List<ConvKeyEntity>
    @Query("SELECT keyId FROM conv_keys WHERE convId = :convId ORDER BY keyId DESC LIMIT 1") suspend fun latestKeyId(convId: String): String?
    @Query("SELECT COUNT(*) FROM conv_keys WHERE convId = :convId") fun observeCount(convId: String): Flow<Int>
    // ---- sync layer (additive) ----
    @Query("SELECT convId, keyId FROM conv_keys") fun observeAllRefs(): Flow<List<ConvKeyRef>>
    @Query("DELETE FROM conv_keys WHERE convId = :convId") suspend fun deleteForConversation(convId: String)
    @Query("DELETE FROM conv_keys") suspend fun clear()
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(item: OutboxEntity): Long
    @Upsert suspend fun upsert(item: OutboxEntity)
    @Query("SELECT * FROM outbox ORDER BY createdAt ASC") suspend fun pending(): List<OutboxEntity>
    @Query("SELECT * FROM outbox WHERE convId = :convId ORDER BY createdAt ASC") fun observeForConversation(convId: String): Flow<List<OutboxEntity>>
    @Query("SELECT * FROM outbox ORDER BY createdAt ASC") fun observeAll(): Flow<List<OutboxEntity>>
    @Query("SELECT * FROM outbox WHERE clientId = :clientId") suspend fun get(clientId: String): OutboxEntity?
    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE clientId = :clientId") suspend fun recordFailure(clientId: String, error: String?)
    // ---- sync layer (additive) ----
    @Query("UPDATE outbox SET contentJson = :contentJson, localUri = NULL WHERE clientId = :clientId") suspend fun setUploaded(clientId: String, contentJson: String)
    @Query("SELECT COUNT(*) FROM outbox") suspend fun count(): Int
    @Query("DELETE FROM outbox WHERE clientId = :clientId") suspend fun delete(clientId: String)
    @Query("DELETE FROM outbox") suspend fun clear()
}

@Dao
interface MediaCacheDao {
    @Upsert suspend fun upsert(entry: MediaCacheEntity)
    @Query("SELECT * FROM media_cache WHERE `key` = :key") suspend fun get(key: String): MediaCacheEntity?
    @Query("UPDATE media_cache SET lastUsedAt = :at WHERE `key` = :key") suspend fun touch(key: String, at: Long)
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM media_cache") suspend fun totalSize(): Long
    @Query("SELECT * FROM media_cache ORDER BY lastUsedAt ASC LIMIT :limit") suspend fun leastRecentlyUsed(limit: Int): List<MediaCacheEntity>
    @Delete suspend fun delete(entry: MediaCacheEntity)
    @Query("DELETE FROM media_cache WHERE `key` = :key") suspend fun delete(key: String)
    @Query("DELETE FROM media_cache") suspend fun clear()
}
