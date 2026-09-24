package com.guftugu.app.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodesTest {
    @Test
    fun `normalises the ways people type a code`() {
        assertEquals("GFT-7K3M-Q9XD", InviteCodes.normalize("GFT-7K3M-Q9XD"))
        assertEquals("GFT-7K3M-Q9XD", InviteCodes.normalize("gft-7k3m-q9xd"))
        assertEquals("GFT-7K3M-Q9XD", InviteCodes.normalize(" gft 7k3m q9xd "))
        assertEquals("GFT-7K3M-Q9XD", InviteCodes.normalize("7K3MQ9XD"))
        assertEquals("GFT-7K3M-Q9XD", InviteCodes.normalize("GFT7K3MQ9XD"))
        assertEquals("", InviteCodes.normalize(null))
        assertEquals("", InviteCodes.normalize("   "))
        // wrong length: returned compacted so the field shows the cleaned input
        assertEquals("GFT7K3M", InviteCodes.normalize("gft-7k3m"))
    }

    @Test
    fun `validates the canonical shape and the crockford alphabet`() {
        assertTrue(InviteCodes.isValid("GFT-7K3M-Q9XD"))
        assertTrue(InviteCodes.isValid(InviteCodes.normalize("gft 7k3m q9xd")))
        assertFalse(InviteCodes.isValid("GFT-7K3M-Q9X"))
        assertFalse(InviteCodes.isValid("GFT-7K3M-Q9XI")) // I is not in Crockford base32
        assertFalse(InviteCodes.isValid("GFT-7K3M-Q9XO")) // nor O
        assertFalse(InviteCodes.isValid("gft-7k3m-q9xd")) // must be normalised first
        assertFalse(InviteCodes.isValid("ABC-7K3M-Q9XD"))
        assertFalse(InviteCodes.isValid(""))
    }
}
