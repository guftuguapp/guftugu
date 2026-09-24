package com.guftugu.app.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordStrengthTest {
    @Test
    fun `scores grow with length and variety`() {
        assertEquals(0, PasswordStrength.score(""))
        assertEquals(1, PasswordStrength.score("short"))
        assertFalse(PasswordStrength.meetsMinimum("12345"))
        assertTrue(PasswordStrength.meetsMinimum("123456"))
        val weak = PasswordStrength.score("password")
        val fair = PasswordStrength.score("password1234")
        val good = PasswordStrength.score("Password1234!")
        val strong = PasswordStrength.score("Password1234!Password1234!")
        assertTrue(weak <= fair && fair <= good && good <= strong)
        assertEquals(4, strong)
        assertEquals(4, PasswordStrength.score("correct horse battery staple"))
    }
}
