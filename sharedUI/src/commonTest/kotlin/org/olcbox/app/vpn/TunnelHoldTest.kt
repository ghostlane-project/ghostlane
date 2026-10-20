package org.olcbox.app.vpn

import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Why Android's service holds a tunnel with nothing to connect, read off the
 * stored bundle, and what its notification says for it.
 */
class TunnelHoldTest {
    private fun room(id: String) = LocationEntry.from(
        storageId = id,
        location = LocationConfig(name = id, id = "room-$id", key = "c".repeat(64), bypassProvider = LocationConfig.PROVIDER_TELEMOST),
        subscriptionUrl = "https://example.com/ours"
    )

    // Servers there and none selected, as a refusal leaves it: the notification
    // asks for a pick, as the screen does.
    @Test
    fun serversWithNoneSelectedAskForAPick() {
        val withdrawn = LocationBundleV4(locations = listOf(room("a"), room("b")), selectionWithdrawn = true).normalized()
        assertEquals(TunnelHold.NonePicked, TunnelHold.of(withdrawn))
        assertEquals("Pick a server", TunnelHold.of(withdrawn).notificationText)
    }

    // Nothing at all to connect is what it was.
    @Test
    fun noLocationAtAllIsWhatItWas() {
        val empty = LocationBundleV4().normalized()
        assertEquals(TunnelHold.NoLocation, TunnelHold.of(empty))
        assertEquals("Add a location first", TunnelHold.of(empty).notificationText)
    }

    // A selection that cannot be built is no case of "none picked".
    @Test
    fun aSelectionThatCannotBeBuiltIsNoLocation() {
        val selected = LocationBundleV4(activeLocationId = "a", locations = listOf(room("a"))).normalized()
        assertEquals(TunnelHold.NoLocation, TunnelHold.of(selected))
    }
}
