package org.olcbox.app.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopWindowChromeTest {

    @Test fun theMacAsksForTemplateTrayImagesAndTheSystemAppearance() {
        assertEquals(
            mapOf(
                "apple.awt.enableTemplateImages" to "true",
                "apple.awt.application.appearance" to "system"
            ),
            DesktopWindowChrome.processProperties(DesktopOs.MacOS)
        )
    }

    @Test fun nothingProcessWideChangesOffTheMac() {
        assertTrue(DesktopWindowChrome.processProperties(DesktopOs.Windows).isEmpty())
        assertTrue(DesktopWindowChrome.processProperties(DesktopOs.Linux).isEmpty())
        assertTrue(DesktopWindowChrome.processProperties(DesktopOs.Other).isEmpty())
    }

    @Test fun onTheMacTheContentStartsBelowTheTitleBarItRunsUnder() {
        assertEquals(28.dp, DesktopWindowChrome.titleBarInset(DesktopOs.MacOS, fullscreen = false))
    }

    @Test fun fullScreenHasNoTitleBarToKeepClearOf() {
        assertEquals(0.dp, DesktopWindowChrome.titleBarInset(DesktopOs.MacOS, fullscreen = true))
    }

    @Test fun windowsAndLinuxKeepTheirTitleBarAboveTheContent() {
        assertEquals(0.dp, DesktopWindowChrome.titleBarInset(DesktopOs.Windows, fullscreen = false))
        assertEquals(0.dp, DesktopWindowChrome.titleBarInset(DesktopOs.Linux, fullscreen = false))
    }

    /** DWM reads 0x00BBGGRR: an ARGB int would swap red and blue, and its alpha byte is no COLORREF. */
    @Test fun theCaptionColourReachesDwmAsAColorref() {
        assertEquals(0x000D0807, WindowsCaption.colorRef(Color(0xFF07080D)))
        assertEquals(0x00F2ECE8, WindowsCaption.colorRef(Color(0xFFE8ECF2)))
        assertEquals(0x000000FF, WindowsCaption.colorRef(Color.Red))
    }
}
