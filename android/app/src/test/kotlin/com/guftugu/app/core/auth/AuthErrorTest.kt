package com.guftugu.app.core.auth

import com.guftugu.app.data.api.ApiException
import com.guftugu.app.protocol.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthErrorTest {

    private fun map(e: ApiException): AuthError = AuthError.fromApi(e.code, e.status, e.message ?: "", e)

    @Test
    fun `enrolment codes map to typed errors`() {
        assertSame(AuthError.InviteInvalid, map(ApiException(ErrorCode.INVITE_INVALID, 400, "invite not found")))
        assertSame(AuthError.InviteExpired, map(ApiException(ErrorCode.INVITE_EXPIRED, 400, "expired")))
        val invalid = map(ApiException(ErrorCode.INVALID_REQUEST, 400, "password must be at least 8 characters"))
        assertTrue(invalid is AuthError.InvalidRequest)
        assertEquals("password must be at least 8 characters", (invalid as AuthError.InvalidRequest).detail)
        assertTrue(map(ApiException(ErrorCode.UPGRADE_REQUIRED, 426, "")) is AuthError.ServerIncompatible)
    }

    @Test
    fun `login codes map to typed errors`() {
        assertSame(AuthError.BadCredentials, map(ApiException(ErrorCode.BAD_CREDENTIALS, 401, "bad")))
        assertSame(AuthError.ChallengeFailed, map(ApiException(ErrorCode.CHALLENGE_INVALID, 400, "stale nonce")))
        assertSame(AuthError.ChallengeFailed, map(ApiException(ErrorCode.BAD_SIGNATURE, 401, "sig")))
        assertEquals(AuthError.Revoked(ErrorCode.DEVICE_REVOKED), map(ApiException(ErrorCode.DEVICE_REVOKED, 403, "revoked")))
        assertEquals(AuthError.Revoked(ErrorCode.USER_DISABLED), map(ApiException(ErrorCode.USER_DISABLED, 403, "disabled")))
        assertSame(AuthError.RateLimited, map(ApiException(ErrorCode.RATE_LIMITED, 429, "slow down")))
    }

    @Test
    fun `password_locked carries the minutes from the message or the default`() {
        assertEquals(AuthError.PasswordLocked(12), map(ApiException(ErrorCode.PASSWORD_LOCKED, 423, "too many attempts, try again in 12 minutes")))
        assertEquals(AuthError.PasswordLocked(15), map(ApiException(ErrorCode.PASSWORD_LOCKED, 423, "locked")))
        assertEquals(AuthError.PasswordLocked(15), map(ApiException(ErrorCode.PASSWORD_LOCKED, 423, "")))
        assertEquals(AuthError.PasswordLocked(15), map(ApiException(ErrorCode.PASSWORD_LOCKED, 423, "locked for 99999 minutes")))
        assertEquals(3, AuthError.parseMinutes("3 min left"))
        assertEquals(15, AuthError.parseMinutes(null))
        assertEquals(15, AuthError.parseMinutes("0 minutes"))
    }

    @Test
    fun `transport failures become Network and unknown codes become Server`() {
        val cause = java.io.IOException("unreachable")
        val api = ApiException(ErrorCode.NETWORK, 0, "network error", cause)
        val net = map(api)
        assertTrue(net is AuthError.Network)
        // the ApiException is kept as the cause (and it wraps the socket error)
        assertSame(api, (net as AuthError.Network).cause)
        assertSame(cause, net.cause?.cause)
        // status 0 with any odd code is still a transport problem
        assertTrue(map(ApiException("not_configured", 0, "no server configured")) is AuthError.Network)

        val srv = map(ApiException(ErrorCode.INTERNAL, 500, "boom"))
        assertEquals(AuthError.Server(ErrorCode.INTERNAL, 500, "boom"), srv)
        assertTrue(srv.isRetryable)
        assertFalse(map(ApiException(ErrorCode.NOT_FOUND, 404, "nope")).isRetryable)
    }

    @Test
    fun `ui hints`() {
        assertTrue(AuthError.NoBiometrics.suggestsPassword)
        assertTrue(AuthError.BiometricInvalidated.suggestsPassword)
        assertTrue(AuthError.BiometricFailed(7, "lockout").suggestsPassword)
        assertFalse(AuthError.BadCredentials.suggestsPassword)
        assertTrue(AuthError.Network().isRetryable)
        assertTrue(AuthError.ChallengeFailed.isRetryable)
        assertFalse(AuthError.InviteExpired.isRetryable)
        // every AuthError is a Throwable usable in Result.failure
        val r: Result<Unit> = Result.failure(AuthError.Cancelled)
        assertSame(AuthError.Cancelled, r.exceptionOrNull())
    }
}
