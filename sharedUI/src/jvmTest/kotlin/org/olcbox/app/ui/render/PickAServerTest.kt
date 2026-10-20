package org.olcbox.app.ui.render

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.ui.components.kit.pkTied
import org.olcbox.app.util.nowMillis
import org.olcbox.app.vpn.TunnelHold
import org.olcbox.app.vpn.VpnStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The home screen with servers on its board and none of them selected: what a
 * refusal leaves when the refused list held the selected server
 * (LocationBundleV4.selectionWithdrawn). It used to read "no server list" and
 * "Add a server list to start", and its button opened the sheet that adds one,
 * though the person's lists were on the board under it; the likely next move
 * was to paste the refused link again, which changes nothing. It asks for a
 * pick, and its button goes to the board.
 */
@OptIn(ExperimentalTestApi::class)
class PickAServerTest {
    private val phone = Phone("iphone", 393, 852)
    private val app = AppFixture()

    @BeforeTest fun begin() = app.begin()
    @AfterTest fun end() = app.end()

    /** The fixture's lists, with no server selected: the selected one's list was refused. */
    private fun withdrawn(): LocationBundleV4 =
        AppFixture.stored().copy(activeLocationId = null, selectionWithdrawn = true)

    private fun DesktopComposeUiTest.texts(): List<String> = laidTexts().map { it.text }

    private fun DesktopComposeUiTest.whereTheLastRoomIs(): Float =
        laidTexts().first { it.text == LAST_ROOM }.node.positionInRoot.y

    @Test fun serversWithNoneSelectedAskForAPick() {
        app.onTheScreen(phone, app.models(Memory(withdrawn()))) {
            waitForIdle()
            val texts = texts()
            assertTrue("Pick a server" in texts, "the strip does not ask for a pick: $texts")
            assertFalse("Add a server list to start" in texts, "the strip asks for a list the person has: $texts")
            assertFalse("NO SERVER LIST" in texts, "the strip says there is no server list: $texts")
            // The bar's button is named by what it says. (The board's own
            // "Add server list" at its foot stays, as it is in every state.)
            onNodeWithContentDescription("PICK A SERVER").assertExists()
            assertTrue(
                onAllNodesWithContentDescription("ADD SERVER LIST").fetchSemanticsNodes().isEmpty(),
                "the bar's button still asks for a server list"
            )
        }
    }

    @Test fun theButtonGoesToTheBoardAndNeverToTheAddSheet() {
        app.onTheScreen(phone, app.models(Memory(withdrawn()))) {
            waitForIdle()
            val atTheTop = whereTheLastRoomIs()
            onNodeWithText(LAST_ROOM).performScrollTo()
            waitForIdle()
            val scrolled = whereTheLastRoomIs()
            assertTrue(scrolled < atTheTop - 100f, "the board did not scroll for the test: $atTheTop, then $scrolled")

            onNodeWithContentDescription("PICK A SERVER").performClick()
            waitForIdle()
            // The board scrolls back with an animation: run it to its end.
            mainClock.advanceTimeBy(2_000)
            waitForIdle()

            assertEquals(atTheTop, whereTheLastRoomIs(), 1f, "the button did not take the board back to its top")
            assertFalse("Paste link or URI" in texts(), "the button opened the sheet that adds a server list")
            assertEquals(0, app.toggled, "the button asked for a connection with nothing selected")
        }
    }

    // Android holds the tunnel until a server is picked: a network change came
    // after a refusal took the selected one. That is no reconnect on its way
    // (a spinner and CANCEL) but the pick, with Disconnect beside its button:
    // the pick's press goes to the board, Disconnect's ends the hold.
    @Test fun aTunnelHeldForAPickIsThePickWithDisconnectBesideIt() {
        val tunnel = Tunnel(upSince = nowMillis()).apply {
            hold.value = TunnelHold.NonePicked
            status.value = VpnStatus.Reconnecting
        }
        app.onTheScreen(phone, app.models(Memory(withdrawn()), tunnel)) {
            waitForIdle()
            val texts = texts()
            assertTrue("Pick a server" in texts, "the held tunnel does not ask for a pick: $texts")
            assertTrue(
                onAllNodesWithContentDescription("CANCEL").fetchSemanticsNodes().isEmpty(),
                "the hold is drawn as a reconnect on its way"
            )

            onNodeWithContentDescription("PICK A SERVER").performClick()
            waitForIdle()
            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            assertEquals(0, app.toggled, "the pick's button touched the tunnel")
            assertFalse("Paste link or URI" in texts(), "the pick's button opened the sheet that adds a server list")

            onNodeWithContentDescription("DISCONNECT").performClick()
            waitForIdle()
            assertEquals(1, app.toggled, "Disconnect did not end the hold")
        }
    }

    private companion object {
        /** The last room of the fixture's own list, near the foot of the board. */
        val LAST_ROOM = pkTied("US · olcRTC · WB")
    }
}
