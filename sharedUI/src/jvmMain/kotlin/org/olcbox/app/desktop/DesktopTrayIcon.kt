package org.olcbox.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The icon the app keeps in the macOS menu bar or the Windows / Linux tray.
 *
 * It was the coloured launcher tile everywhere, which in the macOS menu bar is
 * the one coloured icon in a row of monochrome ones. Now:
 *
 * - macOS: the lane mark in black on transparency, handed to AppKit as a
 *   template image (see `apple.awt.enableTemplateImages` in
 *   [DesktopWindowChrome.installProcessProperties]), so the system tints it
 *   exactly like its neighbours in every state the menu bar has — white on a
 *   dark bar, black on a light one, dimmed on an inactive display, on the
 *   highlight while its menu is open.
 * - Windows: the same mark in the colour the taskbar's own icons use — white on
 *   a dark taskbar, black on a light one — read from the setting Windows itself
 *   follows. White alone would vanish on Windows 11's default light taskbar.
 *   When that setting cannot be read, the coloured tile, which shows on both.
 * - Linux: the coloured tile. Panels are light or dark by theme and desktop,
 *   there is no setting that says which, and an XEmbed tray often paints its own
 *   background behind the icon anyway.
 */
internal sealed interface TrayIconLook {
    /** The lane mark alone, in one colour, its ink [inkFraction] of the canvas. */
    data class Mark(val color: Color, val inkFraction: Float) : TrayIconLook

    /** The coloured launcher tile. */
    data object AppIcon : TrayIconLook
}

internal object DesktopTrayIcon {

    /**
     * Compose's `Tray` asks AppKit to auto-size the image, which scales the
     * whole canvas to the menu bar's height less a point (21 pt on a 22 pt
     * bar). At 0.8 the mark is about 17 x 14 pt inside it, about the size of
     * the system glyphs beside it, rather than a mark filling the bar edge to
     * edge.
     */
    const val MAC_INK_FRACTION = 0.80f

    /** A 16 px tray slot is filled nearly edge to edge, as the system icons are. */
    const val WINDOWS_INK_FRACTION = 0.94f

    fun look(os: DesktopOs, windowsTaskbarIsLight: Boolean?): TrayIconLook = when (os) {
        // Black by convention: a template image is judged by its alpha alone.
        DesktopOs.MacOS -> TrayIconLook.Mark(Color.Black, MAC_INK_FRACTION)
        DesktopOs.Windows -> when (windowsTaskbarIsLight) {
            true -> TrayIconLook.Mark(Color.Black, WINDOWS_INK_FRACTION)
            false -> TrayIconLook.Mark(Color.White, WINDOWS_INK_FRACTION)
            null -> TrayIconLook.AppIcon
        }
        DesktopOs.Linux, DesktopOs.Other -> TrayIconLook.AppIcon
    }
}

/** Often enough that switching Windows between light and dark is followed within a breath. */
private const val WINDOWS_THEME_POLL_MS = 3_000L

/**
 * The tray icon for this platform; [appIcon] wherever the coloured tile is
 * still the right answer.
 */
@Composable
fun rememberDesktopTrayIcon(appIcon: Painter): Painter {
    val os = remember { DesktopPaths.os }
    var windowsTaskbarIsLight by remember {
        mutableStateOf(if (os == DesktopOs.Windows) WindowsTaskbar.isLight() else null)
    }
    if (os == DesktopOs.Windows) {
        // There is no event for this short of a window procedure of our own;
        // reading one registry value is cheaper than anything that would need.
        LaunchedEffect(Unit) {
            while (true) {
                delay(WINDOWS_THEME_POLL_MS)
                windowsTaskbarIsLight = WindowsTaskbar.isLight()
            }
        }
    }
    return when (val look = DesktopTrayIcon.look(os, windowsTaskbarIsLight)) {
        // Remembered per look: Compose's Tray rebuilds the native image only
        // when it is handed a different painter.
        is TrayIconLook.Mark -> remember(look) { LaneMarkPainter(look.color, look.inkFraction) }
        TrayIconLook.AppIcon -> appIcon
    }
}

/**
 * The lane mark in one colour: two edges on a 32° diagonal and three dots
 * running along it, fading behind the front one.
 *
 * The geometry is `tools/render-appicons.py`'s, in its 32-unit box — change the
 * two together. Only the edge stroke is heavier: at menu bar size the icon's 1.5
 * units come out as a one-point hairline beside system glyphs drawn at about
 * one and a half.
 *
 * Drawn rather than shipped as a PNG: Compose hands AWT a vector painter at 1x
 * and at the screen's scale, while a bitmap gets one 22 px variant that a
 * Retina menu bar then blows up.
 */
