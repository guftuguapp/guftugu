package com.guftugu.app.ui.unlock

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Do we have a validated internet connection right now?" — decides whether the unlock screen
 * offers the server round-trip or the offline (local biometric) path. Cheap: one default-network
 * callback, no polling.
 */
class NetworkMonitor(context: Context) {
    private val cm: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(isOnlineNow())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (isOnlineNow()) _online.value = true }
        override fun onLost(network: Network) { _online.value = isOnlineNow() }
        override fun onUnavailable() { _online.value = false }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    fun start() {
        val m = cm ?: return
        if (registered) return
        runCatching { m.registerDefaultNetworkCallback(callback) }.onSuccess { registered = true }
        _online.value = isOnlineNow()
    }

    fun stop() {
        val m = cm ?: return
        if (!registered) return
        runCatching { m.unregisterNetworkCallback(callback) }
        registered = false
    }

    fun isOnlineNow(): Boolean {
        val m = cm ?: return true // no ConnectivityManager: assume online and let the request decide
        val n = m.activeNetwork ?: return false
        val caps = m.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
