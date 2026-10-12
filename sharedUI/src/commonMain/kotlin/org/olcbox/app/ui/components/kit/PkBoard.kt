package org.olcbox.app.ui.components.kit

import multiplatform_app.sharedui.generated.resources.action_cancel_caps
import multiplatform_app.sharedui.generated.resources.action_connect_caps
import multiplatform_app.sharedui.generated.resources.action_connect_via_caps
import multiplatform_app.sharedui.generated.resources.action_disconnect_caps
import multiplatform_app.sharedui.generated.resources.action_leave_caps
import multiplatform_app.sharedui.generated.resources.action_room_full_caps
import multiplatform_app.sharedui.generated.resources.action_take_seat_caps
import multiplatform_app.sharedui.generated.resources.action_take_seat_in_caps
import multiplatform_app.sharedui.generated.resources.board_rooms
import multiplatform_app.sharedui.generated.resources.board_servers
import multiplatform_app.sharedui.generated.resources.no_ping
import multiplatform_app.sharedui.generated.resources.onboarding_add_list_caps
import multiplatform_app.sharedui.generated.resources.ping_ms
import multiplatform_app.sharedui.generated.resources.seats_free
import multiplatform_app.sharedui.generated.resources.seats_full
import multiplatform_app.sharedui.generated.resources.sort_as_served_caps
import multiplatform_app.sharedui.generated.resources.sort_az_caps
import multiplatform_app.sharedui.generated.resources.sort_ping_caps
import multiplatform_app.sharedui.generated.resources.wire_grpc
import multiplatform_app.sharedui.generated.resources.wire_https
import multiplatform_app.sharedui.generated.resources.wire_hysteria2
import multiplatform_app.sharedui.generated.resources.wire_olcrtc
import multiplatform_app.sharedui.generated.resources.wire_reality
import multiplatform_app.sharedui.generated.resources.wire_stream
import multiplatform_app.sharedui.generated.resources.wire_stream_no_handshake
import multiplatform_app.sharedui.generated.resources.wire_unknown
import multiplatform_app.sharedui.generated.resources.wire_xhttp
import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.action_collapse
import multiplatform_app.sharedui.generated.resources.action_expand
import multiplatform_app.sharedui.generated.resources.board_key_gone_caps
import multiplatform_app.sharedui.generated.resources.measure_latency
import multiplatform_app.sharedui.generated.resources.room_measure_caps
import multiplatform_app.sharedui.generated.resources.room_your_seat_caps
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette
import kotlin.math.max

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
            // Twelve above and below a name set on a twenty-point line: the card a
            // one-line name makes is the height it was when the line was twenty-four
            // and the padding ten, and a name on two lines is no longer two lines
            // with a third's worth of air between them.
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        val columns = LocalPkBoardColumns.current
        val ping = pingReading(
            pingMs = pingMs,
            isMeasuring = isMeasuring,
            failed = isOffline,
            connectedHere = connectedHere,
            words = boardWords()
        )
        PkNameLine(
            modifier = Modifier.fillMaxWidth(),
            // On one line the tag stands against the latency, where it always
            // stood; the name has everything before them.
            notesAtEnd = true,
            // The latency keeps the card's top corner while the name can wrap to
            // two lines beside it. A name that could not — the text is large, or
            // "no ping" in the user's language is — has the whole line, and the
            // latency goes under with the tag.
            nameLines = 2,
            lineGap = 5.dp,
            name = {
                Row {
                    if (!emoji.isNullOrBlank()) {
                        // No tracking: a flag is two letters that the font joins
                        // into one glyph, and letter-spacing is what stops a
                        // shaper joining them.
                        Text(
                            text = emoji,
                            style = LocalTextStyle.current.copy(fontSize = 15.sp, letterSpacing = 0.sp),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.alignByBaseline()
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = pkTied(title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (selected) palette.accent else MaterialTheme.colorScheme.onSurface,
                        // Three lines before an ellipsis. A name is the one thing on
                        // this card nothing else repeats, and "CA · olcRTC · …" is
                        // five rooms that read as one.
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alignByBaseline()
                    )
                }
            },
            notes = if (tag.isNullOrBlank() && !connectedHere) {
                null
            } else {
                {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!tag.isNullOrBlank()) {
                            Text(
                                text = tag,
                                style = pkMono(9, 0.6),
                                color = palette.textMuted,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        // No "SELECTED" badge: the lime border and the lime name
                        // already say that, three times over. "YOUR SEAT" stays
                        // because it says something else — that the session
                        // running right now is on this card.
                        if (connectedHere) {
                            PkBadge(text = stringResource(Res.string.room_your_seat_caps), highlighted = true)
                        }
                    }
                }
            },
            end = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (trailing != null) {
                        trailing()
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = ping.label,
                        style = pkMono(10, 0.4),
                        color = when (ping.state) {
                            PkPingState.Measured -> pkPingColor(pingMs ?: 0)
                            // Amber, not red: a probe that got nothing is a fact about
                            // the attempt, and red here read as a verdict on the server.
                            PkPingState.NoAnswer -> palette.accent2
                            PkPingState.Measuring, PkPingState.Unmeasured -> palette.textMuted
                        },
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        // As wide as the widest thing this column says, in the
                        // user's language and at the user's text size, so the tags
                        // before it line up down the list. It was fifty-four points
                        // whatever the text: "нет пинга" did not fit at any size.
                        modifier = Modifier.widthIn(min = columns.ping)
                    )
                }
            }
        )

        // Said where the seats would be, because their absence is the symptom the
        // user sees first — and said at all, because silence here is what makes a
        // revoked key look like a broken app.
        //
        // On as many lines as they take. Each of these is the reason a card will
        // not connect and what to do about it; cut after "KEY NO LONGER VALID ·"
        // it is half a reason and no instruction.
        if (keyGone) {
            Text(
                text = pkTied(stringResource(Res.string.board_key_gone_caps)),
                style = pkMono(9, 1.1),
                color = palette.accent2,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (notice != null) {
            Text(
                text = pkTied(notice),
                style = pkMono(9, 1.1),
                color = palette.accent2,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (seats != SeatDisplay.None) {
            PkSeatsLine(
                seats = seats,
                history = history,
                mine = connectedHere,
                free = freeText.orEmpty(),
                freeColor = when {
                    freeIsFull -> palette.danger
                    freeIsTight -> palette.accent2
                    else -> palette.accent
                },
                freeColumn = columns.free
            )
        }

        if (selected) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            PkNameLine(
                modifier = Modifier.fillMaxWidth(),
                // The sentence wraps beside the button while two lines there hold
                // it; when they do not, the button goes under it.
                nameLines = 2,
                gap = 10.dp,
                name = {
                    Text(
                        text = pkTied(wire.uppercase()),
                        style = pkMono(9, 0.8),
                        color = palette.textMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                end = if (onMeasure != null) {
                    {
                        Text(
                            text = if (isMeasuring) "···" else stringResource(Res.string.room_measure_caps),
                            style = pkMono(10, 1.2),
                            color = palette.link,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(
                                    onClickLabel = stringResource(Res.string.measure_latency),
                                    role = Role.Button
                                ) { onMeasure() }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                } else {
                    null
                }
            )
        }
    }
}

/**
 * How wide the two columns at the ends of a card's lines are: the latency, and
 * the count of free seats.
 *
 * Measured from the words themselves rather than set in points. A column sized in
 * points holds the text of one language at one text size.
 */
@Immutable
data class PkBoardColumns(val ping: Dp = Dp.Unspecified, val free: Dp = Dp.Unspecified)

/** Unspecified outside a board: a card on its own takes each text at its own width. */
val LocalPkBoardColumns = staticCompositionLocalOf { PkBoardColumns() }

/** The columns for a board in the user's language, at the user's text size. */
@Composable
fun rememberBoardColumns(words: BoardWords = boardWords()): PkBoardColumns {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = pkMono(10, 0.4)
    return remember(words, style, density) {
        fun widest(vararg texts: String): Dp = with(density) {
            texts.maxOf { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }.toDp()
        }
        PkBoardColumns(
            // Three digits, not four: a latency over a second is rare enough to
            // push its own card's tag aside rather than every card's.
            ping = widest(words.pingMs.fill(888), words.noPing, "···"),
            free = widest(words.seatsFree.fill(88), words.seatsFull)
        )
    }
}

/**
 * The seats, how they have been moving, and how many are free.
 *
 * In that order of claim on the line: the words are drawn whole, the seats take
 * what is left, and the trace is there only when the seats can spare it. Where
 * even the seats would be squeezed into a barcode, they take a line to themselves
 * and the words go under them.
 */
@Composable
private fun PkSeatsLine(
    seats: SeatDisplay,
    history: List<Float>,
    mine: Boolean,
    free: String,
    freeColor: Color,
    freeColumn: Dp
) {
    val least = seatsLeastWidth(seats)
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            PkSeats(display = seats)
            PkSparkline(history = history, mine = mine)
            Text(
                text = free,
                style = pkMono(10, 0.4),
                color = freeColor,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.widthIn(min = freeColumn)
            )
        }
    ) { measurables, constraints ->
        val (seatsSlot, traceSlot, wordsSlot) = measurables
        val width = constraints.maxWidth
        val gap = 10.dp.roundToPx()
        val wordsP = wordsSlot.measure(Constraints(maxWidth = width))
        val traceWidth = traceSlot.maxIntrinsicWidth(Constraints.Infinity)
        val beside = width - wordsP.width - if (wordsP.width > 0) gap else 0
        val leastPx = least.roundToPx()

        if (beside >= leastPx) {
            val traced = beside - traceWidth - gap >= leastPx
            val traceP = if (traced) traceSlot.measure(Constraints()) else null
            val seatsWidth = if (traced) beside - traceWidth - gap else beside
            val seatsP = seatsSlot.measure(Constraints.fixedWidth(seatsWidth))
            val height = maxOf(seatsP.height, wordsP.height, traceP?.height ?: 0)
            layout(width, height) {
                seatsP.placeRelative(0, (height - seatsP.height) / 2)
                traceP?.placeRelative(seatsWidth + gap, (height - traceP.height) / 2)
                wordsP.placeRelative(width - wordsP.width, (height - wordsP.height) / 2)
            }
        } else {
            val seatsP = seatsSlot.measure(Constraints.fixedWidth(width))
            val traced = width - wordsP.width - gap >= traceWidth
            val traceP = if (traced) traceSlot.measure(Constraints()) else null
            val under = seatsP.height + 6.dp.roundToPx()
            val second = max(wordsP.height, traceP?.height ?: 0)
            layout(width, under + second) {
                seatsP.placeRelative(0, 0)
                traceP?.placeRelative(0, under + (second - traceP.height) / 2)
                wordsP.placeRelative(width - wordsP.width, under + (second - wordsP.height) / 2)
            }
        }
    }
}

/**
 * The least width at which seats still read as seats.
 *
 * Six points a pip: under that sixteen of them are a barcode, and the count of
 * them — which is the thing pips are for — cannot be taken in.
 */
internal fun seatsLeastWidth(display: SeatDisplay): Dp = when (display) {
    is SeatDisplay.Pips -> (display.seats.size * 6).coerceAtLeast(56).dp
    is SeatDisplay.Bar -> 56.dp
    SeatDisplay.None -> 0.dp
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
    PkNameLine(
        modifier = modifier.fillMaxWidth(),
        // The list's name is whole before its buttons are beside it: where it
        // does not fit there on one line, they go under it. There are three to
        // five of them; on a phone they left "Encrypted list" seven letters, and
        // the same seven to every other list on the screen.
        notesIndent = if (collapsible) GROUP_TITLE_INDENT else 0.dp,
        gap = 9.dp,
        lineGap = 2.dp,
        name = {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (collapsible) {
                            Modifier.clickable(
                                onClickLabel = stringResource(if (collapsed) Res.string.action_expand else Res.string.action_collapse),
                                role = Role.Button
                            ) { onToggle() }
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = 6.dp)
            ) {
                // Beside the title's first line, not its middle: under a title
                // that has wrapped, a chevron centred on both lines points at the
                // gap between them.
                val firstLine = with(LocalDensity.current) { GROUP_TITLE_LINE.toDp() }
                if (collapsible) {
                    Icon(
                        imageVector = PkIcons.ChevronRight,
                        contentDescription = null,
                        tint = palette.textDim,
                        modifier = Modifier
                            .padding(top = ((firstLine - 17.dp) / 2).coerceAtLeast(0.dp))
                            .size(17.dp)
                            .rotate(turn)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = pkTied(title),
                    // No tracking, for the flag a provider may open its name with.
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = GROUP_TITLE_LINE,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Folded away, a group still has to admit it holds the exit in use —
                // otherwise the screen shows no selection at all.
                if (collapsed && holdsSelection) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .padding(top = ((firstLine - 7.dp) / 2).coerceAtLeast(0.dp))
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(palette.accent)
                    )
                }
            }
        },
        notes = if (meta.isNullOrBlank()) {
            null
        } else {
            {
                Text(
                    text = meta,
                    style = pkMono(9, 1.0),
                    color = palette.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        end = {
            Row(
                modifier = Modifier.offset(x = PK_GROUP_BUTTON_OVERHANG),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    )
}

/** The chevron and the space after it: where a title starts, and so where what is under it does. */
private val GROUP_TITLE_INDENT = 25.dp

/** The line a list's title is set on. */
private val GROUP_TITLE_LINE = 19.sp

/**
 * How far a group's last button reaches past the cards' edge.
 *
 * Its target is forty points and its square thirty-two, so the square sits four
 * points inside the target. Moved out by those four, the square is flush with the
 * cards under it and the part of the target nobody sees is over the margin.
 */
val PK_GROUP_BUTTON_OVERHANG = 4.dp

/**
 * What is left of a plan, as a bar.
 *
 * Drawn only where both sides of the quota parsed into bytes — see
 * `planFraction`. A provider that reports nothing, or reports it in a spelling
 * the app does not know, gets no bar rather than a bar that is guessing.
 */
/**
 * A provider's note to its users (the `announce` subscription header), under
 * its list and exactly as it was sent: at most 200 characters of plain text,
 * nothing in it made into a link.
 */
@Composable
fun PkProviderNote(text: String, modifier: Modifier = Modifier) {
    val palette = LocalPkPalette.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, palette.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    )
}

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
        PkNameLine(
            modifier = Modifier.fillMaxWidth(),
            lineGap = 3.dp,
            name = {
                Text(
                    text = label.uppercase(),
                    style = pkMono(9, 1.2),
                    color = palette.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            end = {
                Text(text = value, style = pkMono(10, 0.2), color = color, maxLines = 1, softWrap = false)
            }
        )
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

/** [BoardWords] in the user's language. */
@Composable
fun boardWords(): BoardWords = BoardWords(
    wireUnknown = stringResource(Res.string.wire_unknown),
    wireOlcrtc = stringResource(Res.string.wire_olcrtc),
    wireHysteria2 = stringResource(Res.string.wire_hysteria2),
    wireXhttp = stringResource(Res.string.wire_xhttp),
    wireGrpc = stringResource(Res.string.wire_grpc),
    wireReality = stringResource(Res.string.wire_reality),
    wireHttps = stringResource(Res.string.wire_https),
    wireStream = stringResource(Res.string.wire_stream),
    wireStreamNoHandshake = stringResource(Res.string.wire_stream_no_handshake),
    pingMs = stringResource(Res.string.ping_ms),
    noPing = stringResource(Res.string.no_ping),
    seatsFull = stringResource(Res.string.seats_full),
    seatsFree = stringResource(Res.string.seats_free),
    addServerList = stringResource(Res.string.onboarding_add_list_caps),
    cancel = stringResource(Res.string.action_cancel_caps),
    leave = stringResource(Res.string.action_leave_caps),
    disconnect = stringResource(Res.string.action_disconnect_caps),
    roomFull = stringResource(Res.string.action_room_full_caps),
    takeSeatIn = stringResource(Res.string.action_take_seat_in_caps),
    takeSeat = stringResource(Res.string.action_take_seat_caps),
    connectVia = stringResource(Res.string.action_connect_via_caps),
    connect = stringResource(Res.string.action_connect_caps),
    rooms = stringResource(Res.string.board_rooms),
    servers = stringResource(Res.string.board_servers),
    sortAsServed = stringResource(Res.string.sort_as_served_caps),
    sortPing = stringResource(Res.string.sort_ping_caps),
    sortAlphabetical = stringResource(Res.string.sort_az_caps)
)
