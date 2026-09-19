package org.olcbox.app.ui.components.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sin
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette

/**
 * The board: a list of rooms, grouped by the list they came from.
 *
 * The card is the whole point of the redesign. A row with a name, a latency and
 * a radio dot is what every client draws; this one says how many seats the room
 * has, which of them are taken, where yours is, how that has been moving, and —
 * once selected — what the connection will look like from outside.
 */

// ── ping ───────────────────────────────────────────────────────────────────

/**
 * Latency's colour.
 *
 * Green rather than lime for a good measurement, though lime is the app's accent:
 * on this card lime means *your seat*, and a fast server wearing the same colour
 * as the seat you hold reads as a second selection. The thresholds are the ones
 * the app already used — 150ms and 400ms — not the reference's 60/120, which are
 * a designer's numbers rather than a VPN's.
 */
@Composable
fun pkPingColor(pingMs: Int): Color {
    val palette = LocalPkPalette.current
    return when {
        pingMs < 150 -> palette.success
        pingMs < 400 -> palette.accent2
        else -> palette.danger
    }
}

/** Three lightweight pips that keep moving while a network measurement is alive. */
@Composable
private fun PkBouncingDots(
    color: Color,
    modifier: Modifier = Modifier
) {
    val phase by rememberInfiniteTransition(label = "latencyDots").animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "latencyDotsPhase"
    )
    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            Box(
                Modifier
                    .padding(horizontal = 1.5.dp)
                    .offset(y = (-abs(sin(phase - index * 0.75f)) * 3f).dp)
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// ── seats ──────────────────────────────────────────────────────────────────

/** Seats as pips, or as a proportion where there are too many to count. */
@Composable
fun PkSeats(display: SeatDisplay, modifier: Modifier = Modifier) {
    val palette = LocalPkPalette.current
    when (display) {
        is SeatDisplay.Pips -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            display.seats.forEach { seat ->
                val color by animateColorAsState(
                    targetValue = when (seat) {
                        SeatState.Mine -> palette.accent
                        SeatState.Taken -> palette.seatOther
                        SeatState.Free -> palette.seatFree
                    },
                    label = "seat"
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color)
                )
            }
        }

        is SeatDisplay.Bar -> Box(
            modifier = modifier
                .height(9.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.seatFree)
        ) {
            if (display.fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(display.fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (display.mine) palette.accent else palette.seatOther)
                )
            }
        }

        SeatDisplay.None -> Unit
    }
}

/**
 * How full this room has been over the last few polls.
 *
 * Small on purpose — it is not a chart, it is the answer to "is this filling up
 * or emptying out", which a single number cannot give however current it is.
 *
 * Two things this got wrong on a phone. It drew in `seatOther`, which is the
 * colour of a seat somebody else holds — dark blue-grey, and on the card's own
 * dark surface it was very nearly invisible, so every room the user was not in
 * appeared to have no line at all. And a flat line carries its meaning entirely
 * in its height, which cannot be read without knowing where the floor is; a room
 * steady at five of eight looked exactly as dead as an empty one.
 */
