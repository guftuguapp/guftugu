package com.guftugu.app.core.auth

/**
 * Invite / link codes look like `GFT-7K3M-Q9XD` (SECURITY.md: 8 Crockford-base32 characters).
 * People type them in every imaginable way — lower case, without dashes, with spaces — so the
 * Join screen normalises before validating. Pure Kotlin, JVM-testable.
 */
object InviteCodes {
    const val PREFIX = "GFT"
    private const val BODY_LENGTH = 8

    /** Crockford base32 alphabet (no I, L, O, U). */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * Upper-cases, strips whitespace/dashes and re-inserts the canonical `GFT-XXXX-XXXX` dashes.
     * Returns the cleaned text even when it is not a valid code (so the field shows what was typed);
     * use [isValid] to decide.
     */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val compact = buildString(raw.length) {
            for (ch in raw) if (!ch.isWhitespace() && ch != '-' && ch != '_' && ch != '.') append(ch.uppercaseChar())
        }
        val body = if (compact.startsWith(PREFIX)) compact.substring(PREFIX.length) else compact
        if (body.length != BODY_LENGTH) return compact
        return "$PREFIX-${body.substring(0, 4)}-${body.substring(4)}"
    }

    /** True for a canonical `GFT-XXXX-XXXX` code (after [normalize]). */
    fun isValid(code: String): Boolean {
        if (code.length != PREFIX.length + 1 + 4 + 1 + 4) return false
        if (!code.startsWith("$PREFIX-") || code[8] != '-') return false
        for (i in code.indices) {
            if (i < 4 || i == 8) continue
            if (ALPHABET.indexOf(code[i]) < 0) return false
        }
        return true
    }
}
