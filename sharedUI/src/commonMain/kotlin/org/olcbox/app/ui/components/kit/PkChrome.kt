package org.olcbox.app.ui.components.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette

/**
 * The furniture around the board: the header row, the status strip, the head of
 * the list and the one button at the bottom.
 *
 * None of it is a Material component. `CenterAlignedTopAppBar` over a circular
 * power dial is the shape every sing-box front end ships, and looking like them
 * is the thing this file exists to stop.
 */

// ── shared text styles ─────────────────────────────────────────────────────

/** Mono, uppercase, wide-tracked. The app's label voice. */
@Composable
fun pkMono(size: Int, tracking: Double = 1.4): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = size.sp,
        letterSpacing = tracking.sp
    )

// ── the pulsing dot ────────────────────────────────────────────────────────

/**
 * State as a dot, with a soft halo while a session is live.
 *
 * It used to swell and fade on an infinite transition. That is one decorative
 * animation, and it cost the whole screen: an infinite transition asks for a frame
 * every vsync for as long as it runs, and Compose Multiplatform redraws the scene
 * — grid background, every card, every canvas — on each of them. Connected for an
 * hour meant redrawing this screen two hundred thousand times, and a phone that
 * was cool in the background got hot the moment the app was on screen.
 *
 * The halo says connected without asking for a single extra frame. What actually
 * proves the session is alive is the throughput trace under it, which moves once a
 * second because bytes moved — a signal rather than a decoration.
 */
@Composable
fun PkPulseDot(color: Color, pulse: Boolean, size: Int = 8) {
    Box(contentAlignment = Alignment.Center) {
        if (pulse) {
            Box(
                Modifier
                    .size((size * 2.1f).dp)
                    .background(color.copy(alpha = 0.18f), CircleShape)
            )
        }
        Box(Modifier.size(size.dp).background(color, CircleShape))
    }
}

// ── icon buttons ───────────────────────────────────────────────────────────

/** A bordered square, not a Material ripple circle. 40dp in the header, 32dp in a group. */
@Composable
fun PkIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 40,
    corner: Int = 12,
    tint: Color? = null,
    enabled: Boolean = true
) {
    val palette = LocalPkPalette.current
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(corner.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(corner.dp))
            .clickable(enabled = enabled, onClickLabel = contentDescription, role = Role.Button) {
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = (tint ?: palette.textDim).copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size((size * 0.475f).dp)
        )
    }
}

// ── header row ─────────────────────────────────────────────────────────────

/**
 * Brand and tag on the left, actions on the right.
 *
 * [onBrandTap] carries the hidden admin gesture that used to live on the app
 * bar's title — seven taps inside three seconds. It has no indication and no
 * ripple, which is the point of it.
 */
