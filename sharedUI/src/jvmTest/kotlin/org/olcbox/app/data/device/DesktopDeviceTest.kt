package org.olcbox.app.data.device

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDeviceTest {
    @Test
    fun theJvmsSystemNameBecomesOneOfTheThreeDesktopNames() {
        assertEquals("macOS", desktopOs("Mac OS X"))
        assertEquals("macOS", desktopOs("Darwin"), "\"darwin\" holds \"win\" and is still not Windows")
        assertEquals("Windows", desktopOs("Windows 11"))
        assertEquals("Windows", desktopOs("Windows Server 2022"))
        assertEquals("Linux", desktopOs("Linux"))
        assertEquals("Linux", desktopOs("FreeBSD"), "anything else is Linux")
        assertEquals("Linux", desktopOs(""))
    }
}
