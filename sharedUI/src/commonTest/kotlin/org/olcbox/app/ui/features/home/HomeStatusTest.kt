package org.olcbox.app.ui.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
