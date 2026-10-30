package dev.dsh.mobile.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    @Test
    fun dispatchesOnBlankLine() {
        val parser = SseParser()
        assertNull(parser.line("event: snapshot"))
        assertNull(parser.line("data: {\"a\":1}"))
        val event = parser.line("")
        assertEquals("snapshot", event?.event)
        assertEquals("{\"a\":1}", event?.data)
    }

    @Test
    fun joinsMultipleDataLines() {
        val parser = SseParser()
        parser.line("data: line1")
        parser.line("data: line2")
        val event = parser.line("")
        assertEquals("line1\nline2", event?.data)
    }

    @Test
    fun ignoresCommentsAndKeepsDefaultEventName() {
        val parser = SseParser()
        assertNull(parser.line(": hb"))
        parser.line("data: hi")
        assertEquals("message", parser.line("")?.event)
    }

    @Test
    fun handlesCrlfAndSplitFields() {
        val parser = SseParser()
        parser.line("event: turn-done\r")
        parser.line("data: {}\r")
        val event = parser.line("\r")
        assertEquals("turn-done", event?.event)
        assertEquals("{}", event?.data)
    }

    @Test
    fun keepsDataUntilTerminator() {
        val parser = SseParser()
        assertNull(parser.line("data: pending"))
        assertNull(parser.line("event: clear"))
        val event = parser.line("")
        assertEquals("clear", event?.event)
        assertEquals("pending", event?.data)
    }
}
