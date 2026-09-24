package com.guftugu.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoTest {
    @Test
    fun `server events decode by type and unknown types do not crash`() {
        val hello = ProtocolJson.decodeServerEvent("""{"type":"hello","userId":"u_1","deviceId":"d_1","connectionId":"conn","serverTime":1}""")
        assertEquals(ServerEvent.Hello("u_1", "d_1", "conn", 1), hello)

        val keys = ProtocolJson.decodeServerEvent("""{"type":"conversation.keys","convId":"c_1","keyId":"x_1","extra":true}""")
        assertEquals(ServerEvent.ConversationKeys("c_1", "x_1"), keys)

        val signal = ProtocolJson.decodeServerEvent("""{"type":"call.signal","callId":"k_1","fromDeviceId":"d_2","envelope":{"v":1,"keyId":"x_1","iv":"AA","ct":"BB"}}""")
        assertTrue(signal is ServerEvent.CallSignal)

        val unknown = ProtocolJson.decodeServerEvent("""{"type":"something.new","foo":1}""")
        assertTrue(unknown is ServerEvent.Unknown)
        assertEquals("something.new", (unknown as ServerEvent.Unknown).type)

        val malformedKnown = ProtocolJson.decodeServerEvent("""{"type":"hello"}""")
        assertTrue(malformedKnown is ServerEvent.Unknown)

        val noType = ProtocolJson.decodeServerEvent("""{"foo":1}""")
        assertTrue(noType is ServerEvent.Unknown)
    }

    @Test
    fun `server events encode with the type discriminator`() {
        val text = ProtocolJson.encodeToString(ServerEventSerializer, ServerEvent.Pong(5))
        assertEquals("""{"type":"pong","serverTime":5}""", text)
    }

    @Test
    fun `client events encode with type`() {
        assertEquals("""{"type":"hello"}""", ProtocolJson.encodeClientEvent(ClientEvent.Hello))
        assertEquals("""{"type":"ping"}""", ProtocolJson.encodeClientEvent(ClientEvent.Ping))
        assertEquals("""{"type":"typing","convId":"c_1"}""", ProtocolJson.encodeClientEvent(ClientEvent.Typing("c_1")))
        val sig = ProtocolJson.encodeClientEvent(ClientEvent.CallSignal("k_1", Envelope(keyId = "x", iv = "i", ct = "c")))
        assertEquals("""{"type":"call.signal","callId":"k_1","envelope":{"v":1,"keyId":"x","iv":"i","ct":"c"}}""", sig)
    }

    @Test
    fun `signals are discriminated by kind`() {
        val offer = ProtocolJson.encodeToString(Signal.serializer(), Signal.Offer("sdp"))
        assertEquals("""{"kind":"offer","sdp":"sdp"}""", offer)
        val ice = ProtocolJson.decodeFromString(Signal.serializer(), """{"kind":"ice","candidate":"c","sdpMid":"0","sdpMLineIndex":0}""")
        assertEquals(Signal.Ice("c", "0", 0), ice)
    }

    @Test
    fun `nullable fields default and nulls are omitted`() {
        val msg = ProtocolJson.decodeFromString(Message.serializer(), """{"msgId":"m","convId":"c","senderId":"u","kind":"e2e","sentAt":1,"createdAt":2}""")
        assertEquals(null, msg.envelope)
        assertEquals(null, msg.clientId)
        val out = ProtocolJson.encodeToString(EnrollRequest.serializer(), EnrollRequest(inviteCode = "GFT", devicePublicKey = "a", encryptionPublicKey = "b", device = DeviceInfo(name = "n")))
        assertEquals("""{"inviteCode":"GFT","devicePublicKey":"a","encryptionPublicKey":"b","device":{"name":"n"}}""", out)
        val content = ProtocolJson.decodeFromString(Content.serializer(), """{"type":"image","attachment":{"key":"k","mime":"image/jpeg","sizeBytes":10,"enc":"aes-256-gcm-chunked-v1","fileKey":"f","baseIv":"b","chunkSize":1048576,"sha256":"s"}}""")
        assertEquals(ContentType.IMAGE, content.type)
    }
}
