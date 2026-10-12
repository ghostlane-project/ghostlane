package org.olcbox.app.util

import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiUtilsTest {

    @Test
    fun aFlagIsTakenWholeNotCutBetweenItsTwoLetters() {
        // The bug this file was rewritten for. A flag is two regional indicators;
        // taking one left the card a boxed E for an emoji and a name that began
        // with a boxed S — on the card, the connect button and the status strip.
        assertEquals("🇪🇸" to "ES · Madrid", parseEmojiAndName("🇪🇸 ES · Madrid"))
        assertEquals("🇨🇦" to "CA · VP8", parseEmojiAndName("🇨🇦 CA · VP8"))
        assertEquals("🇳🇱" to "Netherlands-Amsterdam-03", parseEmojiAndName("🇳🇱Netherlands-Amsterdam-03"))
    }

    @Test
    fun twoFlagsInARowAreTwoFlags() {
        assertEquals("🇩🇪" to "🇫🇷 relay", parseEmojiAndName("🇩🇪🇫🇷 relay"))
    }

    @Test
    fun aLoneRegionalIndicatorIsStillTakenAsOneEmoji() {
        assertEquals("🇪" to "odd", parseEmojiAndName("🇪 odd"))
    }

    @Test
    fun anEmojiOfOneCodePointIsWhatItAlwaysWas() {
        assertEquals("⚡" to "Fast", parseEmojiAndName("⚡ Fast"))
        assertEquals("🚀" to "Rocket", parseEmojiAndName("🚀Rocket"))
    }

    @Test
    fun whatIsJoinedOrModifiedStaysWithItsEmoji() {
        // A variation selector, a skin tone, a zero-width joiner sequence and a
        // keycap: each is one picture and several code points.
        assertEquals("⚡️" to "Fast", parseEmojiAndName("⚡️ Fast"))
        assertEquals("👍🏽" to "Good", parseEmojiAndName("👍🏽 Good"))
        assertEquals("👨‍👩‍👧" to "Family plan", parseEmojiAndName("👨‍👩‍👧 Family plan"))
        assertEquals("🏴‍☠️" to "Pirate", parseEmojiAndName("🏴‍☠️ Pirate"))
    }

    @Test
    fun aSubdivisionFlagKeepsItsTags() {
        // Scotland: a black flag, five tag letters and a cancel tag.
        val scotland = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"
        assertEquals(scotland to "Edinburgh", parseEmojiAndName("$scotland Edinburgh"))
    }

    @Test
    fun aNameWithNoEmojiKeepsItsNameAndTakesTheDefault() {
        assertEquals("" to "DE · olcRTC · WB", parseEmojiAndName("DE · olcRTC · WB"))
        assertEquals("🌐" to "Germany", parseEmojiAndName("Germany", defaultEmoji = "🌐"))
        assertEquals("" to "Комната", parseEmojiAndName("Комната"))
    }

    @Test
    fun aBlankNameIsTheDefaultAndNothing() {
        assertEquals("🌐" to "", parseEmojiAndName("   ", defaultEmoji = "🌐"))
        assertEquals("" to "", parseEmojiAndName(""))
    }

    @Test
    fun aNameThatIsOnlyAnEmojiHasNoNameLeft() {
        assertEquals("🇩🇪" to "", parseEmojiAndName("🇩🇪"))
    }

    @Test
    fun aJoinerWithNothingToJoinIsLeftInTheName() {
        assertEquals("⚡" to "‍Fast", parseEmojiAndName("⚡‍Fast"))
    }
}