@Composable
fun PkHeaderRow(
    tag: String,
    onBrandTap: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit
) {
    val palette = LocalPkPalette.current
    val tapInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(interactionSource = tapInteraction, indication = null) { onBrandTap() },
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = PkBrand.name,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = tag,
                style = pkMono(9, 1.4),
                color = palette.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/**
 * A back arrow and a title. What a screen that is not the board wears.
 *
 * The arrow is a bare 40dp target rather than a filled circle: a circular button
 * beside a title is the Material detail-screen signature, and this app is trying
 * not to wear it.
 */
@Composable
fun PkScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val palette = LocalPkPalette.current
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "Back", role = Role.Button) { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = PkIcons.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle.uppercase(),
                    style = pkMono(9, 1.3),
                    color = palette.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

// ── status strip ───────────────────────────────────────────────────────────

/**
 * One horizontal card instead of a centred stack.
 *
 * The stack it replaces spent the top third of the screen on a pill, a 26sp
 * timer, a traffic line and a caption — four elements saying one thing, above
 * the list the user actually came to read. Here the state is a dot and a label,
 * and the single number worth that much weight sits on the right.
 */
@Composable
fun PkStatusStrip(
    label: String,
    isActive: Boolean,
    isBusy: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Lambdas, not values, and that is the whole point of them.
     *
     * These three move once a second while a session is up. Read where the caller
     * builds this call, they invalidate the caller — which on the home screen is
     * the screen, so the entire board recomposed every second: every card, every
     * seat pip with its colour animation, every canvas. Read here, the second that
     * passes recomposes a strip.
     */
    meta: () -> String = { "" },
    value: () -> String = { "" },
    trafficTrace: () -> List<Float> = { emptyList() }
) {
    val palette = LocalPkPalette.current
    val stateColor = when {
        isActive -> palette.accent
        isBusy -> MaterialTheme.colorScheme.primary
        else -> palette.textMuted
    }
    val borderColor by animateColorAsState(
        targetValue = if (isActive) palette.accent.copy(alpha = 0.28f)
        else MaterialTheme.colorScheme.outlineVariant,
        label = "stripBorder"
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 15.dp, vertical = 13.dp)
    ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PkPulseDot(color = stateColor, pulse = isActive)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label.uppercase(),
                style = pkMono(10, 1.5),
                color = stateColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = meta(),
                style = pkMono(10, 0.6),
                color = palette.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val valueText = value()
        if (valueText.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }

        // The one thing on this screen that moves by itself. Occupancy answers
        // "how full is the room" and barely changes; this answers "is anything
        // actually going through", which is the question a user stares at the
        // screen to have answered.
        val trace = trafficTrace()
        if (isActive && trace.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            PkTrafficTrace(trace = trace, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * A throughput waveform: the line, and the area under it.
 *
 * The fill is what makes it read as a signal rather than a graph — and it is why
 * a busy second is legible at fourteen density-independent pixels, which is all
 * the room a status strip has.
 */
@Composable
fun PkTrafficTrace(trace: List<Float>, modifier: Modifier = Modifier) {
    val palette = LocalPkPalette.current
    val line = palette.accent
    val fill = palette.accent.copy(alpha = 0.16f)
    val floor = palette.seatFree
    Canvas(modifier = modifier.height(22.dp)) {
        drawLine(
            color = floor,
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1.dp.toPx()
        )
        val points = sparklinePoints(trace, size.width, size.height, inset = 2f)
        if (points.isEmpty()) return@Canvas

        val area = Path().apply {
            moveTo(points.first().x, size.height)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, size.height)
            close()
        }
        drawPath(path = area, color = fill)

        val stroke = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
            path = stroke,
            color = line,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

// ── board head ─────────────────────────────────────────────────────────────

/**
 * The heading over the list, its sort control, and the filter chips.
 *
 * Both controls sit directly above the rows they govern. Sorting used to be
 * reachable only from a settings screen two taps away, which is a control that
 * exists and cannot be found.
 */
@Composable
fun PkBoardHead(
    heading: String,
    sortLabel: String,
    onSortClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** False over an empty board: nothing to order is nothing to offer. */
    showSort: Boolean = true,
    chips: (@Composable () -> Unit)? = null
) {
    val palette = LocalPkPalette.current
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = heading,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showSort) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable(onClickLabel = "Change sorting", role = Role.Button) {
                            onSortClick()
                        }
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = PkIcons.SwapVert,
                        contentDescription = null,
                        tint = palette.textDim,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = sortLabel,
                        style = pkMono(10, 1.1),
                        color = palette.textDim,
                        maxLines = 1
                    )
                }
            }
        }
        if (chips != null) {
            Spacer(Modifier.height(9.dp))
            chips()
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** The rule that separates the pinned head from the scrolling board. */
@Composable
fun PkHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalPkPalette.current.hairline)
    )
}

// ── action bar ─────────────────────────────────────────────────────────────

/**
 * The single control that starts and stops a connection.
 *
 * A bar rather than the 200dp circular dial it replaces. The dial was the most
 * recognisable piece of the layout we share with every other client, and it had
 * a second problem: it could not say what it would do. This one always names its
 * object, so a user who has scrolled away from their selection can still read it
 * off the button.
 */
@Composable
fun PkActionBar(
    action: PkAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPkPalette.current
    val haptics = LocalHapticFeedback.current
    val background by animateColorAsState(
        targetValue = when (action.kind) {
            PkActionKind.Go -> palette.accent
            PkActionKind.Stop -> MaterialTheme.colorScheme.errorContainer
            PkActionKind.Busy, PkActionKind.Blocked ->
                MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "actionBg"
    )
    val foreground = when (action.kind) {
        PkActionKind.Go -> MaterialTheme.colorScheme.onTertiary
        PkActionKind.Stop -> Color(0xFFFF9FB0)
        PkActionKind.Busy -> palette.textDim
        PkActionKind.Blocked -> palette.textMuted
    }
    // Busy is an action as well as a status: the view model treats its second
    // tap as an authoritative cancellation. It cancels Lowest measurement and
    // any pending start before stopping the core, so it cannot accidentally
    // turn into another start against a Windows adapter that is being removed.
    val enabled = action.kind != PkActionKind.Blocked

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f))
    ) {
        PkHairline()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Inside the background, not around it: the bar's own surface has
                // to reach the bottom edge of the screen, while the button it
                // holds sits above the home indicator rather than under it.
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(background)
                .clickable(enabled = enabled, onClickLabel = action.label, role = Role.Button) {
                    // Connecting and leaving both take a moment to show; a tap
                    // with no confirmation reads as a tap that missed.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
                .semantics { contentDescription = action.label },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (action.kind == PkActionKind.Busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        color = foreground,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp
                    ),
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The dashed "add a list" affordance that closes the board. */
@Composable
fun PkDashedAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPkPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .pkDashedBorder(MaterialTheme.colorScheme.outline, 16.dp)
            .clickable(onClickLabel = label, role = Role.Button) { onClick() }
            .padding(14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = palette.textDim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(9.dp))
        Text(label.uppercase(), style = pkMono(10, 1.3), color = palette.textDim)
    }
}

/** A dashed outline. Compose has no dashed border modifier, so it is drawn. */
private fun Modifier.pkDashedBorder(color: Color, corner: Dp) = this.drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(corner.toPx()),
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
        )
    )
}
