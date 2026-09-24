package org.olcbox.app.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
            DesktopTrayIcon.look(DesktopOs.MacOS, windowsTaskbarIsLight = null)
        )
    }

    @Test fun windowsDrawsTheMarkInTheColourOfItsOwnTaskbarIcons() {
        assertEquals(
            TrayIconLook.Mark(Color.White, DesktopTrayIcon.WINDOWS_INK_FRACTION),
            DesktopTrayIcon.look(DesktopOs.Windows, windowsTaskbarIsLight = false)
        )
        assertEquals(
            TrayIconLook.Mark(Color.Black, DesktopTrayIcon.WINDOWS_INK_FRACTION),
            DesktopTrayIcon.look(DesktopOs.Windows, windowsTaskbarIsLight = true)
        )
    }

    @Test fun aTaskbarNobodyCouldReadKeepsTheTileThatShowsOnBoth() {
        assertEquals(TrayIconLook.AppIcon, DesktopTrayIcon.look(DesktopOs.Windows, windowsTaskbarIsLight = null))
    }

    @Test fun linuxKeepsTheColouredTile() {
        assertEquals(TrayIconLook.AppIcon, DesktopTrayIcon.look(DesktopOs.Linux, windowsTaskbarIsLight = null))
        assertEquals(TrayIconLook.AppIcon, DesktopTrayIcon.look(DesktopOs.Other, windowsTaskbarIsLight = true))
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
