package org.olcbox.app.ui.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import org.olcbox.app.ui.components.kit.PkActionBar
import org.olcbox.app.ui.components.kit.PkBoardHead
import org.olcbox.app.ui.components.kit.PkGroupHeader
import org.olcbox.app.ui.components.kit.PkHeaderAction
import org.olcbox.app.ui.components.kit.PkHeaderRow
import org.olcbox.app.ui.components.kit.PkPlanBar
import org.olcbox.app.ui.components.kit.PkRoomCard
import org.olcbox.app.ui.components.kit.PkStatusStrip
import org.olcbox.app.ui.components.kit.PkAction
import org.olcbox.app.ui.components.kit.PkActionKind
import org.olcbox.app.ui.components.kit.SeatDisplay
import org.olcbox.app.ui.components.kit.SeatState
import org.olcbox.app.ui.icons.PkIcons
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The kit's own layouts, asked how wide and how tall they would like to be.
 *
 * Each of them divides the width it is given. A parent that sizes itself to its
 * content gives none — it asks — and a layout that only knows how to divide
 * answers that with a width no screen has, or does not answer. Nothing on the
 * board asks today; the first thing that does should get an answer.
 */
@OptIn(ExperimentalTestApi::class)
class KitIntrinsicsTest {
    @Composable
    private fun Kit() {
        PkHeaderRow(
            tag = "OLCRTC CORE",
            onBrandTap = {},
            actions = List(3) { PkHeaderAction(PkIcons.History, "action $it", onClick = {}) }
        )
        PkStatusStrip(
            label = "in a room · olcRTC",
            isActive = true,
            isBusy = false,
            meta = { "↓ 2.6 GB   ↑ 516 MB · HTTP 143ms" },
            value = { "6:51:24" },
            traffic = BoardFixture.session()
        )
        PkBoardHead(heading = "Rooms", sortLabel = "AS SERVED", onSortClick = {})
        PkGroupHeader(
            title = "Encrypted list",
            meta = "upd 8m",
            collapsed = false,
            collapsible = true,
            holdsSelection = true,
            onToggle = {},
            actions = {}
        )
        PkPlanBar(label = "Traffic · resets in 12d", value = "44/976 GB", fraction = 0.05f)
        PkRoomCard(
            title = "DE · olcRTC · WB",
            tag = "VP8",
            emoji = null,
            selected = true,
            connectedHere = true,
            blocked = false,
            seats = SeatDisplay.Pips(List(8) { if (it < 3) SeatState.Taken else SeatState.Free }),
            freeText = "5 free",
            freeIsFull = false,
            freeIsTight = false,
            history = listOf(0.2f, 0.4f, 0.3f),
            pingMs = 143,
            isMeasuring = false,
            isOffline = false,
            keyGone = true,
            wire = "a WB Stream media session",
            onClick = {},
            onMeasure = {},
            notice = "LINK ASKS FOR VP8 · JITSI RUNS DATACHANNEL"
        )
        PkActionBar(action = PkAction("LEAVE DE · OLCRTC · WB", PkActionKind.Stop), onClick = {})
    }

    @Test fun askedForItsWidestItAnswersWithAWidthAScreenCouldHave() {
        measuredOn(Phone("wide", 2000, 1600), { Column(Modifier.width(IntrinsicSize.Max).testTag("kit")) { Kit() } }) {
            val width = onNodeWithTag("kit").fetchSemanticsNode().size.width / RENDER_DENSITY
            assertTrue(width > 200f && width < 1200f, "the kit would like to be $width points wide")
        }
    }

    @Test fun askedForItsNarrowestItStillLaysOut() {
        measuredOn(Phone("wide", 2000, 1600), { Column(Modifier.width(IntrinsicSize.Min).testTag("kit")) { Kit() } }) {
            val size = onNodeWithTag("kit").fetchSemanticsNode().size
            assertTrue(size.width > 0 && size.height > 0, "the kit laid out as $size")
        }
    }

    @Test fun askedForItsHeightAtAWidthItAnswers() {
        measuredOn(Phone("wide", 2000, 1600), { Column(Modifier.width(320.dpWidth()).height(IntrinsicSize.Min).testTag("kit")) { Kit() } }) {
            val size = onNodeWithTag("kit").fetchSemanticsNode().size
            assertTrue(size.height > 0, "the kit laid out as $size")
        }
    }

    private fun Int.dpWidth() = androidx.compose.ui.unit.Dp(this.toFloat())
}
