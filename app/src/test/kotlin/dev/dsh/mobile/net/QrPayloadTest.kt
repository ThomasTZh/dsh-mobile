package dev.dsh.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadTest {
    @Test
    fun parsesPlainPocketQr() {
        val parsed = QrPayload.parse("http://192.168.1.15:3081")
        assertEquals("http://192.168.1.15:3081", parsed.baseUrl)
        assertNull(parsed.pin)
    }

    @Test
    fun extractsTokenAndDropsItFromTheUrl() {
        val parsed = QrPayload.parse("http://192.168.1.15:3081/?token=abcdef12")
        assertEquals("http://192.168.1.15:3081", parsed.baseUrl)
        assertEquals("abcdef12", parsed.pin)
    }

    @Test
    fun acceptsAddressWithoutScheme() {
        val parsed = QrPayload.parse("192.168.1.15:3081")
        assertEquals("http://192.168.1.15:3081", parsed.baseUrl)
    }

    @Test
    fun rejectsPublicOrBrokenPayloads() {
        assertFalse(QrPayload.parse("https://example.com").ok)
        assertFalse(QrPayload.parse("").ok)
        assertFalse(QrPayload.parse("http://").ok)
        assertFalse(QrPayload.parse("not a url").ok)
        assertFalse(QrPayload.parse("http://192.168.1.15:3081\nmore").ok)
        assertTrue(QrPayload.parse("[fd00::1]:3081").ok)
    }
}
