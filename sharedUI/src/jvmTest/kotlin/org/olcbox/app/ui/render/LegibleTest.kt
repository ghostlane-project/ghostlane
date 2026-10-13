package org.olcbox.app.ui.render

import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What is written can be told from what it is written on.
 *
 * It was on every screenshot of the board that had old measurements on it, from a
 * phone and from a Mac, and nobody had read it: the line over the lists that says
 * the delays were measured on another network. It had a style and no colour, and
 * a text with no colour is drawn in the default one, which on this theme is black
 * on a ground a shade off black.
 *
 * The other tests here ask where a text is and whether all of it is there. This
 * one asks whether it can be seen.
 */
@OptIn(ExperimentalTestApi::class)
class LegibleTest {
    private val phone = Phone("iphone", 393, 852)

    @Test fun everyTextOnTheBoardStandsOutFromItsGround() {
        val scenes = mapOf(
            "idle" to BoardFixture.Scene(connected = false),
            "idle, measured on another network" to BoardFixture.Scene(connected = false, stalePings = true),
            "connected" to BoardFixture.Scene(connected = true, stalePings = true),
            "connecting" to BoardFixture.Scene(connected = false, connecting = true),
            "nothing measured" to BoardFixture.Scene(connected = false, measured = false, seats = false),
            "something went wrong" to BoardFixture.Scene(connected = false, notice = BoardFixture.NOTICE),
            "the fastest of a list" to BoardFixture.Scene(connected = true, lowest = true),
            "a list folded away" to BoardFixture.Scene(connected = false, folded = setOf(BoardFixture.OUR_LIST)),
            "no list at all" to BoardFixture.Scene(connected = false, empty = true),
            "further down" to BoardFixture.Scene(connected = true, scrolledBy = 900),
            "the end of the list" to BoardFixture.Scene(connected = false, admin = true, scrolledBy = 4000)
        )
        val faint = scenes.flatMap { (name, scene) ->
            measuredOn(phone, { BoardFixture.Home(scene) }) { faintTexts().map { "$name: $it" } }
        }
        assertTrue(faint.isEmpty(), "written where it cannot be read:\n" + faint.joinToString("\n"))
    }

    @Test fun soDoesEveryTextOnEveryOtherScreen() {
        val faint = Screens.all.flatMap { (name, screen) ->
            measuredOn(phone, screen) {
                // A sheet is not on the screen in its first frame.
                mainClock.advanceTimeBy(600)
                waitForIdle()
                faintTexts().map { "$name: $it" }
            }
        }
        assertTrue(faint.isEmpty(), "written where it cannot be read:\n" + faint.joinToString("\n"))
    }
}
