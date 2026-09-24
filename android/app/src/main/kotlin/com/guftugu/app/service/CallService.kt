package com.guftugu.app.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.guftugu.app.GuftuguApp
import com.guftugu.app.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that runs for the duration of a call so the microphone/camera keep working
 * when the screen is off or the app is in the background. Started by [com.guftugu.app.calls.CallManagerImpl]
 * at Outgoing/Active and stopped at Ended.
 *
 * - Holds a partial wake lock (CPU on for audio) while running; released on stop.
 * - Handles the notification actions ([Notifier.ACTION_ANSWER] / [Notifier.ACTION_DECLINE] /
 *   [Notifier.ACTION_HANGUP]) when they are delivered here, delegating to the CallManager.
 *
 * The manifest declares `microphone|camera|phoneCall`; at runtime we request only microphone
 * (+ camera for video). `phoneCall` requires `MANAGE_OWN_CALLS`, which the app does not hold.
 */
class CallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = GuftuguApp.graph(this)
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            Notifier.ACTION_ANSWER -> {
                scope.launch { runCatching { graph.callManager.answer() } }
                return START_NOT_STICKY
            }
            Notifier.ACTION_DECLINE -> {
                scope.launch { runCatching { graph.callManager.reject() } }
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            Notifier.ACTION_HANGUP -> {
                scope.launch { runCatching { graph.callManager.hangup() } }
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }

        val callId = intent?.getStringExtra(EXTRA_CALL_ID) ?: ""
        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME) ?: getString(com.guftugu.app.R.string.title_call)
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
        val notification = graph.notifier.ongoingCallNotification(callId, peerName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 34+ rejects a type whose runtime permission is missing, so only claim what we hold.
                var type = 0
                if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (video && granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (type == 0) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                ServiceCompat.startForeground(this, Notifier.NOTIFICATION_ID_ONGOING_CALL, notification, type)
            } else {
                startForeground(Notifier.NOTIFICATION_ID_ONGOING_CALL, notification)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException (missing runtime permission):
            // the call keeps running while the activity is visible; give up on the service quietly.
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopForegroundAndSelf() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.guftugu.app.action.CALL_START"
        const val ACTION_STOP = "com.guftugu.app.action.CALL_STOP"
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_PEER_NAME = "peerName"
        const val EXTRA_VIDEO = "video"

        private const val WAKE_LOCK_TAG = "guftugu:call"
        /** Safety net: no call runs longer than this without the lock being re-acquired (calls re-start the service on Active). */
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000

        /** Idempotent: a second start while running just refreshes the notification and the wake lock. */
        fun start(context: Context, callId: String, peerName: String, video: Boolean) {
            val intent = Intent(context, CallService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_PEER_NAME, peerName)
                .putExtra(EXTRA_VIDEO, video)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CallService::class.java)
            try {
                context.startService(Intent(intent).setAction(ACTION_STOP))
            } catch (e: Exception) {
                // Background-start restriction: stopping outright also removes the notification and runs onDestroy.
                runCatching { context.stopService(intent) }
            }
        }
    }
}
