package org.olcbox.app.vpn.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A session on a core's line leaves its server's address out of the tun
// (route_exclude_address). sing-tun then installs the whole space minus that
// address, as go4.org/netipx breaks it into prefixes; the lists below are what
// that library returns, printed by a program and not worked out by hand.
class WindowsTunRoutesTest {
    /** What Windows puts on the adapter by itself, before and beside the tun's own routes. */
    private val adaptersOwn = listOf("172.19.0.0/30", "172.19.0.1/32", "172.19.0.3/32", "224.0.0.0/4", "255.255.255.255/32")

    /** `0.0.0.0/0` minus `203.0.113.7/32`. */
    private val minusOneServer = listOf(
        "0.0.0.0/1", "128.0.0.0/2", "192.0.0.0/5", "200.0.0.0/7", "202.0.0.0/8", "203.0.0.0/18", "203.0.64.0/19",
        "203.0.96.0/20", "203.0.112.0/24", "203.0.113.0/30", "203.0.113.4/31", "203.0.113.6/32", "203.0.113.8/29",
        "203.0.113.16/28", "203.0.113.32/27", "203.0.113.64/26", "203.0.113.128/25", "203.0.114.0/23",
        "203.0.116.0/22", "203.0.120.0/21", "203.0.128.0/17", "203.1.0.0/16", "203.2.0.0/15", "203.4.0.0/14",
        "203.8.0.0/13", "203.16.0.0/12", "203.32.0.0/11", "203.64.0.0/10", "203.128.0.0/9", "204.0.0.0/6",
        "208.0.0.0/4", "224.0.0.0/3"
    )

    @Test
    fun aTunThatLeavesNothingOutOwnsTheDefaultRoute() {
        assertTrue(WindowsTunController.coversDefaultRoute(adaptersOwn + "0.0.0.0/0"))
        assertTrue(WindowsTunController.coversDefaultRoute(adaptersOwn + listOf("0.0.0.0/1", "128.0.0.0/1")))
    }

    // The bug: this list has one half of the space as a prefix, not both, and
    // no default route, and the old question was "0.0.0.0/0, or both halves".
    @Test
    fun aTunThatLeavesItsServerOutOwnsItToo() {
        assertFalse("0.0.0.0/0" in minusOneServer)
        assertFalse("128.0.0.0/1" in minusOneServer)
        assertTrue(WindowsTunController.coversDefaultRoute(adaptersOwn + minusOneServer))
        assertTrue(WindowsTunController.coversDefaultRoute(minusOneServer.shuffled(java.util.Random(7))))
    }

    @Test
    fun aTunWhoseRoutesAreNotInYetDoesNot() {
        assertFalse(WindowsTunController.coversDefaultRoute(emptyList()))
        assertFalse(WindowsTunController.coversDefaultRoute(adaptersOwn))
        // Half way through: sing-box adds them one at a time.
        assertFalse(WindowsTunController.coversDefaultRoute(adaptersOwn + minusOneServer.take(20)))
        assertFalse(WindowsTunController.coversDefaultRoute(adaptersOwn + minusOneServer.drop(1)))
        assertFalse(WindowsTunController.coversDefaultRoute(adaptersOwn + "0.0.0.0/1"))
    }

    @Test
    fun whatMayBeLeftOutIsAHandfulOfAddresses() {
        // A whole /24 left out is still a server's worth; one address more is not.
        val minusA24 = listOf("0.0.0.0/1", "128.0.0.0/2", "192.0.0.0/5", "200.0.0.0/7", "202.0.0.0/8", "203.0.0.0/18",
            "203.0.64.0/19", "203.0.96.0/20", "203.0.112.0/24", "203.0.114.0/23", "203.0.116.0/22", "203.0.120.0/21",
            "203.0.128.0/17", "203.1.0.0/16", "203.2.0.0/15", "203.4.0.0/14", "203.8.0.0/13", "203.16.0.0/12",
            "203.32.0.0/11", "203.64.0.0/10", "203.128.0.0/9", "204.0.0.0/6", "208.0.0.0/4", "224.0.0.0/3")
        assertTrue(WindowsTunController.coversDefaultRoute(minusA24))
        assertFalse(WindowsTunController.coversDefaultRoute(minusA24, spared = 255))
    }

    @Test
    fun whatPowerShellPrintsBesideThePrefixesIsPassedOver() {
        val printed = listOf("", "  0.0.0.0/0  ", "DestinationPrefix", "::/0", "999.0.0.0/8", "10.0.0.0/33", "not a route")
        assertTrue(WindowsTunController.coversDefaultRoute(printed))
        assertFalse(WindowsTunController.coversDefaultRoute(printed - "  0.0.0.0/0  "))
    }
}
