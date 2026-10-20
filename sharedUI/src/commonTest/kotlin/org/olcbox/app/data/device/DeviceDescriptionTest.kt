package org.olcbox.app.data.device

import kotlin.test.Test
import kotlin.test.assertEquals

// The cut is the one ProofKit's server keeps a device header by, so what the
// app sends is exactly what a provider shows.
class DeviceDescriptionTest {
    @Test
    fun aHeaderValueIsCutTo64Characters() {
        assertEquals("a".repeat(64), asciiHeader("a".repeat(70)))
        assertEquals("a".repeat(64), asciiHeader("a".repeat(64)))
        assertEquals(64, DEVICE_HEADER_MAX_CHARS)
    }

    @Test
    fun whatIsNotPrintableAsciiIsDroppedNeverReplaced() {
        assertEquals("Caf", asciiHeader("Café"))
        assertEquals("Pixel 7 Pro", asciiHeader("Pixel\t 7é Pro"))
        assertEquals("abcde", asciiHeader("a\nb\r\u0000c\u007fd\u001be"))
        // 64 printable characters, counted after the cleaning.
        assertEquals("x".repeat(60) + "yyyy", asciiHeader("x".repeat(60) + "é" + "y".repeat(10)))
    }

    @Test
    fun anEmptyValueStaysEmptyAndABlankOneBecomesEmpty() {
        assertEquals("", asciiHeader(""))
        assertEquals("", asciiHeader("   "))
        assertEquals("", asciiHeader("Тел"))
        assertEquals("iPhone 13", asciiHeader("  iPhone 13  "))
    }
}
