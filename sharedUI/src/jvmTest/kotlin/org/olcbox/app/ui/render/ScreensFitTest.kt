package org.olcbox.app.ui.render

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every screen that is not the board, held to what the board is held to: nothing
 * cut, and nothing a person needs put where they cannot reach it.
 */
@OptIn(ExperimentalTestApi::class)
class ScreensFitTest {
    private val shapes = listOf(
        // Narrower than any phone: the margin for a phone whose own font is wider
        // than the ones these tests measure. See BoardFitsTest.
        Phone("margin", 300, 650, 1.6f, "ru"),
        Phone("margin", 300, 650, 2f, "en"),
        Phone("zoomed", 320, 693, 1f, "ru"),
        Phone("zoomed", 320, 693, 1.6f, "ru"),
        Phone("zoomed", 320, 693, 2f, "ru"),
        Phone("zoomed", 320, 693, 2f, "en"),
        Phone("se1", 320, 568, 1.3f, "ru"),
        Phone("android", 360, 780, 1.3f, "fa"),
        Phone("android", 360, 780, 1.3f, "zh"),
        Phone("iphone", 393, 852, 1f, "en"),
        Phone("iphone", 393, 852, 1.6f, "ru")
    )

    @Test fun nothingIsCutOnAnyOtherScreen() {
        val cut = shapes.flatMap { phone ->
            Screens.all.flatMap { (name, screen) ->
                measuredOn(phone, screen) { laidTexts().filter { it.cut }.map { "$name on ${phone.label}: \"${it.text}\"" } }
            }
        }
        assertTrue(cut.isEmpty(), "cut:\n" + cut.joinToString("\n"))
    }

    @Test fun theSettingsPagesBehindTheHubFitToo() {
        // One level in: every row of the hub is pressed in turn, on a screen made
        // new each time, and what it opens is held to the same two rules. A row
        // that opens nothing leaves the hub, which passes as it did above.
        val settings = Screens.all.getValue("settings")
        val found = listOf(
            Phone("margin", 300, 650, 1.6f, "ru"),
            Phone("zoomed", 320, 693, 2f, "ru"),
            Phone("iphone", 393, 852, 1f, "en")
        ).flatMap { phone ->
            val rows = measuredOn(phone, settings) { onAllNodes(hasClickAction()).fetchSemanticsNodes().size }
            val opened = mutableSetOf<String>()
            val wrong = (0 until rows).flatMap { row ->
                measuredOn(phone, settings) {
                    val control = onAllNodes(hasClickAction())[row]
                    // Brought into view first, as a finger would have to: a press
                    // aimed at a row under the fold lands on nothing. The header's
                    // back arrow is in nothing that scrolls, and needs no bringing.
                    runCatching { control.performScrollTo() }
                    control.performClick()
                    waitForIdle()
                    mainClock.advanceTimeBy(600)
                    waitForIdle()
                    val texts = laidTexts()
                    // A page is known by its heading: the first text on it.
                    texts.firstOrNull()?.let { opened += it.text }
                    texts.filter { it.cut }.map { "page of row $row on ${phone.label}: cut \"${it.text}\"" } +
                        outOfReach(phone).map { "page of row $row on ${phone.label}: out of reach $it" }
                }
            }
            // The hub and the six pages behind it. Fewer, and this test pressed
            // rows that opened nothing and proved nothing.
            assertTrue(opened.size >= 7, "only these pages were opened on ${phone.label}: $opened")
            wrong
        }
        assertTrue(found.isEmpty(), found.joinToString("\n"))
    }

    @Test fun everyControlCanBeReached() {
        val lost = shapes.flatMap { phone ->
            Screens.all.flatMap { (name, screen) ->
                measuredOn(phone, screen) { outOfReach(phone).map { "$name on ${phone.label}: $it" } }
            }
        }
        assertTrue(lost.isEmpty(), "out of reach:\n" + lost.joinToString("\n"))
    }
}
