package com.guftugu.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.withTransaction

/** Room converters (kept for future list/enum columns; current schema uses primitives, JSON strings and BLOBs). */
class Converters {
    @TypeConverter fun listToString(value: List<String>?): String? = value?.joinToString("\u001F")
    @TypeConverter fun stringToList(value: String?): List<String>? = value?.takeIf { it.isNotEmpty() }?.split("\u001F")
}

/**
 * The phone-side cache and source of truth for the UI (ARCHITECTURE.md "Caching & sync").
 * Everything the user sees is read from here; the network layer writes into it.
 */
@Database(
    entities = [
        UserEntity::class,
        ConversationEntity::class,
        MemberEntity::class,
        MessageEntity::class,
        ConvKeyEntity::class,
        OutboxEntity::class,
        MediaCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class GuftuguDb : RoomDatabase() {
    abstract fun users(): UserDao
    abstract fun conversations(): ConversationDao
    abstract fun members(): MemberDao
    abstract fun messages(): MessageDao
    abstract fun convKeys(): ConvKeyDao
    abstract fun outbox(): OutboxDao
    abstract fun mediaCache(): MediaCacheDao

    /** Wipe all local data (logout / revocation). Keys are wiped separately by KeystoreKeys. */
    suspend fun clearAllData() = withTransaction {
        outbox().clear()
        mediaCache().clear()
        messages().clear()
        convKeys().clear()
        members().clear()
        conversations().clear()
        users().clear()
    }

    companion object {
        const val NAME = "guftugu.db"

        fun build(context: Context): GuftuguDb =
            Room.databaseBuilder(context.applicationContext, GuftuguDb::class.java, NAME)
                // Schema is v1; until the first release ships, destructive migration keeps dev builds moving.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
