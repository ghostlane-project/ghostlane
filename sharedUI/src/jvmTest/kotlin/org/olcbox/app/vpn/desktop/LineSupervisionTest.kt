package org.olcbox.app.vpn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The rule the desktop manager executes when a process of a connected session
// dies. Taking the tun down to recover is the moment traffic leaves directly, so
// which deaths are recovered behind it, and how often, are pinned here.
class LineSupervisionTest {
    @Test
    fun aDeadCoreOrEngineIsRestartedBehindTheTun() {
        assertTrue(LineSupervision.restartsBehindTun(DeadProcess.Core))
        assertTrue(LineSupervision.restartsBehindTun(DeadProcess.Engine))
    }

    // Its routes went with it: there is nothing left to restart behind.
    @Test
    fun theTunsOwnDeathEndsTheSession() {
        assertFalse(LineSupervision.restartsBehindTun(DeadProcess.Tun))
    }

    @Test
    fun theWaitDoublesFromTwoSecondsAndStaysAtThirty() {
        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..5).map(LineSupervision::backoffMs)
        )
    }

    // A session may last for days, and a wait computed by the shift alone wraps:
    // nothing at attempt 62, two seconds again at 64. The cap is applied first.
    @Test
    fun aLongOutageKeepsTheCap() {
        for (attempt in listOf(62, 63, 64, 1_000_000, Int.MAX_VALUE)) {
            assertEquals(30_000L, LineSupervision.backoffMs(attempt), "attempt $attempt")
        }
    }

    @Test
    fun aNegativeAttemptIsTheFirst() {
        assertEquals(2_000L, LineSupervision.backoffMs(-1))
        assertEquals(2_000L, LineSupervision.backoffMs(Int.MIN_VALUE))
    }
}
