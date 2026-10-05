package org.olcbox.app.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTrayIconTest {

    @Test fun theMacMenuBarGetsTheMarkInBlackForATemplateImage() {
        assertEquals(
            TrayIconLook.Mark(Color.Black, DesktopTrayIcon.MAC_INK_FRACTION),
            DesktopTrayIcon.look(DesktopOs.MacOS, windowsTaskbarIsLight = null, connected = true)
        )
    }

    /** A template image has no colour to turn; what it can be is fainter. */
    @Test fun theMacMenuBarDimsTheMarkWhileTheVpnIsDown() {
        assertEquals(
            TrayIconLook.Mark(
                Color.Black.copy(alpha = DesktopTrayIcon.MAC_IDLE_ALPHA),
                DesktopTrayIcon.MAC_INK_FRACTION
            ),
            DesktopTrayIcon.look(DesktopOs.MacOS, windowsTaskbarIsLight = null, connected = false)
        )
    }

    @Test fun windowsDrawsTheMarkInTheColourOfItsOwnTaskbarIcons() {
        assertEquals(
            TrayIconLook.Mark(Color.White, DesktopTrayIcon.WINDOWS_INK_FRACTION),
            DesktopTrayIcon.look(DesktopOs.Windows, windowsTaskbarIsLight = false, connected = false)
        )
        assertEquals(
            TrayIconLook.Mark(Color.Black, DesktopTrayIcon.WINDOWS_INK_FRACTION),
            DesktopTrayIcon.look(DesktopOs.Windows, windowsTaskbarIsLight = true, connected = false)
        )
    }

    @Test fun windowsTurnsTheMarkGreenWhileTheVpnIsUp() {
        assertEquals(
            TrayIconLook.Mark(DesktopTrayIcon.LIVE, DesktopTrayIcon.WINDOWS_INK_FRACTION),
            DesktopTrayIcon.look(DesktopOs.Windows, windowsTaskbarIsLight = false, connected = true)
        )
        assertEquals(
            TrayIconLook.Mark(DesktopTrayIcon.LIVE_ON_LIGHT, DesktopTrayIcon.WINDOWS_INK_FRACTION),
            DesktopTrayIcon.look(DesktopOs.Windows, windowsTaskbarIsLight = true, connected = true)
        )
    }

    /**
     * The green has to be findable on the taskbar it is drawn on. 3:1 is what
     * is asked of a graphic; the taskbars are Windows 11's two, near enough.
     */
    @Test fun theGreenStandsOutOnTheTaskbarItIsDrawnOn() {
        val darkTaskbar = Color(0xFF202020)
        val lightTaskbar = Color(0xFFF3F3F3)
        assertTrue(contrast(DesktopTrayIcon.LIVE, darkTaskbar) >= 3f)
        assertTrue(contrast(DesktopTrayIcon.LIVE_ON_LIGHT, lightTaskbar) >= 3f)
        assertTrue(contrast(DesktopTrayIcon.LIVE, lightTaskbar) < 3f, "lime on a light taskbar is why there are two greens")
    }

    @Test fun aTaskbarNobodyCouldReadKeepsTheTileThatShowsOnBoth() {
        assertEquals(
            TrayIconLook.AppIcon(live = false),
            DesktopTrayIcon.look(DesktopOs.Windows, windowsTaskbarIsLight = null, connected = false)
        )
        assertEquals(
            TrayIconLook.AppIcon(live = true),
            DesktopTrayIcon.look(DesktopOs.Windows, windowsTaskbarIsLight = null, connected = true)
        )
    }

    @Test fun linuxKeepsTheColouredTileAndMarksItWhileTheVpnIsUp() {
        assertEquals(
            TrayIconLook.AppIcon(live = false),
            DesktopTrayIcon.look(DesktopOs.Linux, windowsTaskbarIsLight = null, connected = false)
        )
        assertEquals(
            TrayIconLook.AppIcon(live = true),
            DesktopTrayIcon.look(DesktopOs.Linux, windowsTaskbarIsLight = null, connected = true)
        )
        assertEquals(
            TrayIconLook.AppIcon(live = false),
            DesktopTrayIcon.look(DesktopOs.Other, windowsTaskbarIsLight = true, connected = false)
        )
    }

    @Test fun offWindowsTheTaskbarIsUnknownRatherThanACrash() {
        if (DesktopPaths.os != DesktopOs.Windows) assertNull(WindowsTaskbar.isLight())
    }

    /**
     * AppKit keeps only the alpha of a template image. Colour anywhere in it is
     * a sign the wrong painter reached the menu bar — the coloured tile would
     * come out as a solid rounded square.
     */
    @Test fun theMenuBarImageIsBlackInkOnTransparency() {
        val image = renderAsTheTrayDoes(LaneMarkPainter(Color.Black, DesktopTrayIcon.MAC_INK_FRACTION))
        var inked = 0
        var clear = 0
        image.forEachPixel { argb ->
            val alpha = argb ushr 24
            if (alpha == 0) {
                clear++
            } else {
                inked++
                assertEquals(0, argb and 0xFFFFFF, "a template pixel with colour: 0x${argb.toUInt().toString(16)}")
            }
        }
        assertTrue(inked > 0, "nothing drawn")
        assertTrue(clear > inked, "the mark is a glyph on transparency, not a filled tile")
    }

    /** While the VPN is down the menu bar mark is the same black ink, at half strength and no more. */
    @Test fun theIdleMenuBarMarkIsTheSameInkAtHalfStrength() {
        val image = renderAsTheTrayDoes(
            LaneMarkPainter(Color.Black.copy(alpha = DesktopTrayIcon.MAC_IDLE_ALPHA), DesktopTrayIcon.MAC_INK_FRACTION)
        )
        var strongest = 0
        image.forEachPixel { argb ->
            val alpha = argb ushr 24
            if (alpha != 0) assertEquals(0, argb and 0xFFFFFF, "a template pixel with colour: 0x${argb.toUInt().toString(16)}")
            strongest = maxOf(strongest, alpha)
        }
        assertTrue(strongest in 110..140, "the idle ink is at $strongest of 255, meant about half")
    }

    /** The frame goes all round in the colour that means "up", and the tile inside it is the tile. */
    @Test fun theTileGetsALimeFrameWhileTheVpnIsUp() {
        val tile = ColorPainter(Color(0xFF6675FF))
        val live = renderAsTheTrayDoes(LiveFramePainter(tile))
        val last = live.width - 1
        val middle = live.width / 2
        val frame = (live.width * LiveFramePainter.FRAME).toInt()

        for ((x, y) in listOf(middle to 1, middle to last - 1, 1 to middle, last - 1 to middle)) {
            assertNear(0xB5F23D, live.getRGB(x, y) and 0xFFFFFF, "the frame at $x,$y")
        }
        assertNear(0x6675FF, live.getRGB(middle, middle) and 0xFFFFFF, "the middle of the tile")
        assertNear(0x6675FF, live.getRGB(middle, frame + 2) and 0xFFFFFF, "the tile just inside the frame")
        assertEquals(0, live.getRGB(0, 0) ushr 24, "outside the frame's round corner something was drawn")
    }

    /**
     * Centred, with room around it: AppKit scales the whole canvas to the menu
     * bar's height, so the margin is what keeps the mark the size of the icons
     * beside it instead of edge to edge.
     */
    @Test fun theMarkSitsCentredWithAMarginAllRound() {
        val image = renderAsTheTrayDoes(LaneMarkPainter(Color.Black, DesktopTrayIcon.MAC_INK_FRACTION))
        val ink = image.inkBounds()

        assertTrue(ink.left > 0 && ink.top > 0, "ink touches the top or left edge: $ink")
        assertTrue(ink.right < image.width - 1 && ink.bottom < image.height - 1, "ink touches the bottom or right edge: $ink")
        assertTrue(abs(ink.left - (image.width - 1 - ink.right)) <= 1, "not centred across: $ink")
        assertTrue(abs(ink.top - (image.height - 1 - ink.bottom)) <= 1, "not centred down: $ink")

        val inkWidth = (ink.right - ink.left + 1).toFloat() / image.width
        assertTrue(
            abs(inkWidth - DesktopTrayIcon.MAC_INK_FRACTION) <= 0.05f,
            "ink spans $inkWidth of the canvas, meant ${DesktopTrayIcon.MAC_INK_FRACTION}"
        )
    }

    /**
     * Through the same call Compose's `Tray` makes — a 22 x 22 image, asked for
     * the Retina variant AppKit uses on a 2x menu bar.
     */
    private fun renderAsTheTrayDoes(painter: Painter): BufferedImage {
        val image = painter.toAwtImage(Density(2f), LayoutDirection.Ltr, Size(22f, 22f))
        val retina = (image as MultiResolutionImage).getResolutionVariant(44.0, 44.0)
        return retina as BufferedImage
    }

    private fun contrast(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** Within a couple of steps a channel: a renderer is allowed its rounding. */
    private fun assertNear(expected: Int, actual: Int, what: String) {
        for (shift in intArrayOf(16, 8, 0)) {
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            assertTrue(abs(e - a) <= 2, "$what is 0x${actual.toString(16)}, meant 0x${expected.toString(16)}")
        }
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun BufferedImage.inkBounds(): Bounds {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (getRGB(x, y) ushr 24 != 0) {
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        return Bounds(left, top, right, bottom)
    }

    private inline fun BufferedImage.forEachPixel(action: (Int) -> Unit) {
        for (y in 0 until height) for (x in 0 until width) action(getRGB(x, y))
    }
}
