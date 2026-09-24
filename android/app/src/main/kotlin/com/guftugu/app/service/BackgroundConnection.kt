package com.guftugu.app.service

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.guftugu.app.GuftuguApp
import com.guftugu.app.data.repo.AuthState
import com.guftugu.app.data.sync.AppVisibility
import com.guftugu.app.data.sync.SyncWorker
import com.guftugu.app.di.AppGraph
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Decides who keeps the WebSocket open (ARCHITECTURE.md "Realtime without push notifications"):
 *
 * | unlocked | "stay connected" | app visible | service | sync engine | SyncWorker |
 * |---|---|---|---|---|---|
 * | no | – | – | stopped | stopped | as scheduled |
 * | yes | on | yes | started (from the foreground, so allowed) | running | cancelled |
 * | yes | on | no | keeps running if alive; if the OS killed it, engine stops until START_STICKY brings it back | – | cancelled |
 * | yes | off | yes | stopped | running (foreground-only socket) | scheduled |
 * | yes | off | no | stopped | stopped | scheduled (15 min) |
 *
 * [install] (from `GuftuguApp.onCreate`) wires [AppVisibility] and this state machine; the Settings
 * screen only calls [setEnabled]. Everything here is idempotent, so it can be re-evaluated freely.
 */
object BackgroundConnection {
    private val installed = AtomicBoolean(false)
    private val _serviceRunning = MutableStateFlow(false)
    private val engineStarted = AtomicBoolean(false)

    /** True between `RealtimeService.onCreate` and `onDestroy`. */
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    val isInstalled: Boolean get() = installed.get()

    internal data class Desired(
        val visible: Boolean,
        val unlocked: Boolean,
        val enrolled: Boolean,
        val backgroundEnabled: Boolean,
        val serviceRunning: Boolean,
    )

    fun install(app: Application) {
        if (!installed.compareAndSet(false, true)) return
        AppVisibility.install(app)
        val graph = GuftuguApp.graph(app)
        graph.appScope.launch {
            combine(
                AppVisibility.visible,
                graph.authRepository.state.map { it is AuthState.Unlocked }.distinctUntilChanged(),
                graph.serverConfig.config.map { it.isEnrolled to it.backgroundConnectionEnabled }.distinctUntilChanged(),
                _serviceRunning,
            ) { visible, unlocked, (enrolled, bg), running -> Desired(visible, unlocked, enrolled, bg, running) }
                .distinctUntilChanged()
                .collect { reconcile(app, graph, it) }
        }
    }

    internal fun reconcile(context: Context, graph: AppGraph, d: Desired) {
        when {
            !d.unlocked -> {
                if (d.serviceRunning) RealtimeService.stop(context)
                stopEngine(graph)
            }
            d.backgroundEnabled -> {
                SyncWorker.cancel(context)
                if (d.visible && !d.serviceRunning) RealtimeService.start(context)
                if (d.visible || d.serviceRunning) startEngine(graph) else stopEngine(graph)
            }
            else -> {
                if (d.enrolled) SyncWorker.schedule(context)
                if (d.serviceRunning) RealtimeService.stop(context)
                if (d.visible) startEngine(graph) else stopEngine(graph)
            }
        }
    }

    /** Idempotent; also used by [RealtimeService] so the "engine is running" bookkeeping stays in one place. */
    internal fun startEngine(graph: AppGraph) {
        engineStarted.set(true)
        graph.syncEngine.start()
        // The call manager subscribes to realtime.events in its constructor (lazy in AppGraph):
        // touch it whenever the socket is in use so `call.invite` frames are observed.
        graph.callManager
    }

    internal fun stopEngine(graph: AppGraph) {
        // Only touch the (lazy) engine if it was ever started: avoids building the whole graph while locked.
        if (engineStarted.compareAndSet(true, false)) graph.syncEngine.stop()
    }

    /** Settings toggle: persist, then the state machine (or a direct fallback) applies it. */
    suspend fun setEnabled(context: Context, enabled: Boolean) {
        val graph = GuftuguApp.graph(context)
        graph.serverConfig.setBackgroundConnectionEnabled(enabled)
        if (!installed.get()) {
            if (enabled) {
                SyncWorker.cancel(context)
                if (graph.authRepository.sessionToken() != null) RealtimeService.start(context)
            } else {
                RealtimeService.stop(context)
                SyncWorker.schedule(context)
            }
        }
    }

    /** Start the foreground service if the user has it enabled and the app is enrolled + unlocked (fallback when [install] was not called). */
    fun ensureRunning(context: Context) {
        if (installed.get()) return
        val graph = GuftuguApp.graph(context)
        val cfg = graph.serverConfig.current.value
        if (cfg.isEnrolled && cfg.backgroundConnectionEnabled && graph.authRepository.sessionToken() != null) RealtimeService.start(context)
    }

    fun stop(context: Context) {
        RealtimeService.stop(context)
    }

    internal fun onServiceCreated() { _serviceRunning.value = true }

    internal fun onServiceDestroyed() { _serviceRunning.value = false }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Intent that asks the user to exempt the app from battery optimisation (needed on many OEMs for calls to ring). */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
}
