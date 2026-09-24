package com.guftugu.app.data.repo

import androidx.fragment.app.FragmentActivity
import com.guftugu.app.protocol.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A server discovered from an invite link / QR / manual entry (PROTOCOL §1, §2). */
data class ServerInfo(
    val apiUrl: String,
    val wsUrl: String,
    val name: String,
)

sealed class AuthState {
    /** No device keys registered anywhere: show JoinServerScreen. */
    data object NotEnrolled : AuthState()
    /** Enrolled; needs biometric/password unlock on this cold start. */
    data class Locked(val deviceId: String) : AuthState()
    /** Session token in memory; API and WebSocket may be used. */
    data class Unlocked(val session: Session) : AuthState()
    /** The server reported `device_revoked`/`user_disabled`: local data must be wiped, re-enrol. */
    data class Revoked(val reason: String) : AuthState()
}

/**
 * Device enrolment and user login (PROTOCOL §3–§4, SECURITY.md).
 * Owns the session token (memory + SecureStore) and the Keystore key lifecycle.
 */
interface AuthRepository {
    val state: StateFlow<AuthState>

    /** Registers this install with an invite/link code; creates Keystore + ECDH keys if missing. */
    suspend fun enroll(server: ServerInfo, code: String, displayName: String?, password: String?, deviceName: String): Result<Unit>

    /** Challenge → BiometricPrompt(`guftugu_auth`) → verify. */
    suspend fun unlockWithBiometric(activity: FragmentActivity): Result<Unit>

    /** Challenge → sign with `guftugu_device` + password → verify. */
    suspend fun unlockWithPassword(password: String): Result<Unit>

    /** Local biometric only (no server); lets the user read cached history offline. */
    suspend fun unlockOffline(activity: FragmentActivity): Result<Unit>

    suspend fun logout()

    /** Current bearer token, or null when locked/not enrolled. */
    fun sessionToken(): String?
}

/** Skeleton stand-in; replaced in the feature phase. */
class StubAuthRepository : AuthRepository {
    override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.NotEnrolled)
    override suspend fun enroll(server: ServerInfo, code: String, displayName: String?, password: String?, deviceName: String): Result<Unit> =
        throw NotImplementedError("implemented in feature phase")
    override suspend fun unlockWithBiometric(activity: FragmentActivity): Result<Unit> = throw NotImplementedError("implemented in feature phase")
    override suspend fun unlockWithPassword(password: String): Result<Unit> = throw NotImplementedError("implemented in feature phase")
    override suspend fun unlockOffline(activity: FragmentActivity): Result<Unit> = throw NotImplementedError("implemented in feature phase")
    override suspend fun logout() = throw NotImplementedError("implemented in feature phase")
    override fun sessionToken(): String? = null
}
