package org.olcbox.app.ui.components.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.theme.LocalPkPalette
import org.olcbox.app.util.formatByteSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What is going through the tunnel, second by second.
 *
 * It was one line: received and sent added together, scaled against the busiest
 * second in the window. That answers "is anything moving" and nothing after it.
 * One busy second set the scale and every other second lay flat beside it; a
 * download and an upload drew the same line; and no number said what the height
 * of the line stood for, so it could not be read against the totals above it.
 *
 * Here the two directions are two rows of bars on one rule — received above it,
 * sent below — on one scale, so the taller side is the busier one; and the newest
 * bar of each stands beside its rate in figures, which is what gives the rest of
 * the bars a size.
 *
 * It costs what the line cost, or less. The window is two arrays of primitives
 * that are written in place, where the line built a new list every second; and a
 * second that passes redraws the bars and resets two short texts, with nothing
 * recomposed above them and no animation asking for frames in between.
 */

/** How many seconds the chart remembers. */
const val THROUGHPUT_SECONDS = 44

/**
 * The least that the full height of a bar stands for, in bytes a second.
 *
 * The bars are scaled to the busiest second on record, so without a floor the
 * keepalives of an idle tunnel — a few hundred bytes — would fill the chart as a
 * download does. Under this the bars stay short, which is what an idle tunnel is.
 */
const val THROUGHPUT_FLOOR_BYTES = 96L * 1024L

/**
 * How tall a second of [bytes] is drawn, 0f..1f of the room above the rule, where
 * the tallest bar stands for [peak].
 *
 * By the square root, not in proportion. In proportion a page load beside a video
 * is a twentieth of the height — one pixel — and every second that is not the
 * peak reads as nothing. By the root it is a fifth, and a second with a hundredth
 * of the peak's traffic is still a tenth of its height: small, and there.
 */
