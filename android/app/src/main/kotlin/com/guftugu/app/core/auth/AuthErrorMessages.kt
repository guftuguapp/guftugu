package com.guftugu.app.core.auth

import android.content.Context
import com.guftugu.app.R
import com.guftugu.app.protocol.ErrorCode

/** Warm, human wording for every [AuthError] (used by the inline banners on Join / Enroll / Unlock). */
fun AuthError.userMessage(context: Context): String = when (this) {
    AuthError.InviteInvalid -> context.getString(R.string.auth_err_invite_invalid)
    AuthError.InviteExpired -> context.getString(R.string.auth_err_invite_expired)
    is AuthError.InvalidRequest -> context.getString(R.string.auth_err_invalid_request, detail.ifBlank { "?" })
    is AuthError.ServerIncompatible -> context.getString(R.string.auth_err_server_incompatible)
    AuthError.NotEnrolled -> context.getString(R.string.auth_err_not_enrolled)
    AuthError.Cancelled -> context.getString(R.string.auth_err_cancelled)
    AuthError.NoBiometrics -> context.getString(R.string.auth_err_no_biometrics)
    AuthError.BiometricInvalidated -> context.getString(R.string.auth_err_biometric_invalidated)
    is AuthError.BiometricFailed -> context.getString(R.string.auth_err_biometric_failed, detail.ifBlank { code.toString() })
    AuthError.BadCredentials -> context.getString(R.string.auth_err_bad_credentials)
    is AuthError.PasswordLocked -> context.getString(R.string.auth_err_password_locked, minutes)
    is AuthError.Revoked -> context.getString(
        if (reason == ErrorCode.USER_DISABLED) R.string.auth_err_revoked_user else R.string.auth_err_revoked_device,
    )
    AuthError.ChallengeFailed -> context.getString(R.string.auth_err_challenge_failed)
    AuthError.NeedsNetwork -> context.getString(R.string.auth_err_needs_network)
    is AuthError.Network -> context.getString(R.string.auth_err_network)
    AuthError.RateLimited -> context.getString(R.string.auth_err_rate_limited)
    is AuthError.Server -> context.getString(R.string.auth_err_server, code)
    is AuthError.Local -> context.getString(R.string.auth_err_local, detail)
}

/** Message for any throwable coming out of the auth repository (non-[AuthError]s get a generic line). */
fun Throwable.authMessage(context: Context): String =
    (this as? AuthError)?.userMessage(context) ?: context.getString(R.string.auth_err_local, message ?: javaClass.simpleName)
