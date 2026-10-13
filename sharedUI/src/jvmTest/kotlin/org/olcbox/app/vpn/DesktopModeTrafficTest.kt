package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which desktop sessions have their traffic counted, which is what the status
 * strip asks before it keeps room for a chart.
 */
class DesktopModeTrafficTest {
    @Test fun aTunnelIsCountedOnEverySystemThatHasOne() {
        assertTrue(DesktopMode.LinuxTun.countsTraffic)
        assertTrue(DesktopMode.WindowsTun.countsTraffic)
        assertTrue(DesktopMode.MacTun.countsTraffic)
    }

    @Test fun aProxyIsNot() {
        // Nothing stands in a proxy session's path that keeps count: the
        // system's proxy setting points at the line itself.
        assertFalse(DesktopMode.SystemProxy.countsTraffic)
    }
}
