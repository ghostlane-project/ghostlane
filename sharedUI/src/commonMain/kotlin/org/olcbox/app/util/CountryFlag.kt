package org.olcbox.app.util

import org.olcbox.app.update.currentUpdatePlatform

/**
 * The flag of a country, from its two-letter code: the two regional indicators
 * a font joins into one picture. Empty for anything that is not two capitals.
 */
fun flagOf(countryCode: String): String {
    if (countryCode.length != 2 || countryCode.any { it !in 'A'..'Z' }) return ""
    return buildString(4) {
        for (letter in countryCode) {
            // U+1F1E6 and the 25 after it, as the pair of chars each is.
            append(REGIONAL_INDICATOR_LEAD)
            append(REGIONAL_INDICATOR_A_TRAIL + (letter - 'A'))
        }
    }
}

/**
 * Whether this system draws a flag as a flag. An iPhone, a Mac and an Android
 * phone do. Windows' emoji font has no flags and prints the two letters of the
 * code, beside a name that already opens with them; a Linux desktop has
 * whatever fonts its owner installed. A name that brings its own flag is
 * drawn as it came everywhere, as it always was.
 */
val drawsFlags: Boolean by lazy { currentUpdatePlatform().os in setOf("android", "ios", "macos") }

private const val REGIONAL_INDICATOR_LEAD = '\uD83C'
private const val REGIONAL_INDICATOR_A_TRAIL = '\uDDE6'
