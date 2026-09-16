package org.olcbox.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisclosureBlocksTest {

    /**
     * The one test that matters here. The disclosure is a legally required notice
     * and this parser exists only to re-format it; a bug that dropped a paragraph
     * would remove part of that notice and the screen would look perfectly fine.
     */
    @Test
    fun everyLineOfTheSourceSurvivesTheParse() {
        val parsed = disclosureBlocks(DISCLOSURE_BODY)
        val rebuilt = parsed.flatMap { block ->
            listOfNotNull(block.heading) + block.paragraphs
        }
        val original = DISCLOSURE_BODY.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(original, rebuilt)
    }

    @Test
    fun theOpeningSentenceHasNoHeadingOverIt() {
        val first = disclosureBlocks(DISCLOSURE_BODY).first()
        assertNull(first.heading)
        assertTrue(first.paragraphs.single().startsWith("Ghostlane connects using"))
    }

    @Test
    fun theHeadingsAreTheThreeTheTextIsWrittenIn() {
        assertEquals(
            listOf("WHAT IT DOES", "WHAT THIS APP DOES WITH IT", "WHERE YOUR TRAFFIC GOES"),
            disclosureBlocks(DISCLOSURE_BODY).mapNotNull { it.heading }
        )
    }

    /**
     * The rule from the 4.3 work: this string is commonMain and iOS renders it, so
     * it must not name a platform, and the word "subscription" cost two 3.1.1
     * rejections.
     */
    @Test
    fun theDisclosureNamesNoPlatformAndSellsNothing() {
        val text = DISCLOSURE_BODY.lowercase()
        listOf("android", "ios", "iphone", "ipad", "subscription", "subscribe").forEach { word ->
            assertTrue(word !in text, "the disclosure must not say \"$word\"")
        }
    }

    @Test
    fun aParagraphWithNoHeadingAboveItStillBecomesABlock() {
        val blocks = disclosureBlocks("One line.\n\nTWO\n\nThree.")
        assertEquals(2, blocks.size)
        assertNull(blocks[0].heading)
        assertEquals(listOf("One line."), blocks[0].paragraphs)
        assertEquals("TWO", blocks[1].heading)
        assertEquals(listOf("Three."), blocks[1].paragraphs)
    }

    @Test
    fun aLongShoutedSentenceIsProseRatherThanAHeading() {
        // Length is what separates "WHAT IT DOES" from a paragraph someone wrote in
        // capitals; without the bound the whole thing would render as an eyebrow.
        val blocks = disclosureBlocks(
            "THIS IS A VERY LONG LINE WRITTEN ENTIRELY IN CAPITAL LETTERS INDEED."
        )
        assertNull(blocks.single().heading)
        assertEquals(1, blocks.single().paragraphs.size)
    }
}
