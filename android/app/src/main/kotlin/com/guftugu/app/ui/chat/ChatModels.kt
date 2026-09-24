package com.guftugu.app.ui.chat

import androidx.compose.runtime.Immutable
import com.guftugu.app.protocol.Attachment
import com.guftugu.app.protocol.ContentType

/** A clickable URL inside a bubble's text, found once in the ViewModel (regex never runs in composition). */
@Immutable
data class LinkSpan(val start: Int, val end: Int, val url: String)

@Immutable
data class ReplyQuote(val msgId: String, val sender: String, val text: String)

@Immutable
sealed class BubbleBody {
    @Immutable
    data class Text(val text: String, val links: List<LinkSpan>) : BubbleBody()

    @Immutable
    data class Media(val type: ContentType, val attachment: Attachment, val caption: String?, val captionLinks: List<LinkSpan>) : BubbleBody()

    @Immutable
    data class CallLog(val text: String, val video: Boolean, val missed: Boolean) : BubbleBody()

    @Immutable
    data class System(val text: String) : BubbleBody()

    data object Locked : BubbleBody()

    data object Deleted : BubbleBody()
}

/** Everything a bubble needs, pre-formatted. Stable key: [msgId]. */
@Immutable
data class MessageUi(
    val msgId: String,
    val isMine: Boolean,
    val senderName: String,
    /** Group chats: show the sender name above the bubble for the first message in a run. */
    val showSender: Boolean,
    val body: BubbleBody,
    val time: String,
    val tick: Tick,
    val reply: ReplyQuote?,
    /** Text to put on the clipboard for "Copy" (null hides the action). */
    val copyText: String?,
    /** Arrived while this screen was open → one-shot slide-in. */
    val isNew: Boolean,
)

@Immutable
sealed class ChatItem {
    abstract val key: String
    abstract val type: String

    @Immutable
    data class DateChip(override val key: String, val label: String) : ChatItem() {
        override val type: String get() = "date"
    }

    @Immutable
    data class Bubble(val message: MessageUi) : ChatItem() {
        override val key: String get() = message.msgId
        override val type: String get() = when (message.body) {
            is BubbleBody.System -> "system"
            is BubbleBody.CallLog -> "call"
            is BubbleBody.Media -> "media"
            else -> if (message.isMine) "mine" else "theirs"
        }
    }
}

@Immutable
data class ChatHeader(
    val title: String = "",
    val subtitle: String? = null,
    val isGroup: Boolean = false,
    /** Audio/video call actions (direct chats, calls enabled on the server). */
    val canCall: Boolean = false,
)

@Immutable
data class ChatListUi(
    /** Newest first (the list is `reverseLayout`). */
    val items: List<ChatItem> = emptyList(),
    val loaded: Boolean = false,
)

@Immutable
data class ComposerUi(
    val replyTo: ReplyQuote? = null,
    /** Picked photo/video awaiting a caption. */
    val pendingCaption: PendingMedia? = null,
    val sending: Boolean = false,
)

@Immutable
data class PendingMedia(val uri: android.net.Uri, val type: ContentType, val fileName: String?)

sealed class ChatEffect {
    data class OpenCall(val callId: String) : ChatEffect()
    data class Notice(val text: String) : ChatEffect()
}
