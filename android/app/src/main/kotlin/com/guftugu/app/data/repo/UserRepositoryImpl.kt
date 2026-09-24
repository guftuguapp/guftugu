package com.guftugu.app.data.repo

import com.guftugu.app.core.util.Time
import com.guftugu.app.data.api.GuftuguApi
import com.guftugu.app.data.db.GuftuguDb
import com.guftugu.app.data.db.UserEntity
import com.guftugu.app.data.prefs.ServerConfigStore
import com.guftugu.app.domain.User
import com.guftugu.app.domain.toDomain
import com.guftugu.app.domain.toEntity
import com.guftugu.app.protocol.PublicDevice
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Everyone on this private server (PROTOCOL §5): Room is the source of truth, `refresh()` pulls `GET /users`. */
class UserRepositoryImpl(
    private val api: GuftuguApi,
    private val db: GuftuguDb,
    private val serverConfig: ServerConfigStore,
    private val deviceCacheTtlMs: Long = DEVICE_CACHE_TTL_MS,
) : UserRepository {

    private class CachedDevices(val at: Long, val devices: List<PublicDevice>)

    private val deviceCache = ConcurrentHashMap<String, CachedDevices>()

    override fun users(): Flow<List<User>> =
        db.users().observeAll().map { rows -> rows.map(UserEntity::toDomain) }.distinctUntilChanged()

    override fun user(userId: String): Flow<User?> =
        db.users().observe(userId).map { it?.toDomain() }.distinctUntilChanged()

    override fun me(): Flow<User?> =
        serverConfig.config.map { it.userId }.distinctUntilChanged().flatMapLatest { id ->
            if (id == null) flowOf(null) else db.users().observe(id).map { it?.toDomain() }
        }.distinctUntilChanged()

    override suspend fun refresh() {
        db.users().upsertAll(api.users().map { it.toEntity() })
    }

    override suspend fun updateProfile(displayName: String?, avatarKey: String?) {
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() }
        if (name == null && avatarKey == null) return
        val user = api.patchMe(displayName = name, avatarKey = avatarKey)
        db.users().upsert(user.toEntity())
        serverConfig.setDisplayName(user.displayName)
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        api.changePassword(currentPassword, newPassword)
    }

    /** Public device keys, cached briefly (key wrapping asks for several users in a row). */
    override suspend fun devicesOf(userId: String): List<PublicDevice> {
        val now = Time.nowMs()
        deviceCache[userId]?.let { if (now - it.at < deviceCacheTtlMs) return it.devices }
        val devices = api.userDevices(userId)
        deviceCache[userId] = CachedDevices(now, devices)
        return devices
    }

    fun invalidateDevices(userId: String? = null) {
        if (userId == null) deviceCache.clear() else deviceCache.remove(userId)
    }

    companion object {
        const val DEVICE_CACHE_TTL_MS = 60_000L
    }
}
