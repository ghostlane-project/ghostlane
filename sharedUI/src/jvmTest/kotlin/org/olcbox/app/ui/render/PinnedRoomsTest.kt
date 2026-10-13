package org.olcbox.app.ui.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.components.kit.PkRoomCard
import org.olcbox.app.ui.components.kit.SeatDisplay
import org.olcbox.app.ui.components.kit.pkTied
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rooms pinned to the top of their lists, as the board draws them and takes
 * the press.
 *
 * Asked for from a phone with a list of a hundred rooms on it, three of which
 * its owner uses. Which rooms are pinned and where they stand is the model's
 * (`LocationSelectionModelTest`); this is the card and the board.
 */
@OptIn(ExperimentalTestApi::class)
class PinnedRoomsTest {
    private val phone = Phone("iphone", 393, 852)
    private val rooms = BoardFixture.locations()

    @Test fun everyRoomHasAPinAndTheRowThatPicksTheFastestHasNone() {
        // A long press says nothing about itself, and choosing a room to find a
        // button on it would move a live session to that room.
        val scene = BoardFixture.Scene(connected = false, lowest = true)
        measuredOn(phone, { BoardFixture.Home(scene) }) {
            val pins = onAllNodesWithContentDescription(PIN, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(rooms.size, pins.size, "a pin to a room")
        }
    }

    @Test fun pressingAPinAsksForThatRoomToBePinnedAndDoesNotChooseIt() {
        val pinned = mutableListOf<String>()
        val chosen = mutableListOf<String>()
        measuredOn(phone, { BoardFixture.Home(BoardFixture.Scene(connected = false), onPin = { pinned += it }, onSelect = { chosen += it }) }) {
            onAllNodesWithContentDescription(PIN, useUnmergedTree = true).onFirst().performTouchInput { click() }
            waitForIdle()
        }
        assertEquals(listOf(rooms.first().storageId), pinned)
        assertEquals(emptyList(), chosen, "pinning a room chose it — which, with a session up, moves the session")
    }

    @Test fun thePinIsPressedOverMoreOfTheCardThanItIsDrawnOn() {
        // Fifteen points of drawing in a card that is itself a button: a press
        // that misses it by a little would choose the room instead.
        val pinned = mutableListOf<String>()
        val chosen = mutableListOf<String>()
        measuredOn(phone, { BoardFixture.Home(BoardFixture.Scene(connected = false), onPin = { pinned += it }, onSelect = { chosen += it }) }) {
            // The thing that is pressed, not the drawing inside it: a control
            // and what it holds are one node unless they are asked for apart.
            val pin = onAllNodesWithContentDescription(PIN).onFirst()
            val size = pin.fetchSemanticsNode().size
            assertTrue(size.width >= 40 * RENDER_DENSITY && size.height >= 40 * RENDER_DENSITY, "the pin takes a press on $size px")
            // Its corners: as far from the drawing as the pin still reaches.
            pin.performTouchInput { click(Offset(3f, 3f)) }
            pin.performTouchInput { click(Offset(width - 3f, height - 3f)) }
            waitForIdle()
        }
        assertEquals(List(2) { rooms.first().storageId }, pinned)
        assertEquals(emptyList(), chosen)
    }

    @Test fun aPinnedRoomsPinSaysWhatPressingItDoesNow() {
        val mine = rooms[7]
        measuredOn(phone, { BoardFixture.Home(BoardFixture.Scene(connected = false, pinned = listOf(mine.storageId))) }) {
            assertEquals(1, onAllNodesWithContentDescription(UNPIN, useUnmergedTree = true).fetchSemanticsNodes().size)
            assertEquals(rooms.size - 1, onAllNodesWithContentDescription(PIN, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    @Test fun aPinnedRoomStandsAboveTheRestOfItsList() {
        val mine = rooms.first { it.storageId == "ours-ES-VK" }
        measuredOn(phone, { BoardFixture.Home(BoardFixture.Scene(connected = false, pinned = listOf(mine.storageId))) }) {
            val tops = laidTexts().associate { it.text to it.node.positionInRoot.y }
            val pinnedAt = tops.getValue(pkTied(BoardFixture.nameOf(mine)))
            val firstOfTheList = tops.getValue(pkTied(BoardFixture.nameOf(rooms.first())))
            assertTrue(pinnedAt < firstOfTheList, "the pinned room is at $pinnedAt, the list's first at $firstOfTheList")
        }
    }

    @Test fun aFoldedListKeepsItsPinnedRoomsOnTheBoard() {
        // Fold a list of a hundred rooms and what is left is the three in use:
        // the board somebody with one list and a few rooms wants to open.
        val mine = rooms.first { it.storageId == "ours-ES-VK" }
        val scene = BoardFixture.Scene(connected = false, lowest = true, folded = setOf(BoardFixture.OUR_LIST), pinned = listOf(mine.storageId))
        val texts = measuredOn(phone, { BoardFixture.Home(scene) }) { laidTexts().map { it.text }.toSet() }
        assertTrue(pkTied(BoardFixture.nameOf(mine)) in texts, "the pinned room went with its list: $texts")
        assertTrue(pkTied("CA · olcRTC") !in texts, "a room that is not pinned stayed: $texts")
        assertTrue("Lowest latency" !in texts, "the row that picks the fastest stayed on a folded list: $texts")
    }

    @Test fun aCardIsNoTallerForHavingAPin() {
        // A hundred cards, each a few points taller, is a screen more to scroll.
        for (scale in listOf(1f, 1.3f, 2f)) {
            val shaped = phone.copy(fontScale = scale)
            val plain = cardHeight(shaped, withPin = false)
            val pinnable = cardHeight(shaped, withPin = true)
            assertEquals(plain, pinnable, "a card with a pin, at text size $scale")
        }
    }

    private fun cardHeight(phone: Phone, withPin: Boolean): Int {
        val height = mutableStateOf(0)
        return measuredOn(phone, { Card(withPin) { height.value = it } }) { height.value }
    }

    @Composable
    private fun Card(withPin: Boolean, onHeight: (Int) -> Unit) {
        Box(Modifier.padding(16.dp).onSizeChanged { onHeight(it.height) }) {
            PkRoomCard(
                title = "ES · olcRTC",
                tag = "VP8",
                emoji = null,
                selected = false,
                connectedHere = false,
                blocked = false,
                seats = SeatDisplay.None,
                freeText = null,
                freeIsFull = false,
                freeIsTight = false,
                history = emptyList(),
                pingMs = 128,
                isMeasuring = false,
                isOffline = false,
                keyGone = false,
                wire = "a Telemost media session",
                onClick = {},
                onPinToggle = if (withPin) ({}) else null
            )
        }
    }

    private companion object {
        const val PIN = "Pin to the top"
        const val UNPIN = "Unpin"
    }
}