internal class LaneMarkPainter(
    private val color: Color,
    private val inkFraction: Float
) : Painter() {

    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        // The farthest ink from the centre: an edge's end, plus its round cap.
        val halfInkWidth = LANE_HALF * AXIS_X + LANE_OFFSET * AXIS_RISE + STROKE / 2f
        val halfInkHeight = LANE_HALF * AXIS_RISE + LANE_OFFSET * AXIS_X + STROKE / 2f
        val unit = min(
            size.width * inkFraction / (2f * halfInkWidth),
            size.height * inkFraction / (2f * halfInkHeight)
        )
        val centre = Offset(size.width / 2f, size.height / 2f)
        // Up and to the right; y grows downwards on a canvas.
        val along = Offset(AXIS_X, -AXIS_RISE) * unit
        val across = Offset(AXIS_RISE, AXIS_X) * (LANE_OFFSET * unit)

        for (side in floatArrayOf(-1f, 1f)) {
            val offset = across * side
            drawLine(
                color = color,
                start = centre - along * LANE_HALF + offset,
                end = centre + along * LANE_HALF + offset,
                strokeWidth = STROKE * unit,
                cap = StrokeCap.Round
            )
        }
        for (dot in LANE_DOTS) {
            drawCircle(
                color = color.copy(alpha = color.alpha * dot.opacity),
                radius = dot.radius * unit,
                center = centre + along * (LANE_HALF * dot.position)
            )
        }
    }

    private class Dot(val position: Float, val radius: Float, val opacity: Float)

    private companion object {
        private const val ANGLE = 32.0 * PI / 180.0
        val AXIS_X = cos(ANGLE).toFloat()
        val AXIS_RISE = sin(ANGLE).toFloat()

        const val LANE_HALF = 11f
        const val LANE_OFFSET = 4.2f
        const val STROKE = 2.0f

        /** Position along the axis in units of [LANE_HALF], radius, opacity. */
        val LANE_DOTS = listOf(
            Dot(position = -0.78f, radius = 1.6f, opacity = 0.30f),
            Dot(position = -0.22f, radius = 2.0f, opacity = 0.62f),
            Dot(position = 0.50f, radius = 2.6f, opacity = 1.0f)
        )
    }
}

/**
 * Whether the Windows taskbar is light, the way the system's own tray icons
 * decide: `SystemUsesLightTheme` under the current user's Personalize key.
 */
internal object WindowsTaskbar {

    private const val PERSONALIZE_KEY =
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
    private const val SYSTEM_USES_LIGHT_THEME = "SystemUsesLightTheme"

    private const val ERROR_SUCCESS = 0
    private const val ERROR_FILE_NOT_FOUND = 2
    private const val RRF_RT_REG_DWORD = 0x00000010

    /** HKEY_CURRENT_USER is `(HKEY)(ULONG_PTR)(LONG)0x80000001`: sign-extended on 64-bit. */
    private val HKEY_CURRENT_USER = Pointer(0x80000001L.toInt().toLong())

    @Suppress("FunctionName")
    private interface Advapi32 : StdCallLibrary {
        fun RegGetValueW(
            key: Pointer,
            subKey: WString,
            value: WString,
            flags: Int,
            type: IntByReference?,
            data: IntByReference,
            dataSize: IntByReference
        ): Int
    }

    private val advapi32: Advapi32? by lazy {
        runCatching { Native.load("advapi32", Advapi32::class.java) }.getOrNull()
    }

    /**
     * True for a light taskbar, false for a dark one — also when the value is
     * missing, which it is on Windows 10 releases older than the light taskbar
     * (1903). Null when it cannot be read at all, and then nobody guesses.
     */
    fun isLight(): Boolean? {
        val api = advapi32 ?: return null
        return runCatching {
            val data = IntByReference()
            val size = IntByReference(Int.SIZE_BYTES)
            when (
                api.RegGetValueW(
                    HKEY_CURRENT_USER,
                    WString(PERSONALIZE_KEY),
                    WString(SYSTEM_USES_LIGHT_THEME),
                    RRF_RT_REG_DWORD,
                    null,
                    data,
                    size
                )
            ) {
                ERROR_SUCCESS -> data.value != 0
                ERROR_FILE_NOT_FOUND -> false
                else -> null
            }
        }.getOrNull()
    }
}
