package com.guftugu.app.ui.chats

import com.guftugu.app.domain.MessageBody
import com.guftugu.app.protocol.Attachment
import com.guftugu.app.protocol.CallInfo
import com.guftugu.app.protocol.CallOutcome
import com.guftugu.app.protocol.CallType
import com.guftugu.app.protocol.ContentType
import com.guftugu.app.protocol.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTextTest {
    private fun att(mime: String, name: String? = null, duration: Long? = null) = Attachment(
        key = "conv/c_1/x.bin", mime = mime, sizeBytes = 10, fileName = name, durationMs = duration,
        fileKey = "k", baseIv = "iv", sha256 = "h",
    )

    @Test
    fun `text gets a You prefix when mine and the sender name in groups`() {
        val body = MessageBody.Text("see you at 8")
        assertEquals("You: see you at 8", PreviewText.build(body, isMine = true))
        assertEquals("see you at 8", PreviewText.build(body, isMine = false))
        assertEquals("Ammi: see you at 8", PreviewText.build(body, isMine = false, senderName = "Ammi"))
        // mine wins over the sender name
        assertEquals("You: see you at 8", PreviewText.build(body, isMine = true, senderName = "Me"))
    }

    @Test
    fun `newlines and runs of whitespace collapse to one space`() {
        assertEquals("first line second", PreviewText.build(MessageBody.Text("  first line\n\n   second  "), false))
    }

    @Test
    fun `media previews use glyphs, captions and durations`() {
        assertEquals("📷 Photo", PreviewText.build(MessageBody.Media(ContentType.IMAGE, att("image/jpeg"), null), false))
        assertEquals("You: 📷 at the beach", PreviewText.build(MessageBody.Media(ContentType.IMAGE, att("image/jpeg"), "at the beach"), true))
        assertEquals("🎬 Video", PreviewText.build(MessageBody.Media(ContentType.VIDEO, att("video/mp4"), ""), false))
        assertEquals("🎤 Voice note 0:12", PreviewText.build(MessageBody.Media(ContentType.AUDIO, att("audio/mp4", duration = 12_400), null), false))
        assertEquals("🎤 Voice note", PreviewText.build(MessageBody.Media(ContentType.AUDIO, att("audio/mp4"), null), false))
        assertEquals("📎 taxes.pdf", PreviewText.build(MessageBody.Media(ContentType.FILE, att("application/pdf", name = "taxes.pdf"), null), false))
        assertEquals("📎 File", PreviewText.build(MessageBody.Media(ContentType.FILE, att("application/octet-stream"), null), false))
    }

    @Test
    fun `call logs never get a You prefix`() {
        fun call(outcome: String, type: String = CallType.AUDIO, duration: Long? = null) =
            MessageBody.CallLog(CallInfo("k_1", type, outcome, duration))
        assertEquals("📞 Missed call", PreviewText.build(call(CallOutcome.MISSED), isMine = true))
        assertEquals("📞 Audio call · 3:21", PreviewText.build(call(CallOutcome.ANSWERED, duration = 201_000), false))
        assertEquals("📹 Video call", PreviewText.build(call(CallOutcome.ANSWERED, CallType.VIDEO), false))
        assertEquals("📹 Call declined", PreviewText.build(call(CallOutcome.REJECTED, CallType.VIDEO), false))
        assertEquals("📞 Cancelled call", PreviewText.build(call(CallOutcome.CANCELLED), false))
        assertEquals("📞 Call not reached", PreviewText.build(call(CallOutcome.UNREACHABLE), false))
    }

    @Test
    fun `system, locked and deleted bodies`() {
        assertEquals("System message", PreviewText.build(MessageBody.System(SystemInfo("member_added", listOf("u_2"))), true))
        assertEquals("Ammi renamed the group", PreviewText.build(MessageBody.System(SystemInfo("renamed", text = "Ammi renamed the group")), false))
        assertEquals("🔒 Waiting for keys…", PreviewText.build(MessageBody.Locked, false))
        assertEquals("This message was deleted", PreviewText.build(MessageBody.Deleted, true))
    }

    @Test
    fun `custom labels are honoured`() {
        val labels = PreviewLabels(you = "Tum", photo = "Tasveer")
        assertEquals("Tum: 📷 Tasveer", PreviewText.build(MessageBody.Media(ContentType.IMAGE, att("image/jpeg"), null), true, labels = labels))
    }

    @Test
    fun `very long text is bounded`() {
        val long = "a".repeat(1000)
        assertEquals(140, PreviewText.build(MessageBody.Text(long), false).length)
    }
}
