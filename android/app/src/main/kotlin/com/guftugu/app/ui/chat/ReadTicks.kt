package com.guftugu.app.ui.chat

import com.guftugu.app.domain.Member
import com.guftugu.app.domain.MessageStatus

/** Status glyph shown in the bottom-right corner of my bubbles. */
enum class Tick { NONE, PENDING, SENT, READ, FAILED }

/**
 * Read-receipt logic (pure, unit-tested). Message ids are `m_` + ULID, so lexicographic order is
 * creation order and "read up to X" is a simple string comparison.
 */
object ReadTicks {
    /**
     * The highest message id every *other* member has read: the minimum of their `lastReadMsgId`s.
     * Null when there is nobody else, or when someone has read nothing yet — then no message is
     * "read by all". A direct chat has exactly one other member, so this is just their receipt.
     */
    fun watermark(members: List<Member>, myUserId: String?): String? {
        var min: String? = null
        var others = 0
        for (m in members) {
            if (m.userId == myUserId) continue
            others++
            val r = m.lastReadMsgId ?: return null
            if (min == null || r < min) min = r
        }
        return if (others == 0) null else min
    }

    fun isReadByAll(msgId: String, watermark: String?): Boolean = watermark != null && msgId <= watermark

    fun tick(isMine: Boolean, status: MessageStatus, msgId: String, watermark: String?): Tick = when {
        !isMine -> Tick.NONE
        status == MessageStatus.PENDING -> Tick.PENDING
        status == MessageStatus.FAILED -> Tick.FAILED
        isReadByAll(msgId, watermark) -> Tick.READ
        else -> Tick.SENT
    }
}
