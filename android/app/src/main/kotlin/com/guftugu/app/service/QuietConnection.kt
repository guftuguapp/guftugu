package com.guftugu.app.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.guftugu.app.R
import com.guftugu.app.notifications.Notifier

/**
 * Messages and calls without the always-visible "Guftugu is connected" notice (the owner's wish).
 * Android doesn't let an app hide its own foreground-service notification, and phone makers lock their
 * auto-start pages to their own apps, so Guftugu opens the closest settings page and says what to do there.
 */
object QuietConnection {
    /** True once the person turned off the "Background connection" notification category. */
    fun isNoticeHidden(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = nm.getNotificationChannel(Notifier.CHANNEL_SERVICE) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** Guftugu's own "Background connection" notification category page. */
    fun noticeSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CHANNEL_SERVICE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private enum class Maker { HUAWEI, OPPO, XIAOMI, VIVO, SAMSUNG, OTHER }

    private fun maker(): Maker = when (Build.MANUFACTURER.lowercase()) {
        "huawei", "honor" -> Maker.HUAWEI
        "oppo", "realme", "oneplus" -> Maker.OPPO
        "xiaomi", "redmi", "poco" -> Maker.XIAOMI
        "vivo", "iqoo" -> Maker.VIVO
        "samsung" -> Maker.SAMSUNG
        else -> Maker.OTHER
    }

    /** The page closest to "let Guftugu keep running" on this phone (checked on Huawei EMUI 10 and ColorOS 6). */
    fun keepRunningIntent(context: Context): Intent = when (maker()) {
        Maker.HUAWEI -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY) // the Battery page lists "App launch"
        Maker.OTHER -> BackgroundConnection.batteryOptimizationIntent(context)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** What to switch on that page. */
    fun keepRunningHint(): Int = when (maker()) {
        Maker.HUAWEI -> R.string.quiet_keep_running_huawei
        Maker.OPPO -> R.string.quiet_keep_running_oppo
        Maker.XIAOMI -> R.string.quiet_keep_running_xiaomi
        Maker.VIVO -> R.string.quiet_keep_running_vivo
        Maker.SAMSUNG -> R.string.quiet_keep_running_samsung
        Maker.OTHER -> R.string.quiet_keep_running_other
    }

    /** Opens [intent]; if this phone doesn't have that page, opens Guftugu's App info instead. */
    fun open(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
