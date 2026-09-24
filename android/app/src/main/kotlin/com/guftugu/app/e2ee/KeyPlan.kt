package com.guftugu.app.e2ee

import com.guftugu.app.protocol.KeyRecipientsResponse
import com.guftugu.app.protocol.PublicDevice

/**
 * What to do after `GET /conversations/{id}/key-recipients` (PROTOCOL §7 "When to wrap/rotate").
 * Pure decision logic so it can be unit-tested without the network:
 *
 * 1. no current key, `keyRotationRequired`, or no listed device holds the current key → [Mint]
 * 2. we do not hold the current key → [AwaitKey] (another member's phone wraps it for us)
 * 3. some devices lack the current key → [Distribute] to them (never to ourselves)
 * 4. otherwise → [UpToDate]
 */
sealed class KeyPlan {
    /** Generate a fresh epoch and wrap it for every listed device (including our own). */
    data class Mint(val devices: List<PublicDevice>, val previousKeyId: String?) : KeyPlan()

    /** Wrap the current key for these devices. */
    data class Distribute(val keyId: String, val devices: List<PublicDevice>) : KeyPlan()

    /** We lack the current key; nothing to do until it is wrapped for us. */
    data class AwaitKey(val keyId: String) : KeyPlan()

    data object UpToDate : KeyPlan()

    companion object {
        fun of(recipients: KeyRecipientsResponse, myDeviceId: String, holdsCurrent: Boolean): KeyPlan {
            val devices = recipients.devices
            if (devices.isEmpty()) return UpToDate
            val current = recipients.currentKeyId
            val nobodyHasIt = devices.none { it.hasCurrentKey }
            if (current == null || recipients.keyRotationRequired || nobodyHasIt) {
                return Mint(devices.map { it.asPublic() }, current)
            }
            if (!holdsCurrent) return AwaitKey(current)
            val missing = devices.filter { !it.hasCurrentKey && it.deviceId != myDeviceId }
            return if (missing.isEmpty()) UpToDate else Distribute(current, missing.map { it.asPublic() })
        }
    }
}
