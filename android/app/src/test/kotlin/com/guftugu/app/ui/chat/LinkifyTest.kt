package com.guftugu.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkifyTest {
    @Test
    fun `finds http and www links and strips trailing punctuation`() {
        val text = "see https://example.com/a?b=1. and www.guftugu.app/x, ok"
        val links = Linkify.find(text)
        assertEquals(2, links.size)
        assertEquals("https://example.com/a?b=1", text.substring(links[0].start, links[0].end))
        assertEquals("https://example.com/a?b=1", links[0].url)
        assertEquals("www.guftugu.app/x", text.substring(links[1].start, links[1].end))
        assertEquals("https://www.guftugu.app/x", links[1].url)
    }

    @Test
    fun `plain text has no links`() {
        assertTrue(Linkify.find("dinner at 8?").isEmpty())
        assertTrue(Linkify.find("").isEmpty())
    }
}
