package com.guftugu.app.core.auth

/**
 * A deliberately simple strength estimate for the enrol form's thin gold/green bar.
 * Not a security control (the server enforces ≥ 6 characters); it only nudges people
 * towards longer, more varied passcodes. Pure Kotlin.
 */
object PasswordStrength {
    const val MIN_LENGTH = 6

    /** 0 = empty/too short … 4 = strong. */
    fun score(password: String): Int {
        if (password.length < MIN_LENGTH) return if (password.isEmpty()) 0 else 1
        var classes = 0
        if (password.any { it.isLowerCase() }) classes++
        if (password.any { it.isUpperCase() }) classes++
        if (password.any { it.isDigit() }) classes++
        if (password.any { !it.isLetterOrDigit() }) classes++
        var s = 1
        if (password.length >= 12) s++
        if (password.length >= 16) s++
        if (classes >= 3) s++
        if (classes >= 2 && password.length >= 20) s++
        // A long passphrase of plain words is fine too ("correct horse battery staple").
        if (password.count { it == ' ' } >= 3 && password.length >= 20) s = maxOf(s, 4)
        return s.coerceIn(1, 4)
    }

    fun meetsMinimum(password: String): Boolean = password.length >= MIN_LENGTH
}
