package com.guftugu.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import com.guftugu.app.di.AppGraph
import com.guftugu.app.service.BackgroundConnection

class GuftuguApp : Application(), Configuration.Provider {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.notifier.createChannels()
        // Installs AppVisibility and the connection state machine (auth × "stay connected" ×
        // visibility × service alive) that starts/stops RealtimeService, SyncEngine and SyncWorker.
        BackgroundConnection.install(this)
    }

    /** WorkManager on-demand initialisation (the default initializer is removed in the manifest). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as GuftuguApp).graph
    }
}
