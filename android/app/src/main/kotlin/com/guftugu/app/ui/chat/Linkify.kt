package com.guftugu.app.ui.chat

/** Finds http(s)/www URLs in message text. Runs once per message in the ViewModel, never in composition. */
object Linkify {
    private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")
    private const val TRAILING = ".,;:!?)]}'\""

    fun find(text: String): List<LinkSpan> {
        if (text.length < 4 || (!text.contains("://") && !text.contains("www.", ignoreCase = true))) return emptyList()
        val out = ArrayList<LinkSpan>(2)
        for (m in URL.findAll(text)) {
            var end = m.range.last + 1
            // Drop trailing punctuation that is almost never part of the URL ("see https://x.y/z.").
            while (end > m.range.first && text[end - 1] in TRAILING) end--
            if (end - m.range.first < 4) continue
            val raw = text.substring(m.range.first, end)
            val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
            out.add(LinkSpan(m.range.first, end, url))
        }
        return out
    }
}