fun throughputHeight(bytes: Long, peak: Long): Float {
    if (bytes <= 0L || peak <= 0L) return 0f
    return sqrt(bytes.toDouble() / peak.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

/**
 * The last [seconds] seconds of a session: how much arrived and how much left in
 * each.
 *
 * Fed the platform's running totals once a second, it keeps the difference
 * between one reading and the next. The first reading of a session is only a
 * starting point — it has nothing before it to be the difference from, and taken
 * as a second's traffic it would open every chart with a bar the size of the
 * session so far.
 *
 * Every read registers its reader with Compose, and every [record] and [reset]
 * tells them: a draw scope that reads it is drawn again, a composable that reads
 * it is recomposed, and nothing else is.
 */
@Stable
class ThroughputWindow(val seconds: Int = THROUGHPUT_SECONDS) {
    private val received = LongArray(seconds)
    private val sent = LongArray(seconds)
    private var count = 0
    private var hasPrevious = false
    private var previousReceived = 0L
    private var previousSent = 0L
    private var stamp by mutableIntStateOf(0)

    /**
     * Whether any second is on record. A state of its own, so that what only
     * needs to know whether to draw a chart at all is not redone every second.
     */
    var hasData by mutableStateOf(false)
        private set

    /** Takes the running totals as they stand now. */
    fun record(totalReceived: Long, totalSent: Long) {
        if (hasPrevious) {
            // Totals that went backwards are another session's, or a counter that
            // was reset under this one: that second moved nothing we can name.
            push(
                (totalReceived - previousReceived).coerceAtLeast(0L),
                (totalSent - previousSent).coerceAtLeast(0L)
            )
        }
        hasPrevious = true
        previousReceived = totalReceived
        previousSent = totalSent
    }

    private fun push(receivedBytes: Long, sentBytes: Long) {
        // Moved down by one in place. Forty-four numbers a second is nothing to
        // copy and, unlike a list rebuilt each time, nothing to collect.
        received.copyInto(received, destinationOffset = 0, startIndex = 1, endIndex = seconds)
        sent.copyInto(sent, destinationOffset = 0, startIndex = 1, endIndex = seconds)
        received[seconds - 1] = receivedBytes
        sent[seconds - 1] = sentBytes
        if (count < seconds) count++
        hasData = true
        stamp++
    }

    /** Forgets the session: its seconds, and the totals it was counting from. */
    fun reset() {
        received.fill(0L)
        sent.fill(0L)
        count = 0
        hasPrevious = false
        hasData = false
        stamp++
    }

    /** How many seconds are on record, at most [seconds]. */
    val recorded: Int
        get() {
            stamp
            return count
        }

    /** Bytes received in the second that ended [secondsAgo] seconds back; 0 is the newest. */
    fun receivedAgo(secondsAgo: Int): Long {
        stamp
        return if (secondsAgo in 0 until count) received[seconds - 1 - secondsAgo] else 0L
    }

    /** Bytes sent in the second that ended [secondsAgo] seconds back; 0 is the newest. */
    fun sentAgo(secondsAgo: Int): Long {
        stamp
        return if (secondsAgo in 0 until count) sent[seconds - 1 - secondsAgo] else 0L
    }

    /**
     * What the tallest bar stands for: the busiest second of either direction
     * among the newest [among] seconds, and never less than
     * [THROUGHPUT_FLOOR_BYTES].
     *
     * One figure for both rows. Each scaled to its own peak, a trickle of
     * acknowledgements would stand as tall as the download it acknowledges.
     */
    fun peak(among: Int = seconds): Long {
        stamp
        var most = THROUGHPUT_FLOOR_BYTES
        for (ago in 0 until min(among, count)) {
            val index = seconds - 1 - ago
            most = max(most, max(received[index], sent[index]))
        }
        return most
    }
}

/** `1.2 MB/s`: a second's bytes as a rate. */
fun throughputRate(bytesInOneSecond: Long): String = "${formatByteSize(bytesInOneSecond.coerceAtLeast(0L))}/s"

private const val RECEIVED_MARK = "↓"
private const val SENT_MARK = "↑"

/**
 * The chart, and the two rates beside it.
 *
 * Beside it where there is room for a chart worth the name next to them; under
 * it where there is not, which is a narrow phone or large text.
 */
@Composable
fun PkThroughput(traffic: ThroughputWindow, modifier: Modifier = Modifier) {
    if (!traffic.hasData) return
    val palette = LocalPkPalette.current
    val receivedColor = palette.accent
    val sentColor = palette.link
    val rule = palette.seatFree
    // Read left to right in every language: an arrow, a figure and a unit, which
    // a right-to-left line would otherwise set down in the opposite order.
    val style = pkMono(9, 0.4).copy(lineHeight = 12.sp, textDirection = TextDirection.ContentOrLtr)
    val rateColumn = rememberRateColumn(style)

    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Canvas(Modifier) {
                // A bar to a second. On a phone that is the chart's width; on a
                // wider strip the bars grow a little rather than leave a chart
                // that is mostly rule, and stop growing before they are blocks.
                val slot = (size.width / traffic.seconds).coerceIn(BAR_SLOT.toPx(), BAR_SLOT_WIDEST.toPx())
                val bar = slot * BAR_SHARE
                val bars = min(traffic.seconds, (size.width / slot).toInt())
                val middle = size.height / 2f
                val clear = RULE_CLEARANCE.toPx()
                val room = (middle - clear).coerceAtLeast(0f)
                val least = BAR_LEAST.toPx()
                val corner = CornerRadius(bar / 2f)
                val newestOnTheLeft = layoutDirection == LayoutDirection.Rtl
                // Scaled to what is on the chart, not to a second that has
                // already left its edge.
                val peak = traffic.peak(among = bars)
                val recorded = traffic.recorded

                val ruleWidth = 1.dp.toPx()
                drawRect(color = rule, topLeft = Offset(0f, middle - ruleWidth / 2f), size = Size(size.width, ruleWidth))
                for (ago in 0 until min(bars, recorded)) {
                    val offset = ago * slot + (slot - bar) / 2f
                    // The newest second stands against the figures that name it.
                    val x = if (newestOnTheLeft) offset else size.width - offset - bar
                    // Older seconds recede, which is what says the chart has a direction.
                    val fade = 1f - 0.65f * ago / bars.coerceAtLeast(1)
                    val above = barHeight(traffic.receivedAgo(ago), peak, room, least)
                    if (above > 0f) {
                        drawRoundRect(
                            color = receivedColor.copy(alpha = fade),
                            topLeft = Offset(x, middle - clear - above),
                            size = Size(bar, above),
                            cornerRadius = corner
                        )
                    }
                    val below = barHeight(traffic.sentAgo(ago), peak, room, least)
                    if (below > 0f) {
                        drawRoundRect(
                            color = sentColor.copy(alpha = fade),
                            topLeft = Offset(x, middle + clear),
                            size = Size(bar, below),
                            cornerRadius = corner
                        )
                    }
                }
            }
            PkRate(RECEIVED_MARK, { traffic.receivedAgo(0) }, receivedColor, style, rateColumn)
            PkRate(SENT_MARK, { traffic.sentAgo(0) }, sentColor, style, rateColumn)
        }
    ) { measurables, constraints ->
        val (chartSlot, receivedSlot, sentSlot) = measurables
        val gap = RATES_GAP.roundToPx()
        // Asked how wide it would like to be: a second to a bar, and the rates.
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            (BAR_SLOT * traffic.seconds).roundToPx() + gap +
                max(receivedSlot.maxIntrinsicWidth(Constraints.Infinity), sentSlot.maxIntrinsicWidth(Constraints.Infinity))
        }
        val receivedP = receivedSlot.measure(Constraints(maxWidth = width))
        val sentP = sentSlot.measure(Constraints(maxWidth = width))
        val rates = max(receivedP.width, sentP.width)
        val beside = width - rates - gap

        if (beside >= CHART_LEAST_WIDTH.roundToPx()) {
            // Each rate level with its own row of bars: received over the rule,
            // sent under it. The chart is as tall as the two of them need.
            val half = max(CHART_HEIGHT.roundToPx() / 2, max(receivedP.height, sentP.height))
            // No wider than its seconds at their widest, and against the rates:
            // on a tablet the strip is three times what the chart can use.
            val chartWidth = min(beside, (BAR_SLOT_WIDEST * traffic.seconds).roundToPx())
            val chartP = chartSlot.measure(Constraints.fixed(chartWidth, half * 2))
            layout(width, half * 2) {
                chartP.placeRelative(beside - chartWidth, 0)
                receivedP.placeRelative(width - receivedP.width, (half - receivedP.height) / 2)
                sentP.placeRelative(width - sentP.width, half + (half - sentP.height) / 2)
            }
        } else {
            val chartP = chartSlot.measure(Constraints.fixed(width, CHART_HEIGHT.roundToPx()))
            val under = chartP.height + RATES_UNDER_GAP.roundToPx()
            // Side by side under the chart where both fit, one over the other
            // where the text is too large even for that.
            val sideBySide = receivedP.width + gap + sentP.width <= width
            val height = under + if (sideBySide) max(receivedP.height, sentP.height) else receivedP.height + sentP.height
            layout(width, height) {
                chartP.placeRelative(0, 0)
                receivedP.placeRelative(0, under)
                if (sideBySide) {
                    sentP.placeRelative(receivedP.width + gap, under)
                } else {
                    sentP.placeRelative(0, under + receivedP.height)
                }
            }
        }
    }
}

