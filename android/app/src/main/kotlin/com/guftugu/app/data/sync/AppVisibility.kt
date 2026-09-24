package com.guftugu.app.data.sync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Is any Activity of the app started (visible to the user)? Decides whether an incoming message
 * becomes a notification. Counts started activities via [Application.ActivityLifecycleCallbacks]
 * (no `lifecycle-process` dependency). Call [install] once from `GuftuguApp.onCreate`.
 */
object AppVisibility {
    private val installed = AtomicBoolean(false)
    private var started = 0
    private val _visible = MutableStateFlow(false)

    /** True while at least one Activity is between onStart and onStop. */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    val isVisible: Boolean get() = _visible.value

    fun install(app: Application) {
        if (!installed.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1) _visible.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) _visible.value = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Test/preview hook. */
    internal fun setVisibleForTest(value: Boolean) { _visible.value = value }
}
