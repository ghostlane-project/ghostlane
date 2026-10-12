package org.olcbox.app.ui.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.components.kit.PkNameLine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [PkNameLine] with boxes in its slots, so each arrangement is asked for by
 * widths alone and no font has a say.
 *
 * All of them at two pixels to the point, in points.
 */
@OptIn(ExperimentalTestApi::class)
class NameLineTest {
    private val phone = Phone("box", 400, 300)

    /** Bounds in points, whatever the scene was drawn at. */
    private fun DesktopComposeUiTest.box(tag: String): Rect {
        val px = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return Rect(px.left / RENDER_DENSITY, px.top / RENDER_DENSITY, px.right / RENDER_DENSITY, px.bottom / RENDER_DENSITY)
    }

    /**
     * A stand-in for a text: [wide] on one line, and unbreakable in pieces of
     * [chunk]. Given less than [wide] it takes what it is given, as a text does.
     */
    @Composable
    private fun Words(wide: Dp, chunk: Dp, tag: String) {
        Layout(
            content = {},
            modifier = Modifier.testTag(tag),
            measurePolicy = object : MeasurePolicy {
                override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult =
                    layout(constraints.constrainWidth(wide.roundToPx()), 20.dp.roundToPx()) {}

                override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
                    wide.roundToPx()

                override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
                    chunk.roundToPx()
            }
        )
    }

    @Composable
    private fun Line(
        width: Dp,
        name: Dp,
        notes: Dp? = 40.dp,
        end: Dp? = 60.dp,
        notesAtEnd: Boolean = false,
        notesIndent: Dp = 0.dp,
        nameLines: Int = 1,
        /** The widest piece of the name that cannot be broken; all of it, by default. */
        chunk: Dp = name
    ) {
        PkNameLine(
            modifier = Modifier.width(width).testTag("line"),
            notesAtEnd = notesAtEnd,
            nameLines = nameLines,
            notesIndent = notesIndent,
            gap = 8.dp,
            lineGap = 6.dp,
            name = { Words(wide = name, chunk = chunk, tag = "name") },
            notes = notes?.let { { Box(Modifier.size(it, 10.dp).testTag("notes")) } },
            end = end?.let { { Box(Modifier.size(it, 30.dp).testTag("end")) } }
        )
    }

    @Test fun whatFitsIsOneLineWithTheEndAtTheEnd() {
        measuredOn(phone, { Line(width = 300.dp, name = 100.dp) }) {
            assertEquals(Rect(0f, 5f, 100f, 25f), box("name"))
            // Straight after the name, a gap on.
            assertEquals(Rect(108f, 10f, 148f, 20f), box("notes"))
            assertEquals(Rect(240f, 0f, 300f, 30f), box("end"))
            assertEquals(30f, box("line").height)
        }
    }

    @Test fun onOneLineTheNotesCanStandAgainstTheEnd() {
        measuredOn(phone, { Line(width = 300.dp, name = 100.dp, notesAtEnd = true) }) {
            assertEquals(Rect(192f, 10f, 232f, 20f), box("notes"))
        }
    }

    @Test fun theLineHoldsExactlyWhatFitsAndNotAPointMore() {
        // 100 + 8 + 40 + 8 + 60 = 216.
        measuredOn(phone, { Line(width = 216.dp, name = 100.dp) }) {
            assertEquals(5f, box("name").top)
            assertEquals(10f, box("notes").top)
        }
        measuredOn(phone, { Line(width = 215.dp, name = 100.dp) }) {
            assertEquals(36f, box("notes").top, "a point short, the notes go under")
        }
    }

    @Test fun whatDoesNotFitPutsTheNotesUnderAndLeavesTheNameWhole() {
        measuredOn(phone, { Line(width = 180.dp, name = 100.dp, notesIndent = 25.dp) }) {
            assertEquals(Rect(0f, 5f, 100f, 25f), box("name"))
            assertEquals(Rect(120f, 0f, 180f, 30f), box("end"))
            // Under the first line by the line gap, in by the indent.
            assertEquals(Rect(25f, 36f, 65f, 46f), box("notes"))
            assertEquals(46f, box("line").height)
        }
    }

