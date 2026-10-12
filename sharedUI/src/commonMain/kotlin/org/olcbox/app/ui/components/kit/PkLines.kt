package org.olcbox.app.ui.components.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * Lines that keep their name.
 *
 * Every line of the board used to be a `Row` in which the name carried the
 * weight: the tag, the badge, the seat count and the latency each took the width
 * they wanted, and the name had what was left. On the phone the layout was drawn
 * on, at the text size it was drawn at, that is most of the line. On a narrower
 * phone, or with the text set larger — which is what a person does when they
 * cannot read the screen — what was left reached zero, and a room was a card with
 * a tag, a badge and no name.
 *
 * Here the name is measured first and nothing takes its room. What does not fit
 * beside it goes under it.
 */

/**
 * A name, what is said about it, and what closes its line.
 *
 * Where the three fit, they are one line, as they always were. Where they do
 * not, [notes] goes to a line of its own and the name has the first to itself
 * and [end]. Where the name does not fit beside [end] either — in as many lines
 * as [nameLines] allows it there — [end] joins the notes on the second line and
 * the name has the whole of the first. And where the notes and [end] cannot
 * share a line, each of the three has its own.
 *
 * @param notesAtEnd on one line, whether [notes] stands against [end] (a card:
 *   the tag beside the latency) or straight after the name (a list's age beside
 *   its title).
 * @param nameLines how many lines the name may run to beside [end] before [end]
 *   gives up its place. One for a title that should stay a line; two for a card,
 *   whose latency is worth keeping at the top corner while the name can wrap
 *   beside it. The name's own `maxLines` is the caller's, and should not be less.
 * @param notesIndent how far in the second line starts, for notes that belong
 *   under a title that itself starts after an icon.
 */
