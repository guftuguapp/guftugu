package com.guftugu.app.data.repo

import com.guftugu.app.domain.User
import com.guftugu.app.protocol.PublicDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Everyone on this private server (PROTOCOL §5), cached in Room. */
interface UserRepository {
    fun users(): Flow<List<User>>
    fun user(userId: String): Flow<User?>
    fun me(): Flow<User?>
    /** `GET /users` → Room. */
    suspend fun refresh()
    suspend fun updateProfile(displayName: String?, avatarKey: String?)
    suspend fun changePassword(currentPassword: String, newPassword: String)
    /** Public keys of a user's devices (for wrapping/verifying); not cached long-term. */
    suspend fun devicesOf(userId: String): List<PublicDevice>
}

class StubUserRepository : UserRepository {
    override fun users(): Flow<List<User>> = flowOf(emptyList())
    override fun user(userId: String): Flow<User?> = flowOf(null)
    override fun me(): Flow<User?> = flowOf(null)
    override suspend fun refresh() = throw NotImplementedError("implemented in feature phase")
    override suspend fun updateProfile(displayName: String?, avatarKey: String?) = throw NotImplementedError("implemented in feature phase")
    override suspend fun changePassword(currentPassword: String, newPassword: String) = throw NotImplementedError("implemented in feature phase")
    override suspend fun devicesOf(userId: String): List<PublicDevice> = throw NotImplementedError("implemented in feature phase")
}
