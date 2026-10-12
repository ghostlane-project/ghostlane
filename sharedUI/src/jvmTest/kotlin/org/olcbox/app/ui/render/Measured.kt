package org.olcbox.app.ui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