/** A bar's height in pixels: nothing for a second that moved nothing, and never a bar too short to see. */
private fun barHeight(bytes: Long, peak: Long, room: Float, least: Float): Float {
    if (bytes <= 0L) return 0f
    return max(throughputHeight(bytes, peak) * room, min(least, room))
}

@Composable
private fun PkRate(mark: String, bytes: () -> Long, color: Color, style: TextStyle, column: Dp) {
    Text(
        text = "$mark ${throughputRate(bytes())}",
        style = style,
        color = color,
        textAlign = TextAlign.End,
        maxLines = 1,
        softWrap = false,
        // The column is the width of the widest rate, so the chart beside it is
        // not a pixel wider one second and narrower the next.
        modifier = Modifier.widthIn(min = column)
    )
}

@Composable
private fun rememberRateColumn(style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(style, density) {
        with(density) {
            // 1023 is the widest figure the formatter prints before it changes unit.
            measurer.measure("$RECEIVED_MARK 1023 KB/s", style, maxLines = 1, softWrap = false).size.width.toDp()
        }
    }
}

/** The room a second has on the chart, bar and gap, at its least and at its most. */
private val BAR_SLOT = 5.dp
private val BAR_SLOT_WIDEST = 8.dp

/** How much of its room a bar fills; the rest is the gap to the next. */
private const val BAR_SHARE = 0.6f
private val BAR_LEAST = 1.5.dp

/** The gap each row of bars keeps from the rule between them. */
private val RULE_CLEARANCE = 1.dp
private val CHART_HEIGHT = 30.dp

/** Narrower than this beside the rates and it is a decoration; the rates go under it instead. */
private val CHART_LEAST_WIDTH = 120.dp
private val RATES_GAP = 12.dp
private val RATES_UNDER_GAP = 6.dp