@Composable
fun PkNameLine(
    name: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    notes: (@Composable () -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
    notesAtEnd: Boolean = false,
    nameLines: Int = 1,
    notesIndent: Dp = 0.dp,
    gap: Dp = 8.dp,
    lineGap: Dp = 6.dp
) {
    Layout(
        modifier = modifier,
        content = {
            // One box a slot, so the policy below always finds three children
            // whatever each slot composed — an absent one is a box of no size.
            Box { name() }
            Box { notes?.invoke() }
            Box { end?.invoke() }
        }
    ) { measurables, constraints ->
        val (nameSlot, notesSlot, endSlot) = measurables
        val gapPx = gap.roundToPx()
        val lineGapPx = lineGap.roundToPx()
        val nameWanted = nameSlot.maxIntrinsicWidth(Constraints.Infinity)
        val nameChunk = nameSlot.minIntrinsicWidth(Constraints.Infinity)
        val notesWanted = notesSlot.maxIntrinsicWidth(Constraints.Infinity)

        if (!constraints.hasBoundedWidth) {
            // Nothing to fit into: everything is as wide as it likes, on one line.
            val nameP = nameSlot.measure(Constraints())
            val notesP = notesSlot.measure(Constraints())
            val endP = endSlot.measure(Constraints())
            val line = alignOnLine(nameP, notesP, endP)
            val notesX = nameP.width + if (notesP.width > 0) gapPx else 0
            val endX = notesX + notesP.width + if (endP.width > 0) gapPx else 0
            return@Layout layout(max(endX + endP.width, constraints.minWidth), line.height) {
                nameP.placeRelative(0, line.ys[0])
                notesP.placeRelative(notesX, line.ys[1])
                endP.placeRelative(endX, line.ys[2])
            }
        }

        val width = constraints.maxWidth
        val endP = endSlot.measure(Constraints(maxWidth = width))
        val endRoom = if (endP.width > 0) endP.width + gapPx else 0
        val notesRoom = if (notesWanted > 0) notesWanted + gapPx else 0
        val besideEnd = (width - endRoom).coerceAtLeast(0)

        when {
            nameWanted + notesRoom + endRoom <= width -> {
                val nameP = nameSlot.measure(Constraints(maxWidth = (besideEnd - notesRoom).coerceAtLeast(0)))
                val notesP = notesSlot.measure(Constraints(maxWidth = notesWanted))
                val line = alignOnLine(nameP, notesP, endP)
                layout(width, line.height) {
                    nameP.placeRelative(0, line.ys[0])
                    notesP.placeRelative(
                        if (notesAtEnd) besideEnd - notesP.width else nameP.width + gapPx,
                        line.ys[1]
                    )
                    endP.placeRelative(width - endP.width, line.ys[2])
                }
            }

            fitsWrapped(nameWanted, nameChunk, besideEnd, nameLines) -> {
                val nameP = nameSlot.measure(Constraints(maxWidth = besideEnd))
                val indent = notesIndent.roundToPx().coerceAtMost(width / 2)
                val notesP = notesSlot.measure(Constraints(maxWidth = width - indent))
                val line = alignOnLine(nameP, null, endP)
                val under = if (notesP.height > 0) line.height + lineGapPx else line.height
                layout(width, under + notesP.height) {
                    nameP.placeRelative(0, line.ys[0])
                    endP.placeRelative(width - endP.width, line.ys[2])
                    notesP.placeRelative(indent, under)
                }
            }

            else -> {
                val nameP = nameSlot.measure(Constraints(maxWidth = width))
                val indent = notesIndent.roundToPx().coerceAtMost(width / 2)
                val under = nameP.height + lineGapPx
                if (notesWanted <= besideEnd - indent) {
                    val notesP = notesSlot.measure(
                        Constraints(maxWidth = (besideEnd - indent).coerceAtLeast(0))
                    )
                    val line = alignOnLine(null, notesP, endP)
                    layout(width, under + line.height) {
                        nameP.placeRelative(0, 0)
                        notesP.placeRelative(indent, under + line.ys[1])
                        endP.placeRelative(width - endP.width, under + line.ys[2])
                    }
                } else {
                    // Not even the notes and the end share a line: one of each,
                    // in the order they are read.
                    val notesP = notesSlot.measure(Constraints(maxWidth = width - indent))
                    val last = under + notesP.height + lineGapPx
                    layout(width, last + endP.height) {
                        nameP.placeRelative(0, 0)
                        notesP.placeRelative(indent, under)
                        endP.placeRelative(width - endP.width, last)
                    }
                }
            }
        }
    }
}

/**
 * Whether a text [wanted] wide on one line is sure to fit a column [width] wide
 * in [lines], given that the widest piece of it that cannot be broken is [chunk].
 *
 * A line is filled until the next piece does not fit, so what it leaves empty is
 * less than one piece — less than [chunk]. Every line but the last can waste that
 * much, and no more: the text fits if it is no wider than the lines together less
 * that waste. It may also fit when this says no, by luck of where its words fall;
 * what it says yes to is never cut.
 *
 * That is why it is not an allowance of so many percent. `CA · olcRTC · SJ` is
 * three pieces, the middle one half its width, and beside a latency column it
 * needed three lines where a percentage had promised two.
 */
internal fun fitsWrapped(wanted: Int, chunk: Int, width: Int, lines: Int): Boolean {
    if (wanted <= width) return true
    if (lines <= 1) return false
    return wanted.toLong() + (lines - 1).toLong() * chunk <= lines.toLong() * width
}

/** Where each of a line's pieces sits from the line's top, and how tall the line is. */
internal class PkLineFit(val ys: IntArray, val height: Int)

/**
 * Sets a line's pieces on one baseline, and centres what has none against them.
 *
 * A name in fifteen points beside a tag in nine reads as one line only when the
 * letters stand on the same floor; centred, the smaller one floats. A button has
 * no baseline, and is centred on the line the text makes.
 *
 * It is the first baseline that is shared, so a name that wrapped keeps its
 * latency beside its first line rather than between its two.
 */
internal fun alignOnLine(vararg pieces: Placeable?): PkLineFit {
    val baselines = pieces.map { piece ->
        piece?.takeIf { it.height > 0 }?.get(FirstBaseline)?.takeIf { it != AlignmentLine.Unspecified }
    }
    val floor = baselines.filterNotNull().maxOrNull() ?: 0
    val ys = IntArray(pieces.size)
    var lettered = 0
    var height = 0
    pieces.forEachIndexed { index, piece ->
        if (piece == null) return@forEachIndexed
        val baseline = baselines[index]
        if (baseline != null) {
            ys[index] = floor - baseline
            lettered = max(lettered, ys[index] + piece.height)
        }
        height = max(height, piece.height)
    }
    height = max(height, lettered)
    val shift = (height - lettered) / 2
    pieces.forEachIndexed { index, piece ->
        if (piece == null) return@forEachIndexed
        ys[index] = if (baselines[index] != null) ys[index] + shift else (height - piece.height) / 2
    }
    return PkLineFit(ys, height)
}

/**
 * A size for lettering that is a mark rather than a sentence: the wordmark, the
 * tag beside it.
 *
 * Text grows with the size its reader chose; a logo does not need to be read
 * twice as large to be recognised, and at twice the size it took the header's
 * buttons with it. So it follows the setting only as far as [ceiling].
 */
@Composable
fun pkMarkSp(size: Int, ceiling: Float = 1.3f): TextUnit {
    val scale = LocalDensity.current.fontScale
    return if (scale <= ceiling) size.sp else (size * ceiling / scale).sp
}

/**
 * A size for display lettering — a heading, the session timer — which follows the
 * reader's text size at half the rate.
 *
 * What is set large is large so that it is found, not so that it can be read: at
 * twenty-one points a heading is legible to anyone who can use the screen at all.
 * Doubled with the body text it is forty-two, a third of a phone's width for one
 * word, and the list it heads starts a screen further down. The platforms scale
 * their own large titles the same way.
 */
@Composable
fun pkDisplaySp(size: Int): TextUnit {
    val scale = LocalDensity.current.fontScale
    return if (scale <= 1f) size.sp else (size * (1f + (scale - 1f) / 2f) / scale).sp
}

/**
 * `DE · olcRTC · WB` with each dot tied to the word before it.
 *
 * A name that wraps breaks at a space, and half of the spaces in these names are
 * the one before a separator: the second line then opened with a dot — `· WB` —
 * which reads as the start of a list. Tied, a line can end with a dot and never
 * begins with one.
 */
fun pkTied(text: String): String = text.replace(" · ", "\u00A0· ")

/**
 * [text] as two lines, cut at the space nearest its middle.
 *
 * What a centred label reads best as once it cannot be one line. Left to wrap
 * where it runs out of room, `LEAVE DE · OLCRTC · WB` is one full line and `WB`
 * alone under it. A tied dot ([pkTied]) is not a space and is never cut at.
 */
fun pkBalanced(text: String): String {
    val middle = text.length / 2
    val cut = text.indices
        .filter { text[it] == ' ' }
        .minByOrNull { if (it < middle) middle - it else it - middle }
        ?: return text
    return text.substring(0, cut) + "\n" + text.substring(cut + 1)
}

/**
 * A centred label: one line where it fits, two balanced lines where it does not.
 *
 * Both are composed and one is placed. Neither is read out: the control they
 * label says what it is, once.
 */
@Composable
fun PkBalancedText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val balanced = remember(text) { pkBalanced(text) }
    Layout(
        modifier = modifier,
        content = {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.semantics { hideFromAccessibility() }
            )
            Text(
                text = balanced,
                style = style,
                color = color,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { hideFromAccessibility() }
            )
        }
    ) { measurables, constraints ->
        val (oneLine, twoLines) = measurables
        val fits = !constraints.hasBoundedWidth ||
            oneLine.maxIntrinsicWidth(Constraints.Infinity) <= constraints.maxWidth
        val placed = (if (fits) oneLine else twoLines).measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(placed.width, placed.height) { placed.placeRelative(0, 0) }
    }
}

