package com.example.purchasedesk.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HashingTest {

    @Test
    fun `hash and verify roundtrip`() {
        val stored = Hashing.hashPassword("secret123")
        assertTrue(Hashing.verify("secret123", stored))
    }

    @Test
    fun `wrong password is rejected`() {
        val stored = Hashing.hashPassword("secret123")
        assertFalse(Hashing.verify("wrong", stored))
        assertFalse(Hashing.verify("", stored))
    }

    @Test
    fun `each hash uses a random salt`() {
        val a = Hashing.hashPassword("same")
        val b = Hashing.hashPassword("same")
        assertNotEquals(a, b)
        assertTrue(Hashing.verify("same", a))
        assertTrue(Hashing.verify("same", b))
    }

    @Test
    fun `stored format has three colon separated parts`() {
        val stored = Hashing.hashPassword("pw")
        val parts = stored.split(":")
        assertEquals(3, parts.size)
        assertEquals("120000", parts[0])
    }

    @Test
    fun `malformed stored values are rejected safely`() {
        assertFalse(Hashing.verify("pw", ""))
        assertFalse(Hashing.verify("pw", "not:enough"))
        assertFalse(Hashing.verify("pw", "abc:def:ghi"))
        assertFalse(Hashing.verify("pw", "120000:!!!:@@@"))
    }

    @Test
    fun `tokens are url safe and unique`() {
        val a = Tokens.randomToken()
        val b = Tokens.randomToken()
        assertNotEquals(a, b)
        assertTrue(a.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertEquals(43, a.length) // 32 bytes -> 43 urlsafe-base64 chars
    }
}
