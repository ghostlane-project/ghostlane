package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DesktopVpnManagerWindowsTest {
    @Test
    fun windowsTunNamesSeparateProcessesAndRetries() {
        val first = windowsTunInterfaceName("aaaaaaaa", 1, 0)
        val retry = windowsTunInterfaceName("aaaaaaaa", 1, 1)
        val nextProcess = windowsTunInterfaceName("bbbbbbbb", 1, 0)

        assertEquals("Ghostlane-aaaaaaaa-1-1", first)
        assertNotEquals(first, retry)
        assertNotEquals(first, nextProcess)
    }
}
