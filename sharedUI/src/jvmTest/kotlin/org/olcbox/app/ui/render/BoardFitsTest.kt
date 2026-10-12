package org.olcbox.app.ui.render

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import org.olcbox.app.ui.components.kit.pkTied
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The home screen on the phones it has to fit.
 *
 * What these hold the board to is the thing that was reported: on a narrow phone
 * with the text enlarged, a room was a card with a tag and a badge and no name.
 * Nothing on the board may be cut — no ellipsis, no clipped line — on any width
 * from 320 points up, at any text size up to twice the default, in any language
 * the app is written in.
 */
@OptIn(ExperimentalTestApi::class)
class BoardFitsTest {
    private val phones = listOf(
        Phone("zoomed", 320, 693),
        Phone("android", 360, 780),
        Phone("se3", 375, 667),
        Phone("iphone", 393, 852),
        Phone("max", 430, 932)
    )
    private val scales = listOf(1f, 1.3f, 1.6f, 2f)

    private val scenes = mapOf(
        "in a room" to BoardFixture.Scene(connected = true),
        "idle" to BoardFixture.Scene(connected = false, selectedId = BoardFixture.FLAGGED, seats = false),
        "admin" to BoardFixture.Scene(connected = true, admin = true, lowest = true),
        "new" to BoardFixture.Scene(connected = false, empty = true),
        "failed" to BoardFixture.Scene(connected = false, seats = false, notice = BoardFixture.NOTICE)
    )

    private fun cutTexts(shapes: List<Phone>, scenes: Map<String, BoardFixture.Scene>): List<String> =
        shapes.flatMap { phone ->
            scenes.flatMap { (name, scene) ->
                measuredOn(phone, { BoardFixture.Home(scene) }) {
                    laidTexts().filter { it.cut }.map { "$name on ${phone.label}: \"${it.text}\"" }
                }
            }
        }

    @Test fun nothingIsCutInEnglishOrRussianOnAnyPhoneAtAnyTextSize() {
        val shapes = phones.flatMap { phone ->
            scales.flatMap { scale -> listOf("en", "ru").map { phone.copy(fontScale = scale, language = it) } }
        }
        val cut = cutTexts(shapes, scenes)
        assertTrue(cut.isEmpty(), "cut on the board:\n" + cut.joinToString("\n"))
    }

    @Test fun nothingIsCutInPersianOrChinese() {
        // Right to left, and a script whose words are not separated by spaces.
        val shapes = listOf(phones.first(), phones[3]).flatMap { phone ->
            listOf(1f, 1.6f).flatMap { scale -> listOf("fa", "zh").map { phone.copy(fontScale = scale, language = it) } }
        }
        val cut = cutTexts(shapes, scenes.filterKeys { it != "admin" })
        assertTrue(cut.isEmpty(), "cut on the board:\n" + cut.joinToString("\n"))
    }

    @Test fun everyRoomHasItsNameOnTheNarrowestPhoneAtTheLargestText() {
        // The report, as a test: every name in the list is on the screen in full,
        // on at most two lines.
        val phone = Phone("zoomed", 320, 693, fontScale = 2f, language = "ru")
        measuredOn(phone, { BoardFixture.Home(BoardFixture.Scene(connected = true)) }) {
            val texts = laidTexts()
            val names = BoardFixture.locations().map { pkTied(BoardFixture.nameOf(it)) }
            for (name in names) {
                val laid = texts.filter { it.text == name }
                assertTrue(laid.isNotEmpty(), "no card says \"$name\"")
                laid.forEach {
                    assertTrue(!it.cut, "\"$name\" is cut")
                    assertTrue(it.layout.lineCount <= 2, "\"$name\" took ${it.layout.lineCount} lines")
                    assertTrue(it.node.size.width > 0, "\"$name\" has no width")
                }
            }
        }
    }

    @Test fun whereACardHoldsEverythingItIsOneLineAsItWas() {
        // A 6.1-inch phone at the default text size: the tag and the latency stand
        // on the name's line, and a card is no taller than it was before any of this.
        val phone = Phone("iphone", 393, 852, language = "en")
        measuredOn(phone, { BoardFixture.Home(BoardFixture.Scene(connected = false, seats = false)) }) {
            val name = onAllNodesWithText(pkTied("CA · olcRTC"), useUnmergedTree = true).fetchSemanticsNodes().first().place()
            val tag = onAllNodesWithText("VP8", useUnmergedTree = true).fetchSemanticsNodes().first().place()
            assertTrue(tag.top < name.bottom && tag.bottom > name.top, "the tag left the name's line: name $name, tag $tag")
            assertTrue(tag.left > name.right, "the tag is not after the name: name $name, tag $tag")
        }
    }

    @Test fun whereTheNameNeedsTheLineTheTagGoesUnderIt() {
        val phone = Phone("zoomed", 320, 693, fontScale = 2f, language = "en")
        measuredOn(phone, { BoardFixture.Home(BoardFixture.Scene(connected = false, seats = false)) }) {
            val name = onAllNodesWithText(pkTied("CA · olcRTC · MTS"), useUnmergedTree = true).fetchSemanticsNodes().first().place()
            val tags = onAllNodesWithText("VP8", useUnmergedTree = true).fetchSemanticsNodes().map { it.place() }
            val tag = tags.first { it.top >= name.top }
            assertTrue(tag.top >= name.bottom, "the tag is still beside a name it leaves no room for: name $name, tag $tag")
            assertEquals(name.left, tag.left, 1f, "the tag does not start where the name does")
        }
    }

    @Test fun theSeatsAndTheWordsBesideThemStayInsideTheirCard() {
        // Sixteen seats are the widest row there is; the words beside them were the
        // count that lost its last digits ("0 /") in a box sized in points.
        for (phone in listOf(Phone("zoomed", 320, 693, 2f, "ru"), Phone("iphone", 393, 852, 1.3f, "ru"))) {
            measuredOn(phone, { BoardFixture.Home(BoardFixture.Scene(connected = true)) }) {
                val screen = phone.widthDp * RENDER_DENSITY
                val words = laidTexts().filter { it.text.startsWith("свободно") || it.text == "мест нет" }
                assertTrue(words.isNotEmpty(), "no card says how many seats are free")
                words.forEach {
                    assertTrue(!it.cut, "\"${it.text}\" is cut on ${phone.label}")
                    // Position and size: a card further down the list has no
                    // clipped bounds to speak of until it is scrolled to.
                    val left = it.node.positionInRoot.x
                    val right = left + it.node.size.width
                    assertTrue(left >= 0f && right <= screen, "\"${it.text}\" runs off ${phone.label}: $left..$right")
                }
            }
        }
    }
}
