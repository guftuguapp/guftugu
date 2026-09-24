package com.guftugu.app.ui.chats

import com.guftugu.app.core.util.Time
import com.guftugu.app.domain.MessageBody
import com.guftugu.app.protocol.CallOutcome
import com.guftugu.app.protocol.CallType
import com.guftugu.app.protocol.ContentType

/**
 * Labels for [PreviewText]; English defaults keep the builder a pure, testable function.
 * Screens pass a copy built from string resources.
 */
data class PreviewLabels(
    val you: String = "You",
    val photo: String = "Photo",
    val video: String = "Video",
    val voiceNote: String = "Voice note",
    val file: String = "File",
    val missedCall: String = "Missed call",
    val audioCall: String = "Audio call",
    val videoCall: String = "Video call",
    val callRejected: String = "Call declined",
    val callCancelled: String = "Cancelled call",
    val callUnreachable: String = "Call not reached",
    val deleted: String = "This message was deleted",
    val locked: String = "Waiting for keys…",
    val system: String = "System message",
) {
    companion object {
        val DEFAULT = PreviewLabels()
    }
}

/**
 * One-line preview of a message for the chats list, reply quotes and notifications:
 * "You: see you at 8", "📷 Photo", "🎤 Voice note 0:12", "📞 Missed call"…
 * Pure JVM (no Android types) so it is unit-tested.
 */
object PreviewText {
    const val PHOTO = "📷"
    const val VIDEO = "🎬"
    const val VOICE = "🎤"
    const val FILE = "📎"
    const val CALL = "📞"
    const val VIDEO_CALL = "📹"
    const val LOCK = "🔒"

    /** Longest preview kept; the row ellipsises anyway, this just bounds the work. */
    private const val MAX_CHARS = 140

    /**
     * @param isMine prefixes "You: " on text/media.
     * @param senderName group chats pass the sender's name so their messages read "Ammi: …"; null for direct chats.
     */
    fun build(body: MessageBody, isMine: Boolean, senderName: String? = null, labels: PreviewLabels = PreviewLabels.DEFAULT): String {
        val core = when (body) {
            is MessageBody.Text -> collapse(body.text)
            is MessageBody.Media -> media(body, labels)
            is MessageBody.CallLog -> return call(body, labels)
            is MessageBody.System -> return body.info.text?.takeIf { it.isNotBlank() }?.let(::collapse) ?: labels.system
            MessageBody.Locked -> return "$LOCK ${labels.locked}"
            MessageBody.Deleted -> return labels.deleted
        }
        val prefix = when {
            isMine -> labels.you
            senderName != null -> senderName
            else -> null
        }
        return if (prefix == null) core else "$prefix: $core"
    }

    private fun media(body: MessageBody.Media, labels: PreviewLabels): String {
        val caption = body.caption?.takeIf { it.isNotBlank() }?.let(::collapse)
        return when (body.type) {
            ContentType.IMAGE -> "$PHOTO ${caption ?: labels.photo}"
            ContentType.VIDEO -> "$VIDEO ${caption ?: labels.video}"
            ContentType.AUDIO -> {
                val d = body.attachment.durationMs?.takeIf { it > 0 }?.let { " " + Time.formatDuration(it) }.orEmpty()
                "$VOICE ${labels.voiceNote}$d"
            }
            ContentType.FILE -> "$FILE ${caption ?: body.attachment.fileName?.takeIf { it.isNotBlank() } ?: labels.file}"
            ContentType.TEXT -> caption ?: ""
        }
    }

    private fun call(body: MessageBody.CallLog, labels: PreviewLabels): String {
        val info = body.info
        val video = info.type == CallType.VIDEO
        val glyph = if (video) VIDEO_CALL else CALL
        val kind = if (video) labels.videoCall else labels.audioCall
        return when (info.outcome) {
            CallOutcome.ANSWERED -> {
                val d = info.durationMs?.takeIf { it > 0 }?.let { " · " + Time.formatDuration(it) }.orEmpty()
                "$glyph $kind$d"
            }
            CallOutcome.MISSED -> "$glyph ${labels.missedCall}"
            CallOutcome.REJECTED -> "$glyph ${labels.callRejected}"
            CallOutcome.CANCELLED -> "$glyph ${labels.callCancelled}"
            CallOutcome.UNREACHABLE -> "$glyph ${labels.callUnreachable}"
            else -> "$glyph $kind"
        }
    }

    /** Newlines and runs of whitespace become one space; long text is cut (the row ellipsises). */
    fun collapse(text: String): String {
        val sb = StringBuilder(minOf(text.length, MAX_CHARS))
        var pendingSpace = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                pendingSpace = sb.isNotEmpty()
            } else {
                if (pendingSpace) { sb.append(' '); pendingSpace = false }
                sb.append(ch)
                if (sb.length >= MAX_CHARS) break
            }
        }
        return sb.toString()
    }
}
