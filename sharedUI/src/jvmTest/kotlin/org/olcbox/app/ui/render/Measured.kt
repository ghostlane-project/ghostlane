package org.olcbox.app.ui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.olcbox.app.ui.theme.AppTheme
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A screen laid out on a [Phone] with its semantics to hand, for tests that ask
 * where things ended up rather than what they look like.
 */
@OptIn(ExperimentalTestApi::class)
internal fun <T> measuredOn(
    phone: Phone,
    content: @Composable () -> Unit,
    read: DesktopComposeUiTest.() -> T
): T {
    val previous = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag(phone.language))
    try {
        var result: T? = null
        runDesktopComposeUiTest(
            width = (phone.widthDp * RENDER_DENSITY).roundToInt(),
            height = (phone.heightDp * RENDER_DENSITY).roundToInt()
        ) {
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(RENDER_DENSITY, phone.fontScale),
                    LocalLayoutDirection provides if (phone.language in RIGHT_TO_LEFT) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    AppTheme(content)
                }
            }
            waitForIdle()
            result = read()
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    } finally {
        Locale.setDefault(previous)
    }
}

/** A text on the screen, as it was laid out. */
internal class LaidText(val text: String, val layout: TextLayoutResult, val node: SemanticsNode) {
    /**
     * Whether any of it is missing from the screen: lines past the last one it
     * may have, an ellipsis, a box shorter than its lines, or — for a text that
     * may not wrap — a box narrower than the one line it is.
     *
     * Not `hasVisualOverflow`: that compares a text's size with the width its
     * paragraph was laid out at, which is the room it was offered, and is true of
     * every text that did not need all of it.
     */
    val cut: Boolean
        get() {
            val paragraph = layout.multiParagraph
            val lastLine = layout.lineCount - 1
            val clippedLine = !layout.layoutInput.softWrap &&
                ceil(paragraph.maxIntrinsicWidth) > layout.size.width + 1
            return paragraph.didExceedMaxLines ||
                layout.didOverflowHeight ||
                clippedLine ||
                (lastLine >= 0 && layout.isLineEllipsized(lastLine))
        }
}

/** Every text that has a place on the screen, scrolled into view or not. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.laidTexts(): List<LaidText> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes()
        // Composed and not placed is how a layout keeps the shape it did not
        // choose; it is not on the screen.
        .filter { it.layoutInfo.isPlaced }
        .mapNotNull { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val layout = results.firstOrNull() ?: return@mapNotNull null
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            LaidText(text, layout, node)
        }

/**
 * Where a node is in the root, whether or not it is scrolled into view.
 *
 * `boundsInRoot` is the part of a node that shows, and for a card below the fold
 * that is an empty rectangle.
 */
internal fun SemanticsNode.place(): Rect = Rect(positionInRoot, Size(size.width.toFloat(), size.height.toFloat()))

/**
 * The controls a person cannot get to: placed past the top or bottom of the
 * screen, with nothing above them that scrolls.
 *
 * A text that is cut can still be guessed at. A button under the bottom edge of a
 * sheet that does not scroll is a sheet with no way out.
 */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.outOfReach(phone: Phone): List<String> {
    val height = phone.heightDp * RENDER_DENSITY
    return onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .filter { it.layoutInfo.isPlaced }
        .filter { node ->
            val place = node.place()
            place.bottom > height + 1f || place.top < -1f
        }
        .filter { node ->
            generateSequence(node.parent) { it.parent }
                .none { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
        }
        .map { node ->
            val place = node.place()
            val name = node.config.getOrNull(SemanticsActions.OnClick)?.label
                ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                ?: "a control"
            "\"$name\" at ${place.top / RENDER_DENSITY}..${place.bottom / RENDER_DENSITY} of ${phone.heightDp}"
        }
}

/**
 * The least a text may stand out from what it is written on: the lightest point
 * of its box against the darkest, counted the way contrast is counted for
 * accessibility.
 *
 * Not a standard anybody reads comfortably at. The faintest colour the theme
 * writes in — a count on a chip, the version under the list — stands at three to
 * one, and on the card of a full room, which is dimmed to half on purpose, at a
 * little over one and a half. This is the line under those: where a text is not
 * faint but absent. What it is here to find is a text nobody gave a colour, which
 * on this theme is black on the ground, at one and a twentieth.
 */
internal const val LEAST_CONTRAST = 1.5f

/**
 * The texts on the screen that cannot be told from their ground, each with the
 * contrast it has.
 *
 * Read off the picture, not off the colours the code names: a text is drawn in
 * the colour it was given, or the one its surroundings hand down, or the default
 * when neither did, and only the picture says which of those reached the screen.
 * Only what shows whole is judged. A row half under the fold is half a box of
 * something else.
 *
 * A flag or a sign with no letter in it is not judged either. The machines these
 * tests run on have no font with emoji in it, so what is drawn there is nothing,
 * which says nothing about a phone.
 */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.faintTexts(least: Float = LEAST_CONTRAST): List<String> {
    // The whole scene, sheets and dialogs with it: they are roots of their own.
    val picture = captureToImage().toPixelMap()
    return laidTexts().filter { text -> text.text.any { it.isLetterOrDigit() } }.mapNotNull { text ->
        val whole = text.node.place()
        val shown = text.node.boundsInRoot
        if (shown.width < whole.width - 1f || shown.height < whole.height - 1f) return@mapNotNull null
        val left = max(0, floor(shown.left).toInt())
        val top = max(0, floor(shown.top).toInt())
        val right = min(picture.width, ceil(shown.right).toInt())
        val bottom = min(picture.height, ceil(shown.bottom).toInt())
        if (right <= left || bottom <= top) return@mapNotNull null
        var lightest = 0f
        var darkest = 1f
        for (y in top until bottom) for (x in left until right) {
            val light = picture[x, y].onOpaque().luminance()
            lightest = max(lightest, light)
            darkest = min(darkest, light)
        }
        val contrast = (lightest + 0.05f) / (darkest + 0.05f)
        if (contrast < least) "\"${text.text}\" at ${(contrast * 100).roundToInt() / 100f} to 1" else null
    }
}

/** A pixel of a finished picture: whatever was under it has already been mixed in. */
private fun Color.onOpaque(): Color = copy(alpha = 1f)
