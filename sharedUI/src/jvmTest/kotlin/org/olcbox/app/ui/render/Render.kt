package org.olcbox.app.ui.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.EncodedImageFormat
import org.olcbox.app.ui.theme.AppTheme
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A screen of the app, drawn with no device and no display.
 *
 * The shapes a phone can be: how wide, how tall, and how large its owner has set
 * the text. The last is the one a layout is never tried against by accident — a
 * developer's phone sits at the default, and the people who raise it are the ones
 * who cannot read the screen otherwise.
 */
internal data class Phone(
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val fontScale: Float = 1f,
    val language: String = "en"
) {
    val label: String get() = "$name-${widthDp}x$heightDp-x${(fontScale * 100).roundToInt()}-$language"
}

/** The languages written right to left. */
internal val RIGHT_TO_LEFT = setOf("fa", "ar", "he")

/** The pixels per dp the scenes are drawn at. Two is a phone of ten years ago and enough to read. */
internal const val RENDER_DENSITY = 2f

internal fun <T> onPhone(phone: Phone, content: @Composable () -> Unit, read: (ImageComposeScene) -> T): T {
    val previous = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag(phone.language))
    try {
        val scene = ImageComposeScene(
            width = (phone.widthDp * RENDER_DENSITY).roundToInt(),
            height = (phone.heightDp * RENDER_DENSITY).roundToInt(),
            density = Density(RENDER_DENSITY, phone.fontScale)
        ) {
            // Composed as the app composes it on a phone in that language: Compose
            // takes the direction from the system, and a headless scene has none.
            CompositionLocalProvider(
                LocalLayoutDirection provides if (phone.language in RIGHT_TO_LEFT) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                AppTheme { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        try {
            // A second and a half of frames, a tenth of a second apart. A sheet is
            // not on the screen in its first frame: it is laid out, told to open,
            // and slides up over the frames after — and a colour or a size that
            // animates needs frames to arrive too.
            //
            // Each frame is closed as it is made. What `render` returns is a
            // picture held outside the Java heap, where the collector neither
            // counts it nor hurries to free it: thirty of them a scene, across
            // fifty scenes, were five gigabytes on a machine that had other uses
            // for them.
            for (frame in 0..FRAMES) scene.render(frame * FRAME_NANOS).close()
            return read(scene)
        } finally {
            scene.close()
        }
    } finally {
        Locale.setDefault(previous)
    }
}

internal fun renderPng(phone: Phone, content: @Composable () -> Unit): ByteArray =
    onPhone(phone, content) { scene ->
        scene.render((FRAMES + 1) * FRAME_NANOS).use { picture ->
            picture.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
        }
    }

private const val FRAMES = 15
private const val FRAME_NANOS = 100_000_000L

/** Where the pictures go, or null when nobody asked for them. */
internal fun renderDir(): File? =
    System.getenv("OLCBOX_RENDER_DIR")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
