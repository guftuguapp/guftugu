package com.guftugu.app.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.guftugu.app.GuftuguApp
import com.guftugu.app.data.api.ApiException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Periodic reconciliation (every 15 min, network required) for when the always-on
 * [com.guftugu.app.service.RealtimeService] is switched off in Settings. Does nothing unless the
 * phone is enrolled and unlocked (a session token exists) — the token is never persisted across
 * cold starts, so after a reboot this only becomes useful once the user opens the app.
 * Scheduled/cancelled by [com.guftugu.app.service.BackgroundConnection].
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = GuftuguApp.graph(applicationContext)
        val cfg = graph.serverConfig.snapshot()
        if (!cfg.isEnrolled || graph.authRepository.sessionToken() == null) return Result.success()
        return try {
            graph.syncEngine.syncNow()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.isNetwork || e.status >= 500) Result.retry() else Result.success()
        } catch (e: Exception) {
            Result.success() // logged by the engine; a periodic worker must not retry-storm
        }
    }

    companion object {
        const val NAME = "guftugu.sync"
        const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME) }
        }
    }
}
