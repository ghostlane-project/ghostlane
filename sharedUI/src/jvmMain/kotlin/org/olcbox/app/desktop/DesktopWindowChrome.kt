package org.olcbox.app.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import org.olcbox.app.ui.theme.OlcboxDarkColorScheme
import java.awt.Window
import java.awt.event.HierarchyEvent
import javax.swing.JFrame

/**
 * The desktop window's own chrome, made to read as part of the app.
 *
 * The app is dark and the window around it was not: the JDK pins every macOS
 * AWT application to the light Aqua appearance unless told otherwise (its Swing
 * look and feel cannot draw dark mode, JDK-8231438), so the title bar was a
 * light strip over a near-black body. Windows draws a light caption too: a dark
 * one is opt-in, window by window, and nothing opted in.
 *
 * - macOS: the content runs up under a transparent title bar with its title
 *   hidden, so the traffic lights sit on the app's background. The bar is still
 *   AppKit's: pressing on it drags the window and a double click does what the
 *   system setting says, which is why the screens keep their content below
 *   [titleBarInset] (the kit's `LocalWindowTitleBarInset`). All three client
 *   properties ship in the JDK we bundle (21): full content and the transparent
 *   bar since 12 (JDK-8211301), the hidden title since 17 (JDK-8265005).
 * - Windows: DWM is asked for a dark caption, and on Windows 11 for a caption in
 *   the app's own background colour — no custom-drawn title bar, so snapping,
 *   the system menu and the caption buttons stay the system's.
 * - Linux: the window manager draws the frame and is left alone.
 */
object DesktopWindowChrome {

    /**
     * The title bar AppKit draws over a full-content window: 28 pt on every
     * macOS since 11. A Compose dp is an AppKit point on the Mac.
     */
    val MacTitleBarHeight: Dp = 28.dp

    private const val FULL_WINDOW_CONTENT = "apple.awt.fullWindowContent"
    private const val TRANSPARENT_TITLE_BAR = "apple.awt.transparentTitleBar"
    private const val WINDOW_TITLE_VISIBLE = "apple.awt.windowTitleVisible"

    /**
     * Process-wide switches the JDK reads once, so this has to run before
     * anything touches AWT — first thing in `main`, ahead of `application {}`.
     *
     * A value already given on the command line (`-D…`) wins, which is also the
     * way to compare against the JDK's defaults on a real Mac.
     */
    fun installProcessProperties() {
        for ((key, value) in processProperties(DesktopPaths.os)) {
            if (System.getProperty(key) == null) System.setProperty(key, value)
        }
    }

    internal fun processProperties(os: DesktopOs): Map<String, String> = when (os) {
        DesktopOs.MacOS -> mapOf(
            // The tray icon becomes a template image: AppKit keeps its alpha
            // and tints it like every other menu bar icon — white on a dark
            // bar, black on a light one. Read once, when the first tray icon's
            // peer class loads (JDK-8252015, JDK 17+).
            "apple.awt.enableTemplateImages" to "true",
            // Follow the system appearance instead of the forced light Aqua
            // (JDK-8231438, JDK 14+): the tray's menu, the file dialogs and the
            // traffic lights then match the rest of the Mac. Safe here because
            // nothing on screen is a Swing component — the window is Compose,
            // the dialogs and menus are native. Read when AWT launches
            // NSApplication.
            "apple.awt.application.appearance" to "system"
        )
        DesktopOs.Windows, DesktopOs.Linux, DesktopOs.Other -> emptyMap()
    }

    /**
     * Called with the window before it has a native peer (Compose's
     * `SwingWindow(init = …)`): AWT builds the NSWindow's style from these
     * client properties when it creates it.
     */
    fun prepare(window: JFrame) = prepare(window, DesktopPaths.os)

    internal fun prepare(window: JFrame, os: DesktopOs) {
        // What shows before Compose draws its first frame and at the edge that
        // a live resize uncovers, on every platform: the app's ground, not the
        // look and feel's light grey.
        window.background = java.awt.Color(OlcboxDarkColorScheme.background.toArgb(), true)
        when (os) {
            DesktopOs.MacOS -> window.rootPane.apply {
                putClientProperty(FULL_WINDOW_CONTENT, true)
                putClientProperty(TRANSPARENT_TITLE_BAR, true)
                putClientProperty(WINDOW_TITLE_VISIBLE, false)
            }
            DesktopOs.Windows -> WindowsCaption.applyOnceDisplayable(window)
            DesktopOs.Linux, DesktopOs.Other -> Unit
        }
    }

    /**
     * How far the app keeps its content below the top edge of the window.
     *
     * Zero in full screen: macOS hides the title bar there and slides it back
     * over the content only while the pointer is at the top of the screen, the
     * way it does for every app.
     */
    fun titleBarInset(fullscreen: Boolean): Dp = titleBarInset(DesktopPaths.os, fullscreen)

    internal fun titleBarInset(os: DesktopOs, fullscreen: Boolean): Dp =
        if (os == DesktopOs.MacOS && !fullscreen) MacTitleBarHeight else 0.dp
}

/**
 * The Windows caption, through DWM — the one part of the frame an application
 * may recolour without drawing the whole title bar itself.
 */
internal object WindowsCaption {

    // dwmapi.h. 20 is the documented dark-mode attribute since Windows 10 20H1;
    // 1809 to 1909 answer to 19 for the same thing.
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19

    // Windows 11 (build 22000) and later; Windows 10 rejects them, harmlessly.
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    private const val S_OK = 0

    @Suppress("FunctionName")
    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwmapi: Dwmapi? by lazy {
        runCatching { Native.load("dwmapi", Dwmapi::class.java) }.getOrNull()
    }

    /**
     * Applies the colours the moment the window has an HWND and before it is
     * first shown — `addNotify` reports that synchronously — so the caption
     * never paints light first. Again after any later re-creation of the peer.
     */
    fun applyOnceDisplayable(window: Window) {
        window.addHierarchyListener { event ->
            val displayabilityChanged =
                (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L
            if (displayabilityChanged && window.isDisplayable) apply(window)
        }
    }

    private fun apply(window: Window) {
        // Cosmetic through and through: a DWM that is missing or says no leaves
        // the stock caption, never a window that fails to open.
        runCatching {
            val dwm = dwmapi ?: return
            val hwnd = Native.getWindowPointer(window)
            if (hwnd == null || Pointer.nativeValue(hwnd) == 0L) return
            if (dwm.set(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, 1) != S_OK) {
                dwm.set(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1, 1)
            }
            dwm.set(hwnd, DWMWA_CAPTION_COLOR, colorRef(OlcboxDarkColorScheme.background))
            dwm.set(hwnd, DWMWA_TEXT_COLOR, colorRef(OlcboxDarkColorScheme.onBackground))
        }
    }

    private fun Dwmapi.set(hwnd: Pointer, attribute: Int, value: Int): Int =
        DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), Int.SIZE_BYTES)

    /** A Win32 COLORREF: 0x00BBGGRR, red in the low byte. */
    fun colorRef(color: Color): Int {
        val argb = color.toArgb()
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return (blue shl 16) or (green shl 8) or red
    }
}
