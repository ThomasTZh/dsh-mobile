package dev.dsh.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinRulesTest {
    @Test
    fun acceptsTypicalPins() {
        assertEquals("12345678", PinRules.normalize("12345678"))
        assertEquals("abcdefgh", PinRules.normalize(" abcdefgh "))
    }

    @Test
    fun rejectsShortLongOrControlPins() {
        assertNull(PinRules.normalize("12345"))
        assertNull(PinRules.normalize("a".repeat(65)))
        assertNull(PinRules.normalize("abc\ndef"))
    }

    @Test
    fun validationMessages() {
        assertNull(PinRules.validate(""))
        assertNull(PinRules.validate("12345678"))
        assertEquals("PINs are at least 6 characters.", PinRules.validate("123"))
    }
}
