package dev.dsh.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CookieHeadersTest {
    @Test
    fun findsThePocketCookie() {
        val headers = listOf(
            "dsh_pocket_token=abc123; HttpOnly; SameSite=Lax; Path=/; Max-Age=2592000",
            "other=1; Path=/",
        )
        assertEquals("abc123", CookieHeaders.find(headers, "dsh_pocket_token"))
    }

    @Test
    fun ignoresOtherCookiesAndMalformedHeaders() {
        assertNull(CookieHeaders.find(listOf("other=1"), "dsh_pocket_token"))
        assertNull(CookieHeaders.find(listOf("dsh_pocket_token="), "dsh_pocket_token"))
        assertNull(CookieHeaders.find(listOf("dsh_pocket_token=a\r\nInjected: 1"), "dsh_pocket_token"))
        assertNull(CookieHeaders.find(emptyList(), "dsh_pocket_token"))
    }

    @Test
    fun parsesRetryAfterSeconds() {
        assertEquals(60L, CookieHeaders.retryAfterSeconds("60"))
        assertNull(CookieHeaders.retryAfterSeconds(null))
        assertNull(CookieHeaders.retryAfterSeconds("soon"))
        assertEquals(3600L, CookieHeaders.retryAfterSeconds("999999"))
    }
}
