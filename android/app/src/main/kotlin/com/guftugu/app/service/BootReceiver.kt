package com.guftugu.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.guftugu.app.GuftuguApp
import com.guftugu.app.data.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * After boot / app update: the session token is never persisted across cold starts (the unlock
 * screen re-authenticates), so the WebSocket cannot be opened here even when "stay connected" is
 * on. What we can do is schedule the periodic [SyncWorker] so the phone reconciles as soon as the
 * user unlocks once; [BackgroundConnection] then starts [RealtimeService] from the foreground.
 * If a token somehow is available (a fork with a persisted session) the service is started directly.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val graph = GuftuguApp.graph(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cfg = graph.serverConfig.snapshot()
                if (!cfg.isEnrolled) return@launch
                if (cfg.backgroundConnectionEnabled && graph.authRepository.sessionToken() != null) {
                    RealtimeService.start(context)
                } else {
                    SyncWorker.schedule(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
