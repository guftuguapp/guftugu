package com.guftugu.app.e2ee

import com.guftugu.app.protocol.KeyRecipient
import com.guftugu.app.protocol.KeyRecipientsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPlanTest {
    private fun dev(id: String, has: Boolean) = KeyRecipient(
        deviceId = id, userId = "u_$id", name = id, devicePublicKey = "pk", encryptionPublicKey = "ek",
        enrolledAt = 0L, hasCurrentKey = has,
    )

    private val me = dev("d_me", true)

    @Test
    fun `no current key means mint for everyone`() {
        val r = KeyRecipientsResponse(currentKeyId = null, keyRotationRequired = false, devices = listOf(me, dev("d_a", false)))
        val plan = KeyPlan.of(r, "d_me", holdsCurrent = false)
        assertTrue(plan is KeyPlan.Mint)
        assertEquals(listOf("d_me", "d_a"), (plan as KeyPlan.Mint).devices.map { it.deviceId })
        assertEquals(null, plan.previousKeyId)
    }

    @Test
    fun `rotation required means mint even when we hold the current key`() {
        val r = KeyRecipientsResponse(currentKeyId = "x_1", keyRotationRequired = true, devices = listOf(me, dev("d_a", true)))
        val plan = KeyPlan.of(r, "d_me", holdsCurrent = true)
        assertEquals(KeyPlan.Mint(r.devices.map { it.asPublic() }, "x_1"), plan)
    }

    @Test
    fun `nobody holding the current key means mint rather than waiting forever`() {
        val r = KeyRecipientsResponse(currentKeyId = "x_1", keyRotationRequired = false, devices = listOf(dev("d_me", false), dev("d_a", false)))
        assertTrue(KeyPlan.of(r, "d_me", holdsCurrent = false) is KeyPlan.Mint)
    }

    @Test
    fun `missing the current key that others hold means wait`() {
        val r = KeyRecipientsResponse(currentKeyId = "x_1", keyRotationRequired = false, devices = listOf(dev("d_me", false), dev("d_a", true)))
        assertEquals(KeyPlan.AwaitKey("x_1"), KeyPlan.of(r, "d_me", holdsCurrent = false))
    }

    @Test
    fun `holding the key means distribute to devices lacking it but never to myself`() {
        val r = KeyRecipientsResponse(
            currentKeyId = "x_1", keyRotationRequired = false,
            devices = listOf(dev("d_me", false), dev("d_a", true), dev("d_b", false), dev("d_c", false)),
        )
        val plan = KeyPlan.of(r, "d_me", holdsCurrent = true)
        assertTrue(plan is KeyPlan.Distribute)
        assertEquals("x_1", (plan as KeyPlan.Distribute).keyId)
        assertEquals(listOf("d_b", "d_c"), plan.devices.map { it.deviceId })
    }

    @Test
    fun `everyone has it means nothing to do`() {
        val r = KeyRecipientsResponse(currentKeyId = "x_1", keyRotationRequired = false, devices = listOf(me, dev("d_a", true)))
        assertEquals(KeyPlan.UpToDate, KeyPlan.of(r, "d_me", holdsCurrent = true))
        assertEquals(KeyPlan.UpToDate, KeyPlan.of(KeyRecipientsResponse(devices = emptyList()), "d_me", holdsCurrent = false))
    }
}
