package org.olcbox.app.ui.components.kit

import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.features.home.PINNED_HEAD_LEAST_HEIGHT
import org.olcbox.app.ui.features.home.headStaysPinned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PkLinesTest {

    // ── wrapping ───────────────────────────────────────────────────────────

    @Test
    fun aSeparatorIsTiedToTheWordBeforeItSoNoLineBeginsWithOne() {
        assertEquals("DE · olcRTC · WB", pkTied("DE · olcRTC · WB"))
        // A dot with no space on a side is part of a word, not a separator.
        assertEquals("v1.0·beta", pkTied("v1.0·beta"))
        assertEquals("Netherlands-Amsterdam-03", pkTied("Netherlands-Amsterdam-03"))
    }

    @Test
    fun aLabelIsCutAtTheSpaceNearestItsMiddle() {
        assertEquals("LEAVE DE ·\nOLCRTC · WB", pkBalanced(pkTied("LEAVE DE · OLCRTC · WB")))
        assertEquals("ДОБАВИТЬ СПИСОК\nСЕРВЕРОВ", pkBalanced("ДОБАВИТЬ СПИСОК СЕРВЕРОВ"))
        // Two spaces as near the middle as each other: the earlier one.
        assertEquals("TAKE\nA SEAT", pkBalanced("TAKE A SEAT"))
    }

    @Test
    fun aLabelOfOneWordIsLeftAsItIs() {
        assertEquals("DISCONNECT", pkBalanced("DISCONNECT"))
        assertEquals("", pkBalanced(""))
    }

    // ── the header ─────────────────────────────────────────────────────────

    private fun fit(width: Int, brand: Int, tag: Int = 75, buttons: Int = 3) = headerFit(
        width = width, brand = brand, tag = tag, buttons = buttons,
        full = 40, least = 26, gap = 8, tightGap = 5, tagGap = 8
    )

    @Test
    fun aRoomyHeaderHasFullButtonsAndItsTag() {
        // A 393-point phone less its margins, the wordmark at the default size.
        assertEquals(PkHeaderFit(button = 40, gap = 8, showTag = true), fit(width = 361, brand = 99))
    }

    @Test
    fun theTagIsDrawnWholeOrNotAtAll() {
        // 320 points: three full buttons and the wordmark fit, the tag does not.
        assertEquals(PkHeaderFit(button = 40, gap = 8, showTag = false), fit(width = 288, brand = 99))
        // One pixel short of the tag's whole place is no place.
        assertTrue(fit(width = 99 + 8 + 75 + 8 + 136, brand = 99).showTag)
        assertFalse(fit(width = 99 + 8 + 75 + 8 + 136 - 1, brand = 99).showTag)
    }

    @Test
    fun buttonsCloseUpAndShrinkBeforeTheWordmarkIsCut() {
        // The admin's five on a 320-point phone.
        val five = fit(width = 288, brand = 99, buttons = 5)
        assertEquals(5, five.gap)
        assertEquals(32, five.button)
        assertFalse(five.showTag)
        assertTrue(99 + 8 + five.button * 5 + five.gap * 4 <= 288, "the row must fit its width")
    }

    @Test
    fun buttonsNeverShrinkBelowTheirLeast() {
        assertEquals(26, fit(width = 200, brand = 150, buttons = 5).button)
    }

    @Test
    fun aHeaderWithNoButtonsStillDecidesItsTag() {
        assertTrue(fit(width = 288, brand = 99, buttons = 0).showTag)
        assertFalse(fit(width = 150, brand = 99, buttons = 0).showTag)
    }

    // ── the pinned head ────────────────────────────────────────────────────

    @Test
    fun theHeadIsPinnedWhereTheScreenIsTallInItsOwnText() {
        assertTrue(headStaysPinned(852.dp, 1f))
        assertTrue(headStaysPinned(667.dp, 1f))
        assertTrue(headStaysPinned(852.dp, 1.3f))
        assertTrue(headStaysPinned(PINNED_HEAD_LEAST_HEIGHT, 1f))
    }

    @Test
    fun theHeadScrollsWithTheListOnAShortScreenOrLargeText() {
        // A 4-inch phone at the default size, and a tall one at twice it.
        assertFalse(headStaysPinned(568.dp, 1f))
        assertFalse(headStaysPinned(852.dp, 1.6f))
        assertFalse(headStaysPinned(932.dp, 2f))
        // The phone the report came from: a 6.1-inch display zoomed, text enlarged.
        assertFalse(headStaysPinned(693.dp, 1.6f))
    }

    // ── seats ──────────────────────────────────────────────────────────────

    @Test
    fun seatsAskForSixPointsAPipAndNeverLessThanAShortRow() {
        assertEquals(96.dp, seatsLeastWidth(SeatDisplay.Pips(List(16) { SeatState.Free })))
        assertEquals(56.dp, seatsLeastWidth(SeatDisplay.Pips(List(8) { SeatState.Free })))
        assertEquals(56.dp, seatsLeastWidth(SeatDisplay.Bar(0.5f, mine = false)))
        assertEquals(0.dp, seatsLeastWidth(SeatDisplay.None))
    }
}
