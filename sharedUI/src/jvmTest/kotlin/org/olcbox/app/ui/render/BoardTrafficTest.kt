package org.olcbox.app.ui.render

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import org.olcbox.app.ui.components.kit.THROUGHPUT_PENDING
import org.olcbox.app.vpn.TrafficCounters
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the status strip's traffic comes from, and what stands there when
 * nothing counts it.
 *
 * Reported from a Mac on 1.0.459: the chart does not run. Nothing on a desktop
 * counted a session's traffic, and since 1.0.459 the chart stands from the press
 * whether or not anything will ever be drawn on it: an empty rule and two rates
 * that read "…" for as long as the session lasted. On the phones it ran, as a
 * comb — the screen read once a second a value the platform refreshed every
 * three seconds on an iPhone and every fifteen on Android.
 */
@OptIn(ExperimentalTestApi::class)
class BoardTrafficTest {
    private val phone = Phone("iphone", 393, 852)
    private val app = AppFixture()

    @BeforeTest fun begin() = app.begin()
    @AfterTest fun end() = app.end()

    /**
     * The two rates beside the chart: an arrow and a figure, or an arrow and
     * what stands for a figure still to come. Not the line of totals over them,
     * which opens with an arrow too and has both.
     */
    private fun DesktopComposeUiTest.rates(): List<String> =
        laidTexts().map { it.text }.filter { text ->
            (text.startsWith("↓ ") || text.startsWith("↑ ")) &&
                (text.endsWith("/s") || text.endsWith(THROUGHPUT_PENDING))
        }

    @Test fun whereNothingCountsASessionsTrafficTheStripHasNoChartToWaitFor() {
        val uncounted = Tunnel(upSince = SESSION, counts = false)
        app.onTheScreen(phone, app.models(Memory(AppFixture.stored()), uncounted)) {
            mainClock.advanceTimeBy(3_000L)
            waitForIdle()
            assertEquals(emptyList(), rates(), "rates that nothing will ever measure")
        }
    }

    @Test fun whereItIsCountedTheChartStandsBeforeItsFirstSecond() {
        val counted = Tunnel(upSince = SESSION, counters = TrafficCounters(0L, 0L))
        app.onTheScreen(phone, app.models(Memory(AppFixture.stored()), counted)) {
            assertEquals(listOf("↓ $THROUGHPUT_PENDING", "↑ $THROUGHPUT_PENDING"), rates())
        }
    }

    @Test fun theCountersAreReadOnceForEverySecondOfASession() {
        // Read, by the screen's own second, and not looked up in a value that
        // something else refreshes when it gets round to it.
        val counted = Tunnel(upSince = SESSION, counters = TrafficCounters(0L, 0L))
        app.onTheScreen(phone, app.models(Memory(AppFixture.stored()), counted)) {
            val before = counted.readings
            mainClock.advanceTimeBy(5_000L)
            waitForIdle()
            val asked = counted.readings - before
            assertTrue(asked in 4..6, "five seconds of a session read the counters $asked times")
        }
    }

    @Test fun aSessionIsReadTheMomentItsScreenIsThere() {
        // Not a second later. A screen that comes back to a session — a phone's
        // app returned to — has the seconds it was away to set down at once,
        // and a timer that is a second behind for a second is a timer that jumps.
        val counted = Tunnel(upSince = SESSION, counters = TrafficCounters(0L, 0L))
        app.onTheScreen(phone, app.models(Memory(AppFixture.stored()), counted)) {
            assertEquals(1, counted.readings, "the counters as the screen opens")
        }
    }

    @Test fun whileNobodyCanSeeTheScreenNothingIsRead() {
        // An Android app in the background lives on for as long as its tunnel
        // does, and a desktop's window sits in the tray. A reading a second for
        // a chart nobody is looking at is a wakeup a second for nothing.
        val counted = Tunnel(upSince = SESSION, counters = TrafficCounters(0L, 0L))
        val screen = Seen(inFront = true)
        app.onTheScreen(phone, app.models(Memory(AppFixture.stored()), counted), seenBy = screen) {
            mainClock.advanceTimeBy(2_000L)
            waitForIdle()
            screen.inFront = false
            waitForIdle()
            val whenItWent = counted.readings

            mainClock.advanceTimeBy(10_000L)
            waitForIdle()
            assertEquals(whenItWent, counted.readings, "the counters were read behind a screen nobody could see")

            screen.inFront = true
            waitForIdle()
            assertEquals(whenItWent + 1, counted.readings, "the screen came back and was not read at once")
        }
    }

    @Test fun withNoSessionUpNothingIsRead() {
        val down = Tunnel(upSince = null, counters = TrafficCounters(0L, 0L))
        app.onTheScreen(phone, app.models(Memory(AppFixture.stored()), down)) {
            mainClock.advanceTimeBy(5_000L)
            waitForIdle()
            assertEquals(0, down.readings, "the counters of a tunnel that is not up were read")
            assertEquals(emptyList(), rates())
        }
    }

    private companion object {
        const val SESSION = 1_760_000_000_000L
    }
}
