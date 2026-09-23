package org.olcbox.app.vpn

import org.olcbox.app.data.model.LocationConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Whether the engine relays UDP ASSOCIATE for a room. Wrong one way, a Jitsi
// room parks every UDP flow on a lane that never opens until the engine runs
// out of SOCKS slots and takes no TCP either; wrong the other way, a carrier
// that has the lane carries no UDP.
class OlcRtcUdpRelayTest {
    // The jitsi engine has no datagram lane behind datachannel, its only transport.
    @Test
    fun jitsiRunsWithTheRelayOff() {
        assertFalse(OlcRtcUdpRelay.enabled(LocationConfig.PROVIDER_JITSI, LocationConfig.TRANSPORT_DATACHANNEL))
        assertFalse(OlcRtcUdpRelay.enabled("meet", "dc"))
        // A link asking Jitsi for vp8 still runs over datachannel.
        assertFalse(OlcRtcUdpRelay.enabled(LocationConfig.PROVIDER_JITSI, LocationConfig.TRANSPORT_VP8CHANNEL))
    }

    // What the service passes: the provider and transport of the normalized location.
    @Test
    fun aNormalizedJitsiLocationRunsWithTheRelayOff() {
        val config = LocationConfig(
            bypassProvider = "jitsi-meet",
            transport = LocationConfig.TRANSPORT_VP8CHANNEL
        ).normalized()
        assertFalse(OlcRtcUdpRelay.enabled(config.bypassProvider, config.transport))
    }

    @Test
    fun carriersWithALaneKeepTheRelay() {
        val withLane = listOf(
            LocationConfig.PROVIDER_WB_STREAM to LocationConfig.TRANSPORT_DATACHANNEL,
            LocationConfig.PROVIDER_WB_STREAM to LocationConfig.TRANSPORT_VP8CHANNEL,
            LocationConfig.PROVIDER_SALUTEJAZZ to LocationConfig.TRANSPORT_DATACHANNEL,
            LocationConfig.PROVIDER_TELEMOST to LocationConfig.TRANSPORT_VP8CHANNEL
        )
        for ((provider, transport) in withLane) {
            assertTrue(OlcRtcUdpRelay.enabled(provider, transport), "$provider/$transport")
        }
    }

    // seichannel has no datagram methods; the engine refuses there by itself,
    // at once, so the relay is left as it is.
    @Test
    fun seichannelIsLeftToTheEngine() {
        assertTrue(OlcRtcUdpRelay.enabled(LocationConfig.PROVIDER_TELEMOST, LocationConfig.TRANSPORT_SEICHANNEL))
        assertTrue(OlcRtcUdpRelay.enabled(LocationConfig.PROVIDER_WB_STREAM, LocationConfig.TRANSPORT_SEICHANNEL))
    }
}
