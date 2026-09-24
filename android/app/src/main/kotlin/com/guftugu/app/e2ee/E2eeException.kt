package com.guftugu.app.e2ee

/**
 * The one exception type that leaves [ConversationKeyManager]. Send paths catch it and decide
 * between "keep in the outbox and retry" ([isRetryable]) and "give up on this message".
 * Messages never contain key material or plaintext.
 */
class E2eeException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Reason {
        /** No usable key for this conversation yet (another member's phone has to wrap it for us). */
        NO_KEY,
        /** Not enrolled, or the identity keys are missing from this install. */
        IDENTITY_MISSING,
        /** Transport failure while talking to the server; retry later. */
        NETWORK,
        /** The server answered with an error other than 403/404 (those are handled quietly). */
        SERVER,
        /** GCM tag/AAD mismatch or malformed envelope: this envelope will never decrypt. */
        AUTHENTICATION_FAILED,
        /** The decrypted plaintext was not valid Content/Signal JSON. */
        MALFORMED,
        /** The envelope would exceed the server's 64 KiB limit. */
        TOO_LARGE,
        /** Signing, wrapping or Keystore operations failed locally. */
        CRYPTO,
        /** Anything unexpected (storage errors and the like). */
        INTERNAL,
    }

    /** True when trying again later may succeed (missing key, network, server hiccup). */
    val isRetryable: Boolean
        get() = when (reason) {
            Reason.NO_KEY, Reason.NETWORK, Reason.SERVER, Reason.INTERNAL -> true
            else -> false
        }

    override fun toString(): String = "E2eeException($reason: $message)"
}