    @Test fun aNameThatDoesNotFitBesideTheEndHasTheLineToItself() {
        // 112 points are left beside the end, and the name is 150 and one line.
        measuredOn(phone, { Line(width = 180.dp, name = 150.dp) }) {
            // The name has the first line to itself and is whole.
            assertEquals(Rect(0f, 0f, 150f, 20f), box("name"))
            // The notes and the end share the second: notes first, end at the end.
            assertEquals(Rect(120f, 26f, 180f, 56f), box("end"))
            assertEquals(Rect(0f, 36f, 40f, 46f), box("notes"))
            assertEquals(56f, box("line").height)
        }
    }

    @Test fun whenNotEvenTheNotesAndTheEndShareALineEachHasItsOwn() {
        // The name needs the first line; 112 points are left beside the end on
        // the second, and the notes are 130.
        measuredOn(phone, { Line(width = 180.dp, name = 150.dp, notes = 130.dp, notesIndent = 10.dp) }) {
            assertEquals(Rect(0f, 0f, 150f, 20f), box("name"))
            assertEquals(Rect(10f, 26f, 140f, 36f), box("notes"))
            assertEquals(Rect(120f, 42f, 180f, 72f), box("end"))
            assertEquals(72f, box("line").height)
        }
    }

    @Test fun aNameAllowedTwoLinesKeepsTheEndBesideItWhileTwoLinesAreSureToHoldIt() {
        // 112 points beside the end. A line wastes less than one piece of the
        // name, so two lines hold 224 less a piece: with pieces of 40, 184.
        measuredOn(phone, { Line(width = 180.dp, name = 184.dp, chunk = 40.dp, nameLines = 2) }) {
            assertEquals(0f, box("end").top)
            assertEquals(112f, box("name").width)
            assertEquals(36f, box("notes").top)
        }
        measuredOn(phone, { Line(width = 180.dp, name = 185.dp, chunk = 40.dp, nameLines = 2) }) {
            assertEquals(26f, box("end").top, "a name two lines may not hold has the line to itself")
            assertEquals(180f, box("name").width)
        }
    }

    @Test fun aNameOfLongPiecesGivesUpTheEndSoonerThanOneOfShortPieces() {
        // The same width of name in the same room: pieces of 40 leave it beside
        // the end, pieces of 90 do not — two lines there could strand the third.
        measuredOn(phone, { Line(width = 180.dp, name = 160.dp, chunk = 40.dp, nameLines = 2) }) {
            assertEquals(0f, box("end").top)
        }
        measuredOn(phone, { Line(width = 180.dp, name = 160.dp, chunk = 90.dp, nameLines = 2) }) {
            assertEquals(26f, box("end").top)
        }
    }

    @Test fun aLineWithNoNotesOrNoEndKeepsNoGapForThem() {
        measuredOn(phone, { Line(width = 300.dp, name = 100.dp, notes = null) }) {
            assertEquals(Rect(240f, 0f, 300f, 30f), box("end"))
            assertEquals(30f, box("line").height)
        }
        measuredOn(phone, { Line(width = 300.dp, name = 100.dp, end = null) }) {
            assertEquals(Rect(108f, 5f, 148f, 15f), box("notes"))
            assertEquals(20f, box("line").height)
        }
        // Without an end the name may have the whole width before anything moves.
        measuredOn(phone, { Line(width = 148.dp, name = 100.dp, end = null) }) {
            assertEquals(5f, box("notes").top)
        }
    }

    @Test fun rightToLeftIsTheSameLineMirrored() {
        measuredOn(phone.copy(language = "fa"), { Line(width = 300.dp, name = 100.dp) }) {
            // The line is laid from the screen's right edge: 400 wide, the line 300.
            val line = box("line")
            assertEquals(line.right - 100f, box("name").left)
            assertEquals(line.right, box("name").right)
            assertEquals(line.left, box("end").left)
            assertEquals(line.right - 148f, box("notes").left)
        }
    }
}
