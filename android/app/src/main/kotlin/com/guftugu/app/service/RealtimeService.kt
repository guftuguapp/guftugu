package com.guftugu.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.guftugu.app.GuftuguApp
import com.guftugu.app.data.repo.AuthState
import com.guftugu.app.data.ws.ConnectionState
import com.guftugu.app.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (type `specialUse`) that keeps the WebSocket alive so messages and call
 * invites arrive without FCM (ARCHITECTURE.md "Realtime without push notifications").
 *
 * - Owns `SyncEngine.start()` while it runs (the engine owns the RealtimeClient connection).
 * - Shows [Notifier.serviceNotification] and updates it on connection-state changes.
 * - Re-opens the socket when the default network changes (Wi-Fi ↔ mobile) instead of waiting for
 *   the ping to time out.
 * - `START_STICKY`; stops itself as soon as the auth state is not `Unlocked` (no token → nothing to do).
 * - [BackgroundConnection] tracks whether the service is alive and decides who runs the engine
 *   after it stops.
 */
class RealtimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var authJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val graph = GuftuguApp.graph(this)
        startForegroundCompat(graph.notifier.serviceNotification(connected = false))
        BackgroundConnection.onServiceCreated()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = GuftuguApp.graph(this)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must re-post promptly on every start on API 34+; safe to repeat.
        startForegroundCompat(graph.notifier.serviceNotification(connected = graph.realtime.state.value is ConnectionState.Connected))

        if (graph.authRepository.sessionToken() == null) {
            // Locked or not enrolled: nothing to connect with. The unlock flow starts us again.
            stopSelf()
            return START_NOT_STICKY
        }

        BackgroundConnection.startEngine(graph)

        if (stateJob == null) {
            stateJob = scope.launch {
                graph.realtime.state.map { it is ConnectionState.Connected }.distinctUntilChanged().collectLatest { connected ->
                    runCatching { startForegroundCompat(graph.notifier.serviceNotification(connected = connected)) }
                }
            }
        }
        if (authJob == null) {
            authJob = scope.launch {
                graph.authRepository.state.collectLatest { state ->
                    if (state !is AuthState.Unlocked) stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        authJob?.cancel()
        scope.cancel()
        unregisterNetworkCallback()
        BackgroundConnection.onServiceDestroyed()
        if (!BackgroundConnection.isInstalled) {
            // No coordinator: nobody else owns the connection.
            BackgroundConnection.stopEngine(GuftuguApp.graph(this))
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ---------- network changes ----------

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = lastNetwork
                lastNetwork = network
                val realtime = GuftuguApp.graph(this@RealtimeService).realtime
                if (previous != null && previous != network) {
                    // Switched networks: the old socket is dead or about to be. Reconnect now.
                    realtime.disconnect()
                    realtime.connect()
                } else if (realtime.state.value is ConnectionState.Disconnected) {
                    realtime.connect()
                }
            }

            override fun onLost(network: Network) {
                if (lastNetwork == network) lastNetwork = null
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            networkCallback = null // too many callbacks registered / OEM quirks: fall back to ping timeouts
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, Notifier.NOTIFICATION_ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, Notifier.NOTIFICATION_ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } else {
                startForeground(Notifier.NOTIFICATION_ID_SERVICE, notification)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) when started from the background
            // without an exemption; give up quietly — the next app open starts us again.
            stopSelf()
        }
    }

    companion object {
        const val ACTION_START = "com.guftugu.app.action.REALTIME_START"
        const val ACTION_STOP = "com.guftugu.app.action.REALTIME_STOP"

        fun start(context: Context) {
            val intent = Intent(context, RealtimeService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Background-start restrictions; ignore, the foreground UI will retry.
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RealtimeService::class.java)) }
        }
    }
}