@Composable
fun PkSparkline(
    history: List<Float>,
    mine: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalPkPalette.current
    val color = if (mine) palette.accent else palette.textDim
    val floor = palette.seatFree
    Canvas(modifier = modifier.size(width = 46.dp, height = 12.dp)) {
        val points = sparklinePoints(history, size.width, size.height, inset = 1.5f)
        if (points.isEmpty()) return@Canvas
        // The floor, so the height of the trace above it is what says how full
        // the room is. Without it a flat line is just a flat line.
        drawLine(
            color = floor,
            start = Offset(0f, size.height - 1.5f),
            end = Offset(size.width, size.height - 1.5f),
            strokeWidth = 1.dp.toPx()
        )
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

// ── the room card ──────────────────────────────────────────────────────────

/**
 * One room, or one server.
 *
 * Selecting is separate from connecting: tapping a card never yanks a live
 * session on its own — the screen above decides whether to re-join, exactly as
 * it did when this was a row with a radio dot.
 */
@Composable
fun PkRoomCard(
    title: String,
    tag: String?,
    emoji: String?,
    selected: Boolean,
    connectedHere: Boolean,
    blocked: Boolean,
    seats: SeatDisplay,
    seatCountText: String?,
    freeText: String?,
    freeIsFull: Boolean,
    freeIsTight: Boolean,
    history: List<Float>,
    pingMs: Int?,
    isMeasuring: Boolean,
    isOffline: Boolean,
    /**
     * The coordinator does not recognise this room's key any more, so nothing here
     * will connect until the list is fetched again.
     */
    keyGone: Boolean,
    wire: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMeasure: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /**
     * One more line said where the seats would be, when there is something the
     * user should know before pressing connect — a link that asked for a
     * transport this provider cannot carry, say. Same voice as the revoked-key
     * line, for the same reason.
     */
    notice: String? = null,
    /**
     * Drawn at the end of the name row. A card whose location the user added
     * themselves puts its remove control here: a long-press is not something an
     * interface can tell you about, and the only other way out was a settings
     * screen the admin gate hides.
     */
    trailing: (@Composable () -> Unit)? = null
) {
    val palette = LocalPkPalette.current
    val background by animateColorAsState(
        targetValue = if (selected) palette.accent.copy(alpha = 0.06f)
        else MaterialTheme.colorScheme.surfaceContainer,
        label = "roomBg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) palette.accent else MaterialTheme.colorScheme.outlineVariant,
        label = "roomBorder"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            // A full room is dimmed rather than hidden: it is still information,
            // and it comes back the moment somebody leaves.
            .alpha(if (blocked && !selected) 0.5f else 1f)
            .combinedClickable(
                onClick = { if (!blocked) onClick() },
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!emoji.isNullOrBlank()) {
                Text(text = emoji, fontSize = 15.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = if (selected) palette.accent else MaterialTheme.colorScheme.onSurface,
                maxLines = if (selected) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                // The name takes the room, and the tag beside it is one word. Both
                // used to be sized to their content with the tag carrying the whole
                // of `protocolLabels()`, which left "United States" nine characters
                // and an ellipsis.
                modifier = Modifier.weight(1f)
            )
            if (!tag.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(text = tag, style = pkMono(9, 0.6), color = palette.textMuted, maxLines = 1)
            }
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                trailing()
            }
            // No "SELECTED" badge: the lime border and the lime name already say
            // that, three times over. "YOUR SEAT" stays because it says something
            // else — that the session running right now is on this card.
            if (connectedHere) {
                Spacer(Modifier.width(8.dp))
                PkBadge(text = "YOUR SEAT", highlighted = true)
            }
            // The title carries the weight, so nothing after it is pushed apart on
            // its own — without this the tag and the seat count ran together as
            // "SEI7 free".
            Spacer(Modifier.width(10.dp))
            if (!freeText.isNullOrBlank()) {
                Text(
                    text = freeText,
                    style = pkMono(10, 0.4),
                    color = when {
                        freeIsFull -> palette.danger
                        freeIsTight -> palette.accent2
                        else -> palette.accent
                    },
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
            }
            val ping = pingReading(
                pingMs = pingMs,
                isMeasuring = isMeasuring,
                failed = isOffline,
                connectedHere = connectedHere
            )
            val pingColor = when (ping.state) {
                PkPingState.Measured -> pkPingColor(pingMs ?: 0)
                // Amber, not red: a probe that got nothing is a fact about the
                // attempt, and red here read as a verdict on the server.
                PkPingState.NoAnswer -> palette.accent2
                PkPingState.Measuring, PkPingState.Unmeasured -> palette.textMuted
            }
            if (ping.state == PkPingState.Measuring) {
                PkBouncingDots(color = pingColor, modifier = Modifier.width(54.dp))
            } else {
                Text(
                    text = ping.label,
                    style = pkMono(10, 0.4),
                    color = pingColor,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(54.dp)
                )
            }
        }

        // Said where the seats would be, because their absence is the symptom the
        // user sees first — and said at all, because silence here is what makes a
        // revoked key look like a broken app.
        if (keyGone) {
            Text(
                text = "KEY NO LONGER VALID · REFRESH THIS LIST",
                style = pkMono(9, 1.1),
                color = palette.accent2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (notice != null) {
            Text(
                text = notice,
                style = pkMono(9, 1.1),
                color = palette.accent2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (seats != SeatDisplay.None) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PkSeats(display = seats, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                PkSparkline(history = history, mine = connectedHere)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = seatCountText.orEmpty(),
                    style = pkMono(10, 0.2),
                    color = palette.textDim,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(44.dp)
                )
            }
        }

        if (selected) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = wire.uppercase(),
                    style = pkMono(9, 0.8),
                    color = palette.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (onMeasure != null) {
                    Spacer(Modifier.width(10.dp))
                    val measureModifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                    if (isMeasuring) {
                        PkBouncingDots(color = palette.link, modifier = measureModifier.width(34.dp))
                    } else {
                        Text(
                            text = "MEASURE",
                            style = pkMono(10, 1.2),
                            color = palette.link,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(
                                    onClickLabel = "Measure latency",
                                    role = Role.Button
                                ) { onMeasure() }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PkBadge(text: String, highlighted: Boolean) {
    val palette = LocalPkPalette.current
    Text(
        text = text,
        style = pkMono(8, 1.1),
        color = if (highlighted) palette.accent else palette.textDim,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (highlighted) palette.accent.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceContainerHighest
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

// ── group header ───────────────────────────────────────────────────────────

/**
 * The head of one server list: fold, name, what the provider said about itself,
 * and the controls that belong to the whole group.
 *
 * The reference drew a single refresh button here. This keeps all four the app
 * has — the provider's page, its support contact, measure and refresh — because
 * they work today and removing a working control is not a design change.
 */
@Composable
fun PkGroupHeader(
    title: String,
    meta: String?,
    collapsed: Boolean,
    collapsible: Boolean,
    holdsSelection: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit
) {
    val palette = LocalPkPalette.current
    val turn by animateFloatAsState(if (collapsed) 0f else 90f, label = "groupChevron")
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (collapsible) {
                        Modifier.clickable(
                            onClickLabel = if (collapsed) "Expand" else "Collapse",
                            role = Role.Button
                        ) { onToggle() }
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (collapsible) {
                Icon(
                    imageVector = PkIcons.ChevronRight,
                    contentDescription = null,
                    tint = palette.textDim,
                    modifier = Modifier.size(17.dp).rotate(turn)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            // Folded away, a group still has to admit it holds the exit in use —
            // otherwise the screen shows no selection at all.
            if (collapsed && holdsSelection) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(palette.accent))
            }
            if (!meta.isNullOrBlank()) {
                Spacer(Modifier.width(9.dp))
                Text(
                    text = meta,
                    style = pkMono(9, 1.0),
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), content = actions)
    }
}

/**
 * What is left of a plan, as a bar.
 *
 * Drawn only where both sides of the quota parsed into bytes — see
 * `planFraction`. A provider that reports nothing, or reports it in a spelling
 * the app does not know, gets no bar rather than a bar that is guessing.
 */
@Composable
fun PkPlanBar(
    label: String,
    value: String,
    fraction: Float,
    modifier: Modifier = Modifier
) {
    val palette = LocalPkPalette.current
    // Amber only when the allowance is nearly gone. Anything earlier is a warning
    // about a plan that is being used, which is what a plan is for.
    val color = if (fraction > 0.85f) palette.accent2 else palette.accent
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, palette.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label.uppercase(),
                style = pkMono(9, 1.2),
                color = palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(text = value, style = pkMono(10, 0.2), color = color, maxLines = 1)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }
    }
}
