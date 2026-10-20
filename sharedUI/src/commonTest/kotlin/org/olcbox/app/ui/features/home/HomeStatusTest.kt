package org.olcbox.app.ui.features.home

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.ui.components.kit.PkAction
import org.olcbox.app.ui.components.kit.PkActionKind
import org.olcbox.app.ui.components.kit.boardSecondaryAction
import org.olcbox.app.vpn.TunnelHold
import org.olcbox.app.vpn.VpnStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the status strip says, and where its one button goes, when there is
 * nothing to connect to: no list at all, or lists whose servers are on the
 * board with none of them selected, as a refusal of the selected server's list
 * leaves it.
 */
class HomeStatusTest {
    private fun label(requiresSetup: Boolean, pickServer: Boolean) =
        statusLabel(isConnected = false, isConnecting = false, requiresSetup = requiresSetup, hasSeats = false, transportLabel = null, pickServer = pickServer)

    private fun meta(requiresSetup: Boolean, pickServer: Boolean) =
        statusMeta(isConnected = false, bytesLine = "", requiresSetup = requiresSetup, isFull = false, protocolLine = null, pickServer = pickServer)

    @Test
    fun noListAtAllAsksForOne() {
        assertEquals("no server list", label(requiresSetup = true, pickServer = false))
        assertEquals("Add a server list to start", meta(requiresSetup = true, pickServer = false))
        assertEquals(ActionTarget.AddServerList, actionTarget(requiresSetup = true, pickServer = false, disclosureAccepted = true, isConnected = false))
    }

    @Test
    fun serversWithNoneSelectedAskForAPick() {
        assertEquals("not connected", label(requiresSetup = true, pickServer = true))
        assertEquals("Pick a server", meta(requiresSetup = true, pickServer = true))
    }

    // The person has lists; adding the refused one again changes nothing. The
    // button takes them to the board, before the disclosure is even asked for.
    @Test
    fun serversWithNoneSelectedNeverOpenTheAddSheet() {
        for (accepted in listOf(true, false)) {
            assertEquals(
                ActionTarget.Board,
                actionTarget(requiresSetup = true, pickServer = true, disclosureAccepted = accepted, isConnected = false)
            )
        }
    }

    @Test
    fun aSelectedServerIsConnectedAsBefore() {
        assertEquals(ActionTarget.Connection, actionTarget(requiresSetup = false, pickServer = false, disclosureAccepted = true, isConnected = false))
        assertEquals(ActionTarget.Disclosure, actionTarget(requiresSetup = false, pickServer = false, disclosureAccepted = false, isConnected = false))
        assertEquals(ActionTarget.Connection, actionTarget(requiresSetup = false, pickServer = false, disclosureAccepted = false, isConnected = true))
        assertEquals("not connected", label(requiresSetup = false, pickServer = false))
    }

    // ── a tunnel Android holds for a pick ─────────────────────────────────

    /** What the model holds with nothing selected: a refusal took the selected server. */
    private val nothingSelected = HomeScreenState(
        isVpnConnected = true,
        isVpnLoading = false,
        selectedLocation = null,
        configData = LocationConfig(),
        shouldShowConfigInvalidReminder = false,
        canStartVpn = false,
        startBlockedReason = null
    )

    // The service holds the interface until a server is picked (traffic waits).
    // That is no reconnect on its way: the screen is the pick (its strip, its
    // button to the board), with Disconnect beside it to end the hold.
    @Test
    fun aTunnelHeldForAPickShowsThePickAndOffersDisconnect() {
        val held = nothingSelected.applying(VpnStatus.Reconnecting, TunnelHold.NonePicked)
        assertTrue(held.heldForPick)
        assertTrue(held.picksServer(hasLocations = true, selectedId = null), "the hold is not the pick: $held")
        assertEquals("not connected", statusLabel(held.isVpnConnected, held.isVpnLoading, held.requiresSetup, false, null, pickServer = true))
        assertEquals("Pick a server", statusMeta(held.isVpnConnected, "", held.requiresSetup, false, null, pickServer = true))
        assertEquals(ActionTarget.Board, actionTarget(held.requiresSetup, pickServer = true, disclosureAccepted = true, isConnected = held.isVpnConnected))
        assertTrue(held.offersDisconnect)
        assertEquals(PkAction("DISCONNECT", PkActionKind.Stop), boardSecondaryAction(held.offersDisconnect))
    }

    // A hold for no location at all, and a reconnect, are what they were: a
    // reconnect on its way (CANCEL), and no second button.
    @Test
    fun otherHoldsAndReconnectsAreWhatTheyWere() {
        for (hold in listOf(TunnelHold.NoLocation, null)) {
            val state = nothingSelected.applying(VpnStatus.Reconnecting, hold)
            assertTrue(state.isVpnConnected && state.isVpnLoading, "$hold")
            assertFalse(state.picksServer(hasLocations = true, selectedId = null), "$hold")
            assertFalse(state.offersDisconnect, "$hold")
            assertNull(boardSecondaryAction(state.offersDisconnect))
        }
    }

    // Whatever the platform says next ends the hold: the person picked a
    // server (the tunnel connects), or ended it.
    @Test
    fun theHoldEndsWithTheNextStatus() {
        val held = nothingSelected.applying(VpnStatus.Reconnecting, TunnelHold.NonePicked)
        for (next in listOf(VpnStatus.Connected, VpnStatus.Connecting, VpnStatus.Stopping, VpnStatus.Disconnected, VpnStatus.Error("x"))) {
            assertFalse(held.applying(next).offersDisconnect, "$next")
        }
        assertFalse(held.applying(VpnStatus.Reconnecting, null).offersDisconnect, "a reconnect that is one")
    }
}
