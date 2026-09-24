package com.guftugu.app.core.crypto

import com.guftugu.app.core.util.Base64Url
import com.guftugu.app.protocol.Content
import com.guftugu.app.protocol.ContentType
import com.guftugu.app.protocol.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContentCipherTest {
    private val key = Ecies.newConversationKey()

    @Test
    fun `round trip with json content`() {
        val content = Content(ContentType.TEXT, text = "hi ✓ گفتگو", replyTo = "m_1")
        val json = ProtocolJson.encodeToString(Content.serializer(), content)
        val aad = ContentCipher.aad("c_1", "u_1", "client-1")
        val env = ContentCipher.encrypt(key, "x_1", json, aad)
        assertEquals(1, env.v)
        assertEquals("x_1", env.keyId)
        assertEquals(12, Base64Url.decode(env.iv).size)
        assertEquals(json.toByteArray().size + 16, Base64Url.decode(env.ct).size)
        val plain = ContentCipher.decrypt(key, env, aad)
        assertEquals(content, ProtocolJson.decodeFromString(Content.serializer(), plain))
    }

    @Test
    fun `aad binds conversation sender and client id`() {
        val env = ContentCipher.encrypt(key, "x_1", "{}", ContentCipher.aad("c_1", "u_1", "id"))
        assertThrows(ContentCipher.DecryptException::class.java) {
            ContentCipher.decrypt(key, env, ContentCipher.aad("c_2", "u_1", "id"))
        }
        assertThrows(ContentCipher.DecryptException::class.java) {
            ContentCipher.decrypt(key, env, ContentCipher.aad("c_1", "u_2", "id"))
        }
    }

    @Test
    fun `wrong key or tampered ciphertext fails`() {
        val aad = ContentCipher.aad("c_1", "u_1", "id")
        val env = ContentCipher.encrypt(key, "x_1", "{\"type\":\"text\"}", aad)
        assertThrows(ContentCipher.DecryptException::class.java) { ContentCipher.decrypt(Ecies.newConversationKey(), env, aad) }
        val ct = Base64Url.decode(env.ct).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(ContentCipher.DecryptException::class.java) { ContentCipher.decrypt(key, env.copy(ct = Base64Url.encode(ct)), aad) }
    }
}
