package com.guftugu.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.guftugu.app.MainActivity
import com.guftugu.app.R

/**
 * Local notifications (there is no FCM). Three channels:
 * - `messages` (high): new messages, grouped per conversation
 * - `calls` (high, full-screen intent): incoming calls
 * - `service` (low): the persistent "connected" notification of RealtimeService
 *
 * Never puts decrypted message text into logs; notification text is fine (it stays on-device).
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_messages_desc)
                enableVibration(true)
                setShowBadge(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, context.getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_calls_desc)
                enableVibration(true)
                setBypassDnd(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_service_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The RealtimeService foreground notification. */
    fun serviceNotification(connected: Boolean = true): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_service_title))
            .setContentText(context.getString(if (connected) R.string.notification_service_text else R.string.notification_service_disconnected))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    /** The CallService foreground notification while a call is active. */
    fun ongoingCallNotification(callId: String, peerName: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(peerName)
            .setContentText(context.getString(R.string.notification_call_in_progress))
            .setContentIntent(openCall(callId))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, context.getString(R.string.action_hang_up), callAction(callId, ACTION_HANGUP))
            .build()

    /** One notification per conversation (id derived from convId). */
    fun showMessageNotification(convId: String, title: String, text: String, count: Int = 1) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setNumber(count)
            .setAutoCancel(true)
            .setContentIntent(openConversation(convId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_MESSAGES)
            .build()
        notify(messageNotificationId(convId), n)
    }

    fun cancelMessageNotification(convId: String) = manager.cancel(messageNotificationId(convId))

    /** Full-screen incoming-call notification with Answer / Decline actions. */
    fun showIncomingCall(callId: String, callerName: String, video: Boolean) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(callerName)
            .setContentText(context.getString(if (video) R.string.notification_incoming_video_call else R.string.notification_incoming_audio_call))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(openIncomingCall(callId), true)
            .setContentIntent(openIncomingCall(callId))
            .addAction(0, context.getString(R.string.action_decline), callAction(callId, ACTION_DECLINE))
            .addAction(0, context.getString(R.string.action_answer), callAction(callId, ACTION_ANSWER))
            .build()
        notify(NOTIFICATION_ID_INCOMING_CALL, n)
    }

    fun cancelIncomingCall() = manager.cancel(NOTIFICATION_ID_INCOMING_CALL)

    private fun notify(id: Int, notification: Notification) {
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call; nothing to do.
        }
    }

    // ---------- intents ----------

    private fun openApp(): PendingIntent = activity(Intent(context, MainActivity::class.java), 0)

    private fun openConversation(convId: String): PendingIntent =
        activity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONV_ID, convId), convId.hashCode())

    private fun openIncomingCall(callId: String): PendingIntent =
        activity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_INCOMING_CALL_ID, callId), callId.hashCode())

    private fun openCall(callId: String): PendingIntent =
        activity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CALL_ID, callId), callId.hashCode() + 1)

    private fun callAction(callId: String, action: String): PendingIntent =
        activity(
            Intent(context, MainActivity::class.java).setAction(action).putExtra(MainActivity.EXTRA_CALL_ID, callId),
            (action + callId).hashCode(),
        )

    private fun activity(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_SERVICE = "service"
        const val GROUP_MESSAGES = "com.guftugu.app.MESSAGES"

        const val NOTIFICATION_ID_SERVICE = 1
        const val NOTIFICATION_ID_INCOMING_CALL = 2
        const val NOTIFICATION_ID_ONGOING_CALL = 3
        private const val NOTIFICATION_ID_MESSAGE_BASE = 1000

        const val ACTION_ANSWER = "com.guftugu.app.action.ANSWER_CALL"
        const val ACTION_DECLINE = "com.guftugu.app.action.DECLINE_CALL"
        const val ACTION_HANGUP = "com.guftugu.app.action.HANGUP_CALL"

        fun messageNotificationId(convId: String): Int = NOTIFICATION_ID_MESSAGE_BASE + (convId.hashCode() and 0x7FFFFFFF) % 100_000
    }
}
