package org.olcbox.app.ui.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.components.kit.PkStatusStrip
import org.olcbox.app.ui.components.kit.THROUGHPUT_PENDING
import org.olcbox.app.ui.components.kit.ThroughputWindow
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The status strip keeps its shape through a session, and changes it where a
 * person can follow.
 *
 * It was reported from a phone: the strip opened short when the tunnel came up,
 * and two seconds later — when the first second of traffic had been counted — the
 * chart arrived under it and the whole board dropped by its height in one frame.
 * The strip is as tall as a session needs from the moment one is asked for, and
 * the one change of height left, at the press that starts or ends a session, is
 * made over frames.
 */
@OptIn(ExperimentalTestApi::class)
class StatusStripSteadyTest {
    private val phone = Phone("iphone", 393, 852)

    private enum class Tunnel { Down, ComingUp, Up }

    @Composable
    private fun Strip(tunnel: Tunnel, traffic: ThroughputWindow, height: MutableState<Int>) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.onSizeChanged { height.value = it.height }) {
                PkStatusStrip(
                    // The same words in every state: what is measured is the
                    // strip's own shape, not what a longer label does to it.
                    label = "in a room · olcRTC",
                    isActive = tunnel == Tunnel.Up,
                    isBusy = tunnel == Tunnel.ComingUp,
                    meta = { "VK Calls · VP8" },
                    value = { "00:01" },
                    traffic = traffic
                )
            }
        }
    }

    private fun heightOf(tunnel: Tunnel, traffic: ThroughputWindow): Int {
        val height = mutableStateOf(0)
        return measuredOn(phone, { Strip(tunnel, traffic, height) }) { height.value }
    }

    @Test fun aSessionsFirstSecondsAreAsTallAsTheRestOfIt() {
        // Nothing counted yet, against a minute of traffic on the chart.
        val justUp = heightOf(Tunnel.Up, ThroughputWindow())
        val later = heightOf(Tunnel.Up, BoardFixture.session())
        assertEquals(later, justUp, "the strip grew when the first second of traffic arrived")
    }

    @Test fun theStripIsThatTallWhileTheTunnelIsStillComingUp() {
        // The press is where a person expects the screen to change; the moment
        // the tunnel answers, a second or ten later, is not.
        val comingUp = heightOf(Tunnel.ComingUp, ThroughputWindow())
        val up = heightOf(Tunnel.Up, BoardFixture.session())
        assertEquals(up, comingUp, "the strip changed height when the tunnel came up")
    }

    @Test fun withNoSessionTheStripIsShort() {
        // The chart's room is a session's. An idle screen gives it to the list.
        val idle = heightOf(Tunnel.Down, ThroughputWindow())
        val up = heightOf(Tunnel.Up, BoardFixture.session())
        assertTrue(idle < up, "an idle strip is $idle px, a session's $up")
    }

    @Test fun untilASecondIsOnRecordTheRatesSayTheyAreComing() {
        // Not "0 B/s": that is a measurement, and none has been made.
        val height = mutableStateOf(0)
        val texts = measuredOn(phone, { Strip(Tunnel.Up, ThroughputWindow(), height) }) { laidTexts().map { it.text } }
        assertTrue("↓ $THROUGHPUT_PENDING" in texts && "↑ $THROUGHPUT_PENDING" in texts, "the strip says $texts")
        assertTrue(texts.none { "B/s" in it }, "a rate nobody measured: $texts")
    }

    @Test fun theStripsWordsDoNotMoveSidewaysWhenASessionStarts() {
        // The dot gains a halo while a session is up. Its place was as wide as
        // whatever it held, so the halo pushed every word of the strip nine
        // points along in the frame the tunnel came up.
        fun wordsStartAt(tunnel: Tunnel): Float {
            val height = mutableStateOf(0)
            return measuredOn(phone, { Strip(tunnel, ThroughputWindow(), height) }) {
                laidTexts().first { it.text.startsWith("IN A ROOM") }.node.positionInRoot.x
            }
        }
        val idle = wordsStartAt(Tunnel.Down)
        assertEquals(idle, wordsStartAt(Tunnel.ComingUp), 0.5f, "the words moved when the tunnel was asked for")
        assertEquals(idle, wordsStartAt(Tunnel.Up), 0.5f, "the words moved when the tunnel came up")
    }

    @Test fun underTheChartTheRatesStartWhereTheChartDoes() {
        // A narrow phone with the text at twice its size: no room for the rates
        // beside the chart, so they stand under it. They were set to the end of
        // a column as wide as the widest rate there is, which under a chart is a
        // short figure adrift in the middle of the strip.
        val narrow = Phone("zoomed", 320, 693, fontScale = 2f)
        for (traffic in listOf(ThroughputWindow(), BoardFixture.session())) {
            val height = mutableStateOf(0)
            measuredOn(narrow, { Strip(Tunnel.Up, traffic, height) }) {
                val rates = laidTexts().filter { it.text.startsWith("↓") || it.text.startsWith("↑") }
                assertEquals(2, rates.size, "the strip's rates: ${rates.map { it.text }}")
                // The test's own margin and the strip's padding, at two pixels a point.
                val start = (16 + 15) * RENDER_DENSITY
                rates.forEach {
                    // Its box and its letters are one size, so where the box
                    // starts is where the figure does. A box at the edge with
                    // the figure set to its far end is the same drift.
                    val letters = ceil(it.layout.multiParagraph.maxIntrinsicWidth)
                    assertTrue(
                        it.node.size.width <= letters + 1,
                        "\"${it.text}\" is $letters px of letters in a box of ${it.node.size.width}"
                    )
                    assertEquals(start, it.node.positionInRoot.x, 1f, "\"${it.text}\" does not start at the strip's edge")
                }
            }
        }
    }

    @Test fun theStripOpensOverFramesNotInOne() {
        val tunnel = mutableStateOf(Tunnel.Down)
        val height = mutableStateOf(0)
        val traffic = ThroughputWindow()
        measuredOn(phone, { Strip(tunnel.value, traffic, height) }) {
            val idle = height.value
            mainClock.autoAdvance = false
            tunnel.value = Tunnel.ComingUp
            mainClock.advanceTimeBy(OPENING_MS)
            val opening = height.value
            mainClock.advanceTimeBy(SETTLED_MS)
            val open = height.value
            assertTrue(open > idle, "the strip did not open: $idle px, then $open")
            assertTrue(opening in (idle + 1) until open, "after $OPENING_MS ms the strip is $opening px, between $idle and $open")
        }
    }

    @Test fun theStripClosesOverFramesToo() {
        val tunnel = mutableStateOf(Tunnel.Up)
        val height = mutableStateOf(0)
        val traffic = BoardFixture.session()
        measuredOn(phone, { Strip(tunnel.value, traffic, height) }) {
            val open = height.value
            mainClock.autoAdvance = false
            tunnel.value = Tunnel.Down
            mainClock.advanceTimeBy(OPENING_MS)
            val closing = height.value
            mainClock.advanceTimeBy(SETTLED_MS)
            val idle = height.value
            assertTrue(idle < open, "the strip did not close: $open px, then $idle")
            assertTrue(closing in (idle + 1) until open, "after $OPENING_MS ms the strip is $closing px, between $idle and $open")
        }
    }

    private companion object {
        /** A third of the way into the change, whatever its curve. */
        const val OPENING_MS = 80L

        /** Long after any change of shape this screen makes is over. */
        const val SETTLED_MS = 2_000L
    }
}
