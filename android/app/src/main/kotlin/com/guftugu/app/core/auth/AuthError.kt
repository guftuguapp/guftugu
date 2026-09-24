package com.guftugu.app.core.auth

import com.guftugu.app.protocol.ErrorCode

/**
 * Typed failures of enrolment / unlock (PROTOCOL §3–§4). Every `Result.failure` from the auth
 * repository carries one of these so screens can render a precise, warm message instead of a
 * raw error string. Pure Kotlin: no Android imports, JVM-testable.
 */
sealed class AuthError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    // ---------- enrolment ----------

    /** The invite code does not exist or was already used. */
    data object InviteInvalid : AuthError("invite_invalid")

    /** The invite code is past its expiry. */
    data object InviteExpired : AuthError("invite_expired")

    /** The server rejected the request shape (e.g. password too short). [detail] is the server's text. */
    data class InvalidRequest(val detail: String) : AuthError("invalid_request: $detail")

    /** `.well-known/guftugu` is missing, not JSON, or speaks another protocol version. */
    data class ServerIncompatible(val protocolVersion: Int?) : AuthError("server_incompatible")

    /** The phone is not enrolled anywhere (no deviceId) — the caller should show Join. */
    data object NotEnrolled : AuthError("not_enrolled")

    // ---------- biometric ----------

    /** The user dismissed the prompt or tapped "Use password". */
    data object Cancelled : AuthError("cancelled")

    /** No `guftugu_auth` key on this phone (enrolled password-only, or the key was cleared). */
    data object NoBiometrics : AuthError("no_biometrics")

    /** Biometrics changed: `guftugu_auth` is permanently invalid and has been cleared. Use the password. */
    data object BiometricInvalidated : AuthError("biometric_invalidated")

    /** Lockout, hardware unavailable, … [code] is the `BiometricPrompt.ERROR_*` value. */
    data class BiometricFailed(val code: Int, val detail: String) : AuthError("biometric_failed($code): $detail")

    // ---------- password ----------

    data object BadCredentials : AuthError("bad_credentials")

    /** Too many wrong passwords; try again in [minutes]. */
    data class PasswordLocked(val minutes: Int) : AuthError("password_locked($minutes)")

    // ---------- session / server ----------

    /** `device_revoked` or `user_disabled`: local data must be wiped, re-enrol. [reason] is the code. */
    data class Revoked(val reason: String) : AuthError("revoked: $reason")

    /** The nonce was stale or the signature did not verify — simply retry. */
    data object ChallengeFailed : AuthError("challenge_failed")

    /** Offline unlock succeeded locally but no stored session exists: a network unlock is required. */
    data object NeedsNetwork : AuthError("needs_network")

    /** Transport failure (DNS, TLS, timeout, no connectivity). */
    data class Network(override val cause: Throwable? = null) : AuthError("network", cause)

    data object RateLimited : AuthError("rate_limited")

    /** Anything else the server said. */
    data class Server(val code: String, val status: Int, val detail: String) : AuthError("$status $code: $detail")

    /** Unexpected local failure (keystore, serialization…). */
    data class Local(val detail: String, override val cause: Throwable? = null) : AuthError("local: $detail", cause)

    /** True when showing the password field is the sensible next step. */
    val suggestsPassword: Boolean
        get() = this is NoBiometrics || this is BiometricInvalidated || this is BiometricFailed

    /** True when the failure is transient and a plain retry is reasonable. */
    val isRetryable: Boolean
        get() = this is Network || this is ChallengeFailed || this is RateLimited || (this is Server && status >= 500)

    companion object {
        /** Default lockout window (PROTOCOL §4: "5 failures → password_locked for 15 min"). */
        const val DEFAULT_LOCK_MINUTES = 15

        /**
         * Maps a server error (`code` from the error body, HTTP [status], server [message]) to a
         * typed [AuthError]. A transport failure is `status == 0` / code `network`.
         */
        fun fromApi(code: String, status: Int, message: String, cause: Throwable? = null): AuthError = when (code) {
            ErrorCode.NETWORK -> Network(cause)
            ErrorCode.INVITE_INVALID -> InviteInvalid
            ErrorCode.INVITE_EXPIRED -> InviteExpired
            ErrorCode.INVALID_REQUEST -> InvalidRequest(message)
            ErrorCode.UPGRADE_REQUIRED -> ServerIncompatible(null)
            ErrorCode.BAD_CREDENTIALS -> BadCredentials
            ErrorCode.PASSWORD_LOCKED -> PasswordLocked(parseMinutes(message))
            ErrorCode.DEVICE_REVOKED, ErrorCode.USER_DISABLED -> Revoked(code)
            ErrorCode.CHALLENGE_INVALID, ErrorCode.BAD_SIGNATURE -> ChallengeFailed
            ErrorCode.RATE_LIMITED -> RateLimited
            else -> if (status == 0) Network(cause) else Server(code, status, message)
        }

        /**
         * Pulls a "minutes remaining" figure out of a free-form server message such as
         * `"too many attempts, try again in 12 minutes"`. Falls back to [DEFAULT_LOCK_MINUTES].
         */
        fun parseMinutes(message: String?): Int {
            if (message.isNullOrBlank()) return DEFAULT_LOCK_MINUTES
            val digits = StringBuilder()
            var found: Int? = null
            for (ch in message) {
                if (ch.isDigit()) {
                    digits.append(ch)
                } else if (digits.isNotEmpty()) {
                    found = digits.toString().toIntOrNull(); break
                }
            }
            if (found == null && digits.isNotEmpty()) found = digits.toString().toIntOrNull()
            return found?.takeIf { it in 1..1440 } ?: DEFAULT_LOCK_MINUTES
        }
    }
}
