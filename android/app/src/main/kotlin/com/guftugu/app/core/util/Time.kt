package com.guftugu.app.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Wall-clock helpers. All protocol timestamps are epoch milliseconds (UTC). */
object Time {
    fun nowMs(): Long = System.currentTimeMillis()

    /** Skew between server and phone clocks, updated from `hello`/`pong`/well-known `serverTime`. */
    @Volatile
    var serverOffsetMs: Long = 0L
        private set

    fun observeServerTime(serverTimeMs: Long, localNowMs: Long = nowMs()) {
        serverOffsetMs = serverTimeMs - localNowMs
    }

    fun serverNowMs(): Long = nowMs() + serverOffsetMs

    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())

    fun toLocalDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    fun isSameDay(a: Long, b: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        toLocalDate(a, zone) == toLocalDate(b, zone)

    /** "14:05" */
    fun formatTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(timeFmt)

    /** "Today" / "Yesterday" / "Monday" / "12 Mar 2026" */
    fun formatDayLabel(epochMs: Long, nowMs: Long = nowMs(), zone: ZoneId = ZoneId.systemDefault()): String {
        val d = toLocalDate(epochMs, zone)
        val today = toLocalDate(nowMs, zone)
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.isAfter(today.minusDays(7)) -> d.format(dayFmt)
            else -> d.format(dateFmt)
        }
    }

    /** Chat-list style stamp: time if today, weekday if this week, else date. */
    fun formatShort(epochMs: Long, nowMs: Long = nowMs(), zone: ZoneId = ZoneId.systemDefault()): String =
        if (isSameDay(epochMs, nowMs, zone)) formatTime(epochMs, zone) else formatDayLabel(epochMs, nowMs, zone)

    /** "0:07" / "12:34" / "1:02:03" for media/call durations. */
    fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}
