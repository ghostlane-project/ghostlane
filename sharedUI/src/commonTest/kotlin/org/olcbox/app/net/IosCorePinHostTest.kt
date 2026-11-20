package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A server named by a hostname is resolved before the core starts (Android: on the
 * upstream network, outside the tun), and the line is written with the address. What
 * the name meant on the wire (SNI, the Host header) must not become the address.
 */
class IosCorePinHostTest {
    @Test
    fun theAddressReplacesTheHostAndTheNameKeepsItsWireMeanings() {
        val named = LinkParser.parse(
            "vless://33333333-3333-3333-3333-333333333333@edge.example.com:443" +
                "?security=tls&encryption=none&fp=chrome&type=ws&path=%2Fv&alpn=h2%2Chttp%2F1.1#x"
        ) as OutboundSpec.Vless
        assertTrue(IosCore.refusal(named) != null, "a hostname is refused as it stands")

        val pinned = IosCore.pinHost(named, "203.0.113.7") as OutboundSpec.Vless
        assertEquals("203.0.113.7", pinned.host)
        assertEquals("edge.example.com", pinned.sni)
        assertEquals("edge.example.com", (pinned.transport as TransportSpec.Ws).host)
        assertNull(IosCore.refusal(pinned), IosCore.refusal(pinned))
    }

    @Test
    fun anExplicitSniAndHostHeaderAreKept() {
        val line = IosCoreFixtures.spec(IosCoreLine.WebTunnelWs) as OutboundSpec.Vless
        val named = line.copy(host = "edge.example.com")
        val pinned = IosCore.pinHost(named, "203.0.113.7") as OutboundSpec.Vless
        assertEquals(line.sni, pinned.sni)
        assertEquals(line.transport, pinned.transport)
    }

    @Test
    fun hysteria2KeepsItsNameAsSni() {
        val h = (IosCoreFixtures.spec(IosCoreLine.Hysteria2) as OutboundSpec.Hysteria2).copy(host = "edge.example.com", sni = "")
        val pinned = IosCore.pinHost(h, "203.0.113.7") as OutboundSpec.Hysteria2
        assertEquals("203.0.113.7", pinned.host)
        assertEquals("edge.example.com", pinned.sni)
    }

    @Test
    fun anAddressNeedsNoPinning() {
        val line = IosCoreFixtures.spec(IosCoreLine.RealityVision)
        assertEquals(line, IosCore.pinHost(line, "127.0.0.1"))
    }
}
