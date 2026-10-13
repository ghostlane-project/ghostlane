package org.olcbox.app.ui.render

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.olcbox.app.ui.components.kit.pkTied
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A server list folded away, as the board draws it.
 *
 * Which lists are folded is the settings' to say and the board's to draw. It was
 * the board's own to remember, which is why it forgot: see
 * `HomeScreenModelSettingsTest`.
 */
@OptIn(ExperimentalTestApi::class)
class FoldedListTest {
    private val phone = Phone("iphone", 393, 852)

    private fun namesOnTheBoard(scene: BoardFixture.Scene): Set<String> =
        measuredOn(phone, { BoardFixture.Home(scene) }) { laidTexts().map { it.text }.toSet() }

    @Test fun aListTheSettingsCallFoldedShowsItsHeadAndNoneOfItsRooms() {
        val texts = namesOnTheBoard(BoardFixture.Scene(connected = false, folded = setOf(BoardFixture.OUR_LIST)))
        assertTrue("Encrypted list" in texts, "the folded list has no head: $texts")
        val ours = BoardFixture.locations().filter { it.subscriptionUrl == BoardFixture.OUR_LIST }.map { pkTied(BoardFixture.nameOf(it)) }
        assertEquals(emptyList(), ours.filter { it in texts }, "rooms of a folded list are on the board")
        // The list beside it is as it was.
        assertTrue(pkTied("ES · Madrid") in texts, "the other list lost its rows: $texts")
    }

    @Test fun withNothingFoldedEveryListShowsItsRooms() {
        val texts = namesOnTheBoard(BoardFixture.Scene(connected = false))
        assertTrue(pkTied("CA · olcRTC") in texts && pkTied("ES · Madrid") in texts, "rows missing: $texts")
    }

    @Test fun pressingAListsHeadAsksForThatListToBeFolded() {
        val asked = mutableListOf<String>()
        measuredOn(phone, { BoardFixture.Home(BoardFixture.Scene(connected = false), onFold = { asked += it }) }) {
            onNodeWithText("Encrypted list").performClick()
            waitForIdle()
        }
        assertEquals(listOf(BoardFixture.OUR_LIST), asked)
    }

    @Test fun theBoardDoesNotFoldAListByItself() {
        // The press asks; the list is folded when the settings say so. A board
        // that also kept its own note of it would show the two disagreeing.
        measuredOn(phone, { BoardFixture.Home(BoardFixture.Scene(connected = false), onFold = {}) }) {
            onNodeWithText("Encrypted list").performClick()
            waitForIdle()
            val texts = laidTexts().map { it.text }
            assertTrue(pkTied("CA · olcRTC") in texts, "the board folded a list nobody stored as folded")
        }
    }
}
