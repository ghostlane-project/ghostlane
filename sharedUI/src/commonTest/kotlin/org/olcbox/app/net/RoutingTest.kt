package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingTest {
    @Test fun firstIPv4Wins() {
        assertEquals("192.0.2.5", DirectDns.Servers(listOf("fe80::1%wlan0", "192.0.2.5", "192.0.2.6")).pick())
    }

    @Test fun ipv6WithoutAZoneIsAcceptedWhenThereIsNoIPv4() {
        assertEquals("2001:db8::53", DirectDns.Servers(listOf("2001:db8::53%en0")).pick())
    }

    @Test fun linkLocalKeepsItsZoneAndComesLast() {
        // An IPv6-only Wi-Fi advertises its router's link-local address as the
        // resolver; sing-box dials it only with the zone, so the zone stays.
        assertEquals("fe80::1%wlan0", DirectDns.Servers(listOf("fe80::1%wlan0", "127.0.0.1", "::1")).pick())
        assertEquals("192.0.2.5", DirectDns.Servers(listOf("fe80::1%wlan0", "192.0.2.5")).pick())
        assertEquals("2001:db8::53", DirectDns.Servers(listOf("fe80::1%wlan0", "2001:db8::53")).pick())
    }

    @Test fun linkLocalWithoutAZoneAndLoopbackAreNotResolvers() {
        // Without its zone a link-local address names no interface, and
        // loopback inside the tunnel process is the tunnel itself.
        assertEquals(
            RoutingDefaults.DIRECT_DNS_FALLBACK,
            DirectDns.Servers(listOf("fe80::1", "127.0.0.1", "::1")).pick()
        )
    }

    @Test fun nothingOfferedFallsBackToThePublicResolver() {
        assertEquals(RoutingDefaults.DIRECT_DNS_FALLBACK, DirectDns.Servers(emptyList()).pick())
        assertEquals(RoutingDefaults.DIRECT_DNS_FALLBACK, DirectDns.Servers(listOf(" ", "")).pick())
    }
}