/**
 * A row of equals — two buttons, usually — that stands as a column when it has to.
 *
 * Side by side, each as wide as the others, while every one of them fits its
 * share on one line. When one does not, they stack at full width, in order. Two
 * buttons at half a narrow phone each were "Throu…" and "Direct"; one over the
 * other they are two buttons.
 */
@Composable
fun PkEvenRow(
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val gapPx = gap.roundToPx()
        val count = measurables.size
        if (!constraints.hasBoundedWidth) {
            val placeables = measurables.map { it.measure(Constraints()) }
            val height = placeables.maxOf { it.height }
            var x = 0
            return@Layout layout(placeables.sumOf { it.width } + gapPx * (count - 1), height) {
                placeables.forEach { placeable ->
                    placeable.placeRelative(x, (height - placeable.height) / 2)
                    x += placeable.width + gapPx
                }
            }
        }
        val width = constraints.maxWidth
        val share = (width - gapPx * (count - 1)) / count
        val sideBySide = share > 0 && measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= share }
        if (sideBySide) {
            // Cut to one height as well as one width: a row of buttons of
            // different heights reads as one button and one accident.
            val height = measurables.maxOf { it.maxIntrinsicHeight(share) }
            val placeables = measurables.map { it.measure(Constraints.fixed(share, height)) }
            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(index * (share + gapPx), 0)
                }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            layout(width, placeables.sumOf { it.height } + gapPx * (count - 1)) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + gapPx
                }
            }
        }
    }
}
