package org.olcbox.app.ui.features.home

import org.olcbox.app.ui.components.kit.boardWords
import multiplatform_app.sharedui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import multiplatform_app.sharedui.generated.resources.label_transport
import multiplatform_app.sharedui.generated.resources.measure_latency
import multiplatform_app.sharedui.generated.resources.ping_ms
import multiplatform_app.sharedui.generated.resources.room_free
import multiplatform_app.sharedui.generated.resources.room_last_minutes
import multiplatform_app.sharedui.generated.resources.room_latency
import multiplatform_app.sharedui.generated.resources.room_measure_caps
import multiplatform_app.sharedui.generated.resources.room_pick_hint
import multiplatform_app.sharedui.generated.resources.room_seats
import multiplatform_app.sharedui.generated.resources.room_selected
import multiplatform_app.sharedui.generated.resources.room_your_seat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.components.kit.PkSeats
import org.olcbox.app.ui.components.kit.PkSectionEyebrow
import org.olcbox.app.ui.components.kit.PkSparkline
import org.olcbox.app.ui.components.kit.SeatDisplay
import org.olcbox.app.ui.components.kit.pkDisplaySp
import org.olcbox.app.ui.components.kit.pkMono
import org.olcbox.app.ui.components.kit.pkPingColor
import org.olcbox.app.ui.components.kit.pkTied
import org.olcbox.app.ui.components.kit.seatCountText
import org.olcbox.app.ui.components.kit.seatDisplay
import org.olcbox.app.ui.components.kit.seatFreeText
import org.olcbox.app.ui.components.kit.transportTag
import org.olcbox.app.ui.components.kit.wireShape
import org.olcbox.app.ui.features.home.components.isChecking
import org.olcbox.app.ui.features.home.components.locationDisplayParts
import org.olcbox.app.ui.features.home.components.pingFor
import org.olcbox.app.ui.theme.LocalPkPalette

/**
 * The selected room, given a whole pane.
 *
 * Only ever seen beside the board, on a screen wide enough that stretching the
 * phone layout across it reads as a template rather than a design — which is
 * exactly the reading App Review gave the app, on an iPad Air.
 *
 * It says the same things the selected card says. That is the point: a wider
 * screen should show more of one thing, not the same thing further apart.
 */
@Composable
fun RoomDetailPane(
    board: HomeBoard,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier
) {
    val palette = LocalPkPalette.current
    val selected = board.model.subscriptionGroups
        .flatMap { it.locations }
        .plus(board.model.customLocations)
        .firstOrNull { it.storageId == board.selectedLocationId }

    if (selected == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.room_pick_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textMuted
            )
        }
        return
    }

    val (emoji, name) = locationDisplayParts(selected)
    val config = selected.config
    val slots = board.olcrtcSlots[selected.storageId]
    val seats = seatDisplay(slots, mine = board.isConnected)
    val ping = board.pingsState.pingFor(selected.storageId)
    val measuring = board.pingsState.isChecking(selected.storageId)

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PkSectionEyebrow(stringResource(if (board.isConnected) Res.string.room_your_seat else Res.string.room_selected))

        Row {
            if (emoji.isNotBlank()) {
                // No tracking, so a flag stays one glyph; see PkRoomCard.
                Text(
                    text = emoji,
                    style = LocalTextStyle.current.copy(
                        fontSize = pkDisplaySp(26),
                        lineHeight = pkDisplaySp(36),
                        letterSpacing = 0.sp
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alignByBaseline()
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = pkTied(name),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = pkDisplaySp(28),
                    lineHeight = pkDisplaySp(36),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline()
            )
        }

        Text(
            text = pkTied(wireShape(config, boardWords()).uppercase()),
            style = pkMono(10, 1.2),
            color = palette.textDim
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // A row while the four fit in one, and as many rows as they need when
            // they do not. In a fixed row the last of them was given what was left,
            // and at a large text size on a tablet that was one letter's width:
            // "LATENCY" stood down the pane's edge a letter to a line.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DetailStat(label = stringResource(Res.string.label_transport), value = transportTag(config) ?: "—")
                slots?.let {
                    DetailStat(label = stringResource(Res.string.room_seats), value = seatCountText(it).orEmpty())
                    DetailStat(
                        label = stringResource(Res.string.room_free),
                        value = seatFreeText(it, boardWords()).orEmpty(),
                        color = when {
                            it.slots_free <= 0 -> palette.danger
                            it.slots_free <= 2 -> palette.accent2
                            else -> palette.accent
                        }
                    )
                }
                DetailStat(
                    label = stringResource(Res.string.room_latency),
                    value = when {
                        measuring -> "···"
                        ping != null -> stringResource(Res.string.ping_ms, ping)
                        else -> "—"
                    },
                    color = ping?.let { pkPingColor(it) } ?: palette.textMuted
                )
            }

            if (seats != SeatDisplay.None) {
                PkSeats(display = seats, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(Res.string.room_last_minutes),
                        style = pkMono(9, 1.2),
                        color = palette.textMuted,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    PkSparkline(
                        history = board.occupancyHistory[selected.storageId].orEmpty(),
                        mine = board.isConnected
                    )
                }
            }
        }

        if (config != null && callbacks.canPing(config)) {
            Text(
                text = if (measuring) "···" else stringResource(Res.string.room_measure_caps),
                style = pkMono(11, 1.2),
                color = palette.link,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClickLabel = stringResource(Res.string.measure_latency), role = Role.Button) {
                        callbacks.onMeasure(listOf(selected.storageId))
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun DetailStat(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color? = null
) {
    val palette = LocalPkPalette.current
    Column {
        Text(text = label.uppercase(), style = pkMono(9, 1.2), color = palette.textMuted, maxLines = 1, softWrap = false)
        Spacer(Modifier.height(5.dp))
        Text(
            text = value,
            style = pkMono(15, 0.4).copy(fontWeight = FontWeight.Medium, lineHeight = 20.sp),
            color = color ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}
