package org.olcbox.app.ui.components.kit

import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.action_back
import multiplatform_app.sharedui.generated.resources.change_sorting
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.icons.PkIcons
import org.olcbox.app.ui.theme.LocalPkPalette
import kotlin.math.max

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
    // The halo's size whether or not there is a halo. As wide as whatever it
    // held, the dot's place grew by nine points when a session came up, and
    // every word beside it moved along by nine in that frame.
    val halo = (size * 2.1f).dp
    Box(Modifier.size(halo), contentAlignment = Alignment.Center) {
        if (pulse) {
            Box(Modifier.size(halo).background(color.copy(alpha = 0.18f), CircleShape))
        }
        Box(Modifier.size(size.dp).background(color, CircleShape))
    }
}

// ── icon buttons ───────────────────────────────────────────────────────────

/**
 * A bordered square, not a Material ripple circle. 40dp in the header, 32dp in a group.
 *
 * [touch] is how large the thing a finger lands on is, where that is more than
 * the square that is drawn: a group's buttons are thirty-two points to look at
 * and forty to press, which is the difference between a row of five that can be
 * used and one that takes two tries.
 *
 * [iconModifier] is for what moves — the arrows that turn while a list is
 * fetched. Turning the whole button turned its border with it.
 */
@Composable
fun PkIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 40,
    corner: Int = 12,
    tint: Color? = null,
    enabled: Boolean = true,
    touch: Int = size,
    iconModifier: Modifier = Modifier
) {
    val palette = LocalPkPalette.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(max(size, touch).dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = contentDescription,
                role = Role.Button
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(corner.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(corner.dp))
                // The press is shown on the square, wherever in the target it landed.
                .indication(interaction, LocalIndication.current),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = (tint ?: palette.textDim).copy(alpha = if (enabled) 1f else 0.4f),
                modifier = iconModifier.size((size * 0.475f).dp)
            )
        }
    }
}

// ── header row ─────────────────────────────────────────────────────────────

/** One button of the header: what it shows, what it is called, what it does. */
@Immutable
data class PkHeaderAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    /** Drawn at full strength: the one a new user needs first. */
    val strong: Boolean = false
)

/**
 * Brand and tag on the left, actions on the right.
 *
 * [onBrandTap] carries the hidden admin gesture that used to live on the app
 * bar's title — seven taps inside three seconds. It has no indication and no
 * ripple, which is the point of it.
 *
 * The three do not have equal claims on the row. The wordmark is drawn whole; the
 * buttons come next, at forty points where there is room and smaller where there
 * is not; and the tag is there only when all of it is, because half a tag —
 * `OLCRTC COR…` — reads as a mistake where no tag reads as nothing.
 */
@Composable
fun PkHeaderRow(
    tag: String,
    onBrandTap: () -> Unit,
    actions: List<PkHeaderAction>,
    modifier: Modifier = Modifier
) {
    val palette = LocalPkPalette.current
    val tapInteraction = remember { MutableInteractionSource() }
    val tap = Modifier.clickable(interactionSource = tapInteraction, indication = null) { onBrandTap() }
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        content = {
            Text(
                text = PkBrand.name,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = pkMarkSp(19),
                    lineHeight = pkMarkSp(28),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = tap
            )
            Text(
                text = tag,
                style = pkMono(9, 1.4).copy(fontSize = pkMarkSp(9), lineHeight = pkMarkSp(16)),
                color = palette.textMuted,
                maxLines = 1,
                softWrap = false,
                modifier = tap
            )
            actions.forEach { action ->
                PkIconButton(
                    icon = action.icon,
                    contentDescription = action.label,
                    onClick = action.onClick,
                    tint = if (action.strong) MaterialTheme.colorScheme.onSurface else null
                )
            }
        }
    ) { measurables, constraints ->
        val brandSlot = measurables[0]
        val tagSlot = measurables[1]
        val buttonSlots = measurables.drop(2)

        val brandWanted = brandSlot.maxIntrinsicWidth(Constraints.Infinity)
        // Asked how wide it would like to be: everything, at full size.
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            brandWanted + HEADER_TAG_GAP.roundToPx() + tagSlot.maxIntrinsicWidth(Constraints.Infinity) +
                HEADER_BUTTON_GAP.roundToPx() + buttonSlots.size * (HEADER_BUTTON + HEADER_BUTTON_GAP).roundToPx()
        }
        val fit = headerFit(
            width = width,
            brand = brandWanted,
            tag = tagSlot.maxIntrinsicWidth(Constraints.Infinity),
            buttons = buttonSlots.size,
            full = HEADER_BUTTON.roundToPx(),
            least = HEADER_BUTTON_LEAST.roundToPx(),
            gap = HEADER_BUTTON_GAP.roundToPx(),
            tightGap = HEADER_BUTTON_TIGHT_GAP.roundToPx(),
            tagGap = HEADER_TAG_GAP.roundToPx()
        )
        val buttons = buttonSlots.map { it.measure(Constraints.fixed(fit.button, fit.button)) }
        val buttonsWidth = fit.button * buttons.size + fit.gap * (buttons.size - 1).coerceAtLeast(0)
        val brandP = brandSlot.measure(
            Constraints(maxWidth = (width - buttonsWidth - fit.gap).coerceAtLeast(0))
        )
        val tagP = if (fit.showTag) tagSlot.measure(Constraints()) else null
        val line = alignOnLine(brandP, tagP)
        val height = max(line.height, fit.button)
        val lettersTop = (height - line.height) / 2

        layout(width, height) {
            brandP.placeRelative(0, lettersTop + line.ys[0])
            tagP?.placeRelative(brandP.width + HEADER_TAG_GAP.roundToPx(), lettersTop + line.ys[1])
            var x = width - buttonsWidth
            buttons.forEach { button ->
                button.placeRelative(x, (height - fit.button) / 2)
                x += fit.button + fit.gap
            }
        }
    }
}

