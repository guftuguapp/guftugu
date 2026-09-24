package com.guftugu.app.ui.common

import com.guftugu.app.core.util.Time
import java.time.ZoneId

/**
 * Human "how long ago" stamps ("just now", "5 min ago", "yesterday", "12 Mar 2026").
 * Pure JVM so it is unit-testable; the labels come from string resources at the call site.
 */
data class RelativeTimeLabels(
    val justNow: String = "just now",
    /** `%d` (or Android's `%1$d`) is replaced by the minute count. */
    val minutesAgo: String = "%d min ago",
    /** `%d` (or Android's `%1$d`) is replaced by the hour count. */
    val hoursAgo: String = "%d h ago",
    val yesterday: String = "yesterday",
) {
    companion object {
        val DEFAULT = RelativeTimeLabels()
    }
}

object RelativeTime {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** Anything within this window counts as "online" for presence subtitles. */
    const val ONLINE_WINDOW_MS = 2 * MINUTE

    fun isOnline(lastSeenMs: Long?, nowMs: Long = Time.nowMs()): Boolean =
        lastSeenMs != null && nowMs - lastSeenMs in 0 until ONLINE_WINDOW_MS

    /**
     * Relative stamp for [thenMs] as seen from [nowMs]. Future timestamps (clock skew) read as "just now".
     */
    fun format(
        thenMs: Long,
        nowMs: Long = Time.nowMs(),
        labels: RelativeTimeLabels = RelativeTimeLabels.DEFAULT,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val delta = nowMs - thenMs
        return when {
            delta < MINUTE -> labels.justNow
            delta < HOUR -> labels.minutesAgo.withCount(delta / MINUTE)
            Time.isSameDay(thenMs, nowMs, zone) -> labels.hoursAgo.withCount(delta / HOUR)
            Time.toLocalDate(thenMs, zone) == Time.toLocalDate(nowMs, zone).minusDays(1) -> labels.yesterday
            else -> Time.formatDayLabel(thenMs, nowMs, zone)
        }
    }

    private fun String.withCount(n: Long): String = replace("%1\$d", n.toString()).replace("%d", n.toString())
}
