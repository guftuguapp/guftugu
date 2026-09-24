package com.guftugu.app.service

/**
 * Seam for forks that want push (FCM, UnifiedPush…) instead of / in addition to the foreground
 * WebSocket. The reference app ships [NoPush]; a provider would wake [RealtimeService] on push.
 */
interface PushProvider {
    val name: String
    fun isAvailable(): Boolean
    suspend fun register(): String?
    suspend fun unregister()
}

object NoPush : PushProvider {
    override val name = "none"
    override fun isAvailable() = false
    override suspend fun register(): String? = null
    override suspend fun unregister() = Unit
}
