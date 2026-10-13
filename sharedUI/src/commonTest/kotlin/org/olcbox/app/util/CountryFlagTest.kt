package org.olcbox.app.util

import kotlin.test.Test
import kotlin.test.assertEquals

class CountryFlagTest {
    @Test
    fun aCountrysCodeIsItsFlagsTwoRegionalIndicators() {
        assertEquals("🇩🇪", flagOf("DE"))
        assertEquals("🇦🇿", flagOf("AZ"))
        assertEquals("🇿🇦", flagOf("ZA"))
    }

    @Test
    fun whatIsNotTwoCapitalsHasNoFlag() {
        for (code in listOf("", "D", "DEU", "de", "D1", "Дe", "D E")) {
            assertEquals("", flagOf(code), "\"$code\"")
        }
    }

    // The flag a name is given is one the name's own parser reads back as its picture.
    @Test
    fun aFlagInFrontOfANameIsReadAsThatNamesPicture() {
        assertEquals(flagOf("GB") to "GB Direct", parseEmojiAndName("${flagOf("GB")} GB Direct"))
    }
}
