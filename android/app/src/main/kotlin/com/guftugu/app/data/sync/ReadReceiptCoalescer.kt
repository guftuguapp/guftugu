package com.guftugu.app.data.sync

/**
 * Per-conversation coalescing of `PUT …/read`: at most one request per [windowMs] and only when
 * the latest message id moved past what was last sent. Pure state machine; the repository runs
 * the timer and the request.
 */
class ReadReceiptCoalescer(private val windowMs: Long = OutboxPolicy.READ_RECEIPT_WINDOW_MS) {

    sealed class Action {
        data class SendNow(val msgId: String) : Action()
        /** Start one timer; call [onTimerFired] when it elapses. Never returned while a timer is pending. */
        data class ScheduleIn(val delayMs: Long) : Action()
        data object Nothing : Action()
    }

    private var lastSentMsgId: String? = null
    private var lastSentAt: Long = Long.MIN_VALUE / 2
    private var target: String? = null
    private var scheduled = false

    @Synchronized
    fun onRead(latestMsgId: String, now: Long): Action {
        val sent = lastSentMsgId
        if (sent != null && sent >= latestMsgId) return Action.Nothing
        target = latestMsgId
        if (scheduled) return Action.Nothing
        val elapsed = now - lastSentAt
        if (elapsed >= windowMs) {
            lastSentAt = now
            lastSentMsgId = latestMsgId
            return Action.SendNow(latestMsgId)
        }
        scheduled = true
        return Action.ScheduleIn(windowMs - elapsed)
    }

    /** The scheduled timer elapsed: returns the id to send now, or null when nothing new is pending. */
    @Synchronized
    fun onTimerFired(now: Long): String? {
        scheduled = false
        val t = target ?: return null
        val sent = lastSentMsgId
        if (sent != null && sent >= t) return null
        lastSentAt = now
        lastSentMsgId = t
        return t
    }

    /** The request failed: forget it was sent so the next [onRead] retries. */
    @Synchronized
    fun onSendFailed(msgId: String) {
        if (lastSentMsgId == msgId) lastSentMsgId = null
    }
}
