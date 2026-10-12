package org.olcbox.app.ui.render

import java.io.File
import kotlin.test.Test

/**
 * Pictures of every screen that is not the board, on the shapes that break
 * layouts first. Written only when `OLCBOX_RENDER_DIR` names a directory.
 */
class ScreensRenderTest {
    private val shapes = listOf(
        Phone("zoomed", 320, 693, 1f, "ru"),
        Phone("zoomed", 320, 693, 1.6f, "ru"),
        Phone("zoomed", 320, 693, 2f, "ru"),
        Phone("iphone", 393, 852, 1f, "en"),
        Phone("iphone", 393, 852, 1.3f, "ru")
    )

    @Test fun everyOtherScreen() {
        val dir = renderDir() ?: return
        for ((name, screen) in Screens.all) for (shape in shapes) {
            File(dir, "screen-$name-${shape.label}.png").writeBytes(renderPng(shape, screen))
        }
    }
}
