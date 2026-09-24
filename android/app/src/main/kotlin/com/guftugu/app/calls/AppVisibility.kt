package com.guftugu.app.calls

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Is one of our activities in front?" without pulling in lifecycle-process. Used to decide
 * whether an incoming call needs a full-screen notification (app in background / screen off)
 * or whether the in-app IncomingCallScreen will show on its own.
 *
 * Two sources are combined: the activity-started counter (exact once installed) and the
 * process importance (covers an activity that was already running when we installed).
 */
object AppVisibility {
    private val started = AtomicInteger(0)
    @Volatile private var installed = false

    val isVisible: Boolean
        get() = started.get() > 0 || processInForeground()

    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext as? Application ?: return
        synchronized(this) {
            if (installed) return
            installed = true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) { started.incrementAndGet() }
                override fun onActivityStopped(activity: Activity) { started.updateAndGet { (it - 1).coerceAtLeast(0) } }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }
    }

    private fun processInForeground(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)
}
