package com.guftugu.app.core.auth

/**
 * When to ask for the passcode — the owner's WhatsApp-style rule:
 *
 * - Joining never asks for one.
 * - About a week after the person's first activity (opening a chat or sending a message) they
 *   are asked to **create** a passcode. "Later" snoozes the ask for a day; after three weeks of
 *   use it can no longer be postponed (a passcode-less phone would otherwise stay unprotected).
 * - Once a passcode exists it is asked for again **every 30 days**, so it isn't forgotten.
 *
 * Pure Kotlin (JVM-testable); all times are epoch milliseconds.
 */
object PasscodePolicy {
    const val DAY_MS: Long = 24L * 60 * 60 * 1000
    const val CREATE_AFTER_MS: Long = 7 * DAY_MS
    const val MUST_CREATE_AFTER_MS: Long = 21 * DAY_MS
    const val SNOOZE_MS: Long = DAY_MS
    const val REENTER_EVERY_MS: Long = 30 * DAY_MS

    sealed interface Prompt {
        data object None : Prompt
        /** Create a first passcode; [canSnooze] = "Later" is offered. */
        data class Create(val canSnooze: Boolean) : Prompt
        /** Enter the existing passcode again (30-day reminder). */
        data object Reenter : Prompt
    }

    fun decide(
        now: Long,
        hasPassword: Boolean,
        firstActivityAt: Long,
        lastPasscodeAt: Long,
        snoozedUntil: Long,
    ): Prompt {
        if (!hasPassword) {
            if (firstActivityAt <= 0L || now < firstActivityAt + CREATE_AFTER_MS) return Prompt.None
            val canSnooze = now < firstActivityAt + MUST_CREATE_AFTER_MS
            if (canSnooze && now < snoozedUntil) return Prompt.None
            return Prompt.Create(canSnooze)
        }
        // A passcode made before this clock existed (or on the server by another means): the
        // caller starts the clock now instead of nagging immediately.
        if (lastPasscodeAt <= 0L) return Prompt.None
        return if (now >= lastPasscodeAt + REENTER_EVERY_MS) Prompt.Reenter else Prompt.None
    }
}
