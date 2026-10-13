package org.olcbox.app.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a desktop window's content is told about being seen.
 *
 * A window closed to the tray is hidden, not destroyed, and its own lifecycle
 * goes on reading "started": it knows minimised and unfocused, not put away.
 * Whatever the content does only while somebody is looking — the tick that
 * reads a session's traffic from the tunnel, once a second — went on behind a
 * window nobody could see, for as long as the app sat in the tray connected,
 * which is how a desktop VPN spends its day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShownLifecycleTest {
    @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun restore() = Dispatchers.resetMain()

    private class Window(state: Lifecycle.State) : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = state }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun contentOf(window: Window, shown: Boolean = true) =
        ShownLifecycleOwner(window).apply { this.shown = shown; attach() }

    @Test fun whileTheWindowIsShownItsContentLivesAsTheWindowDoes() {
        val window = Window(Lifecycle.State.RESUMED)
        val content = contentOf(window)
        assertEquals(Lifecycle.State.RESUMED, content.lifecycle.currentState)

        // Another window has the keyboard: seen, not typed into.
        window.registry.currentState = Lifecycle.State.STARTED
        assertEquals(Lifecycle.State.STARTED, content.lifecycle.currentState)

        // Minimised.
        window.registry.currentState = Lifecycle.State.CREATED
        assertEquals(Lifecycle.State.CREATED, content.lifecycle.currentState)
    }

    @Test fun aWindowPutAwayIsNotStartedWhateverItsOwnLifecycleReads() {
        val window = Window(Lifecycle.State.RESUMED)
        val content = contentOf(window)

        content.shown = false
        assertEquals(Lifecycle.State.CREATED, content.lifecycle.currentState)
        // Its own lifecycle moving behind it changes nothing.
        window.registry.currentState = Lifecycle.State.STARTED
        assertEquals(Lifecycle.State.CREATED, content.lifecycle.currentState)

        content.shown = true
        assertEquals(Lifecycle.State.STARTED, content.lifecycle.currentState)
        window.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(Lifecycle.State.RESUMED, content.lifecycle.currentState)
    }

    @Test fun contentThatBeginsPutAwayBeginsBelowStarted() {
        val content = contentOf(Window(Lifecycle.State.RESUMED), shown = false)
        assertEquals(Lifecycle.State.CREATED, content.lifecycle.currentState)
    }

    @Test fun beingShownDoesNotStartAWindowThatIsMinimised() {
        val window = Window(Lifecycle.State.CREATED)
        val content = contentOf(window, shown = false)
        content.shown = true
        assertEquals(Lifecycle.State.CREATED, content.lifecycle.currentState)
    }

    @Test fun whenItIsLetGoItIsOver() {
        val window = Window(Lifecycle.State.RESUMED)
        val content = contentOf(window)
        content.detach()
        assertEquals(Lifecycle.State.DESTROYED, content.lifecycle.currentState)
        // And it no longer follows the window it was content of.
        window.registry.currentState = Lifecycle.State.STARTED
        assertEquals(Lifecycle.State.DESTROYED, content.lifecycle.currentState)
    }
}
