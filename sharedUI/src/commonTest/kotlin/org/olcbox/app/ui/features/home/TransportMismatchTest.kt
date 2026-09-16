package org.olcbox.app.ui.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.LocationMetadata

// A link can ask for a transport its provider cannot carry - vp8channel on a
// Jitsi room - and the app runs the room over the one it can. Saying so is the
// difference between "my server is misconfigured" and "the app is broken".
class TransportMismatchTest {
    private val jitsi = LocationConfig(
        name = "Room",
        id = "https://meet.test/room",
        key = "a".repeat(64),
        bypassProvider = LocationConfig.PROVIDER_JITSI,
        transport = LocationConfig.TRANSPORT_DATACHANNEL
    )

    @Test
    fun aRoomWhoseLinkAskedForAnotherTransportSaysSo() {
        val entry = LocationEntry.from(
            storageId = "room",
            location = jitsi,
            subscriptionUrl = null,
            metadata = LocationMetadata(requestedTransport = LocationConfig.TRANSPORT_VP8CHANNEL)
        )
        assertEquals("LINK ASKS FOR VP8 · JITSI RUNS DATACHANNEL", TransportMismatch.caption(entry))
        assertEquals(
            "This link asks for VP8, and Ghostlane runs Jitsi rooms over DataChannel. " +
                "Set the server's transport to datachannel, then connect.",
            TransportMismatch.explanation(entry)
        )
    }

    @Test
    fun aRoomWithoutSuchARequestHasNothingToSay() {
        val entry = LocationEntry.from(storageId = "room", location = jitsi, subscriptionUrl = null, metadata = null)
        assertNull(TransportMismatch.caption(entry))
        assertNull(TransportMismatch.explanation(entry))
    }
}
