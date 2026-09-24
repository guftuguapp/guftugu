package com.guftugu.app.core.auth

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.guftugu.app.R
import com.guftugu.app.core.crypto.KeystoreKeys
import java.security.Signature
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * BiometricPrompt + `CryptoObject(Signature)` around the `guftugu_auth` key.
 * The returned [Signature] is authorised for exactly one `sign()` (SECURITY.md "Login").
 */
object BiometricGate {

    sealed class Outcome {
        /** Prompt succeeded; the signature is ready to sign one message. */
        data class Authenticated(val signature: Signature) : Outcome()
        /** The user chose the negative button ("Use password") or dismissed the prompt. */
        data object Cancelled : Outcome()
        /** Biometrics changed; `guftugu_auth` is gone for good — fall back to password, offer re-enrolment. */
        data object KeyInvalidated : Outcome()
        /** Any other error (lockout, hardware unavailable…). */
        data class Failed(val code: Int, val message: String) : Outcome()
    }

    /** What `BiometricManager` says about BIOMETRIC_STRONG on this phone. */
    enum class Availability {
        /** Hardware present and at least one strong biometric enrolled. */
        AVAILABLE,
        /** Hardware present, nothing enrolled yet (the user could enrol in system settings). */
        NONE_ENROLLED,
        /** No usable strong-biometric hardware (or a security update is required). */
        UNAVAILABLE,
    }

    /** Texts shown on the system prompt; `null` falls back to `strings.xml`. */
    data class PromptText(
        val title: String? = null,
        val subtitle: String? = null,
        val negative: String? = null,
    )

    fun availability(context: Context): Availability {
        when (BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> return Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> return Availability.NONE_ENROLLED
        }
        // Android 10 and older: androidx can only *guess* whether a biometric is "strong" (hidden
        // APIs / STATUS_UNKNOWN) and returns STATUS_UNKNOWN (-1) on Android 10 even with a fingerprint
        // enrolled (verified on an Android 10 emulator). Fingerprint readers on these releases unlock Keystore keys, so ask
        // the fingerprint service directly; the enrolment prompt then proves the key really works
        // (and falls back to a passcode if it doesn't).
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.Q) {
            legacyFingerprint(context)?.let { return it }
        }
        return Availability.UNAVAILABLE
    }

    @Suppress("DEPRECATION")
    private fun legacyFingerprint(context: Context): Availability? = runCatching {
        if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT)) return@runCatching null
        val fm = context.getSystemService(android.hardware.fingerprint.FingerprintManager::class.java) ?: return@runCatching null
        when {
            !fm.isHardwareDetected -> null
            fm.hasEnrolledFingerprints() -> Availability.AVAILABLE
            else -> Availability.NONE_ENROLLED
        }
    }.getOrNull()

    /** Whether a BIOMETRIC_STRONG authenticator is enrolled and usable right now. */
    fun canAuthenticate(context: Context): Boolean = availability(context) == Availability.AVAILABLE

    /**
     * Opens the phone's own "add a fingerprint" screen, falling back to Security settings and
     * then Settings on OEMs (e.g. Huawei EMUI) that don't handle the enrol intents.
     */
    fun openFingerprintSettings(context: Context) {
        val candidates = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                add(
                    android.content.Intent(android.provider.Settings.ACTION_BIOMETRIC_ENROLL)
                        .putExtra(android.provider.Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BiometricManager.Authenticators.BIOMETRIC_STRONG),
                )
            }
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= 28) add(android.content.Intent(android.provider.Settings.ACTION_FINGERPRINT_ENROLL))
            add(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
            add(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
        }
        for (intent in candidates) {
            val ok = runCatching {
                context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
    }

    /** Whether a BIOMETRIC_STRONG authenticator is enrolled and usable right now. */
    fun canAuthenticate(activity: FragmentActivity): Boolean = canAuthenticate(activity as Context)

    /** Shows the prompt bound to `guftugu_auth`. Must be called on the main thread's coroutine. */
    suspend fun authenticate(activity: FragmentActivity, text: PromptText = PromptText()): Outcome {
        val signature = try {
            KeystoreKeys.signatureFor(KeystoreKeys.AUTH)
        } catch (e: KeyPermanentlyInvalidatedException) {
            return Outcome.KeyInvalidated
        } catch (e: IllegalStateException) {
            return Outcome.Failed(-1, "biometric key missing")
        } catch (e: java.security.GeneralSecurityException) {
            return Outcome.Failed(-1, "biometric key unusable")
        }
        return suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val sig = result.cryptoObject?.signature
                    if (cont.isActive) cont.resume(if (sig != null) Outcome.Authenticated(sig) else Outcome.Failed(-1, "no crypto object"))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    val outcome = when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> Outcome.Cancelled
                        else -> Outcome.Failed(errorCode, errString.toString())
                    }
                    cont.resume(outcome)
                }

                override fun onAuthenticationFailed() {
                    // A non-matching finger: the prompt stays up; nothing to do.
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(text.title ?: activity.getString(R.string.biometric_title))
                .setSubtitle(text.subtitle ?: activity.getString(R.string.biometric_subtitle))
                .setNegativeButtonText(text.negative ?: activity.getString(R.string.biometric_negative))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
            cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        }
    }
}