private val HEADER_BUTTON = 40.dp
private val HEADER_BUTTON_LEAST = 26.dp
private val HEADER_BUTTON_GAP = 8.dp
private val HEADER_BUTTON_TIGHT_GAP = 5.dp
private val HEADER_TAG_GAP = 8.dp

/** How wide the header's buttons are, how far apart, and whether the tag is drawn. */
internal data class PkHeaderFit(val button: Int, val gap: Int, val showTag: Boolean)

/**
 * Divides the header's width, in pixels.
 *
 * The buttons keep their full size while they and the wordmark fit. When they do
 * not — a narrow phone with the admin's two extra buttons — they close up and
 * then shrink, down to [least]: small for a thumb, and reached only by five
 * buttons on the narrowest phone with its text enlarged, which is the admin's own
 * screen. The tag asks last and gets only a whole place.
 */
internal fun headerFit(
    width: Int,
    brand: Int,
    tag: Int,
    buttons: Int,
    full: Int,
    least: Int,
    gap: Int,
    tightGap: Int,
    tagGap: Int
): PkHeaderFit {
    if (buttons <= 0) return PkHeaderFit(button = full, gap = gap, showTag = brand + tagGap + tag <= width)
    val gaps = buttons - 1
    val room = width - brand - gap
    val fullWidth = buttons * full + gaps * gap
    if (fullWidth <= room) {
        return PkHeaderFit(button = full, gap = gap, showTag = tag > 0 && tag + tagGap <= room - fullWidth)
    }
    val tight = (room - gaps * tightGap) / buttons
    return PkHeaderFit(button = tight.coerceIn(least, full), gap = tightGap, showTag = false)
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
                .clickable(onClickLabel = stringResource(Res.string.action_back), role = Role.Button) { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = PkIcons.ArrowBack,
                contentDescription = stringResource(Res.string.action_back),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = pkDisplaySp(17),
                    lineHeight = pkDisplaySp(22),
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface,
                // Three lines before it is cut. A title is two or three words; on a
                // narrow phone at a large size each can need a line, and in a
                // font a little wider than ours "Server lists and sharing" did.
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = pkTied(subtitle.uppercase()),
                    style = pkMono(9, 1.3),
                    color = palette.textDim,
                    maxLines = 3,
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
    /**
     * The session's traffic, second by second. Its chart stands from the press
     * that asks for a session to the end of one: see [STRIP_RESHAPE_MS].
     */
    traffic: ThroughputWindow? = null
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
            // Inside the border and the padding, so that what moves is the
            // strip's edge with its border on it, and the list under that.
            .animateContentSize(tween(STRIP_RESHAPE_MS, easing = FastOutSlowInEasing))
    ) {
        PkStatusLines(
            dot = { PkPulseDot(color = stateColor, pulse = isActive) },
            label = {
                Text(
                    text = pkTied(label.uppercase()),
                    style = pkMono(10, 1.5),
                    color = stateColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            meta = {
                val metaText = meta()
                if (metaText.isNotBlank()) {
                    Text(
                        text = metaText,
                        // By what it says: counters and protocol names run left to
                        // right in every language, a sentence in the user's runs
                        // the way that language does.
                        style = pkMono(10, 0.6).copy(textDirection = TextDirection.ContentOrLtr),
                        color = palette.textDim,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            value = {
                val valueText = value()
                if (valueText.isNotBlank()) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = pkDisplaySp(17),
                            lineHeight = pkDisplaySp(20),
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        )

        // The one thing on this screen that moves by itself. Occupancy answers
        // "how full is the room" and barely changes; this answers "is anything
        // actually going through", which is the question a user stares at the
        // screen to have answered.
        //
        // There while the tunnel is coming up as well as while it is up. The
        // strip's height then changes at the press that asks for a session and
        // at the one that ends it, which is where a person expects the screen
        // to change — not a second or ten later when the tunnel answers, nor
        // two seconds after that when there is a second of traffic to draw.
        if ((isActive || isBusy) && traffic != null) {
            PkThroughput(traffic = traffic, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * How long the strip takes to change height.
 *
 * It heads the board, so every point it grows by is a point the whole list
 * moves: in one frame that is a jump under the reader's eyes, and the list they
 * were about to press is somewhere else. Over a quarter of a second it is the
 * strip opening, with the list following it. The same goes for a label that
 * takes a second line in one state and not in another, at a large text size.
 */
internal const val STRIP_RESHAPE_MS = 240

/**
 * The strip's three texts, arranged by how much line there is.
 *
 * With room, it is the shape it always had: the state over its detail on the
 * left, the one number on the right, level with both. That needs the detail —
 * `↓ 2.6 GB   ↑ 516 MB · HTTP 143ms` — to fit beside the number, which it does on
 * a wide phone at the default text size and on little else; everywhere else it
 * was cut at `· HT…`, and the latency, which is the part that changes, was the
 * part that went.
 *
 * So where the detail does not fit beside the number, the number moves up to the
 * state's line and the detail has the strip's whole width, on as many lines as
 * it needs. And where the state and the number cannot share a line either, each
 * of the three has its own — the state is not wrapped to keep the number beside
 * it: `IN A ROOM ·` over `OLCRTC` is a worse line than one more line.
 */
@Composable
private fun PkStatusLines(
    dot: @Composable () -> Unit,
    label: @Composable () -> Unit,
    meta: @Composable () -> Unit,
    value: @Composable () -> Unit
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Box { dot() }
            Box { label() }
            Box { meta() }
            Box { value() }
        }
    ) { measurables, constraints ->
        val (dotSlot, labelSlot, metaSlot, valueSlot) = measurables
        val dotP = dotSlot.measure(Constraints())
        val lead = dotP.width + STATUS_DOT_GAP.roundToPx()
        val labelWanted = labelSlot.maxIntrinsicWidth(Constraints.Infinity)
        val metaWanted = metaSlot.maxIntrinsicWidth(Constraints.Infinity)
        // Asked how wide it would like to be: wide enough for the shape it had
        // when there was room.
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            lead + max(labelWanted, metaWanted) + STATUS_VALUE_GAP.roundToPx() +
                valueSlot.maxIntrinsicWidth(Constraints.Infinity)
        }
        val column = (width - lead).coerceAtLeast(0)
        val valueP = valueSlot.measure(Constraints(maxWidth = column))
        val valueRoom = if (valueP.width > 0) valueP.width + STATUS_VALUE_GAP.roundToPx() else 0
        val beside = (column - valueRoom).coerceAtLeast(0)
        val textGap = STATUS_TEXT_GAP.roundToPx()

        when {
            labelWanted <= beside && metaWanted <= beside -> {
                val labelP = labelSlot.measure(Constraints(maxWidth = beside))
                val metaP = metaSlot.measure(Constraints(maxWidth = beside))
                val texts = labelP.height + if (metaP.height > 0) textGap + metaP.height else 0
                val height = maxOf(texts, valueP.height, dotP.height)
                val top = (height - texts) / 2
                layout(width, height) {
                    dotP.placeRelative(0, (height - dotP.height) / 2)
                    labelP.placeRelative(lead, top)
                    metaP.placeRelative(lead, top + labelP.height + textGap)
                    valueP.placeRelative(width - valueP.width, (height - valueP.height) / 2)
                }
            }

            labelWanted <= beside -> {
                val labelP = labelSlot.measure(Constraints(maxWidth = beside))
                val metaP = metaSlot.measure(Constraints(maxWidth = column))
                val line = alignOnLine(labelP, valueP)
                val first = max(line.height, dotP.height)
                val lettersTop = (first - line.height) / 2
                val under = if (metaP.height > 0) first + textGap else first
                layout(width, under + metaP.height) {
                    dotP.placeRelative(0, (first - dotP.height) / 2)
                    labelP.placeRelative(lead, lettersTop + line.ys[0])
                    valueP.placeRelative(width - valueP.width, lettersTop + line.ys[1])
                    metaP.placeRelative(lead, under)
                }
            }

            else -> {
                val labelP = labelSlot.measure(Constraints(maxWidth = column))
                val metaP = metaSlot.measure(Constraints(maxWidth = column))
                val first = max(labelP.height, dotP.height)
                val metaTop = if (metaP.height > 0) first + textGap else first
                val valueTop = metaTop + metaP.height + if (valueP.height > 0) textGap else 0
                layout(width, valueTop + valueP.height) {
                    dotP.placeRelative(0, (first - dotP.height) / 2)
                    labelP.placeRelative(lead, (first - labelP.height) / 2)
                    metaP.placeRelative(lead, metaTop)
                    valueP.placeRelative(lead, valueTop)
                }
            }
        }
    }
}

private val STATUS_DOT_GAP = 12.dp
private val STATUS_VALUE_GAP = 10.dp
private val STATUS_TEXT_GAP = 3.dp

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
        PkNameLine(
            modifier = Modifier.fillMaxWidth(),
            // The control that orders the list goes under the list's name before
            // the name is cut, or wrapped, to make room for it.
            name = {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = pkDisplaySp(21),
                        lineHeight = pkDisplaySp(28),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            end = if (showSort) {
                {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable(onClickLabel = stringResource(Res.string.change_sorting), role = Role.Button) {
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
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            } else {
                null
            }
        )
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
 *
 * [secondary], when there is one, is a smaller button beside it: Disconnect,
 * while a tunnel holds its traffic for a server to be picked and the one
 * button is the pick.
 */
@Composable
fun PkActionBar(
    action: PkAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: PkAction? = null,
    onSecondaryClick: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f))
    ) {
        PkHairline()
        // Inside the background, not around it: the bar's own surface has to
        // reach the bottom edge of the screen, while the buttons it holds sit
        // above the home indicator rather than under it.
        val inset = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
        if (secondary == null) {
            PkActionButton(action, onClick, inset)
        } else {
            Row(
                modifier = inset,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PkActionButton(action, onClick, Modifier.weight(1f))
                // Its own width, one short word, and never so wide that the
                // first button is left without room.
                PkActionButton(secondary, onSecondaryClick, Modifier.widthIn(max = 168.dp))
            }
        }
    }
}

/** One button of [PkActionBar], laid out by [modifier]. */
@Composable
private fun PkActionButton(action: PkAction, onClick: () -> Unit, modifier: Modifier) {
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
    val enabled = action.kind != PkActionKind.Blocked

    Box(
        modifier = modifier
            // At least the height it always had, and more when its sentence
            // (which names the room it acts on) needs a second line.
            .heightIn(min = 56.dp)
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
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (action.kind == PkActionKind.Busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    color = foreground,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
            }
            PkBalancedText(
                text = pkTied(action.label),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp
                ),
                color = foreground,
                // Not weighted to fill: the spinner and the words are centred
                // together, as they were when the words were one line.
                modifier = Modifier.weight(1f, fill = false)
            )
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
        Text(
            text = label.uppercase(),
            style = pkMono(10, 1.3),
            color = palette.textDim,
            textAlign = TextAlign.Center,
            // Not weighted: the icon and the words are centred together, and a
            // weighted text would take the whole row and push the icon to its edge.
            modifier = Modifier.weight(1f, fill = false)
        )
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
