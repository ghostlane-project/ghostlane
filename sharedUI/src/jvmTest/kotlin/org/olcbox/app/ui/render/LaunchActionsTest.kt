package org.olcbox.app.ui.render

import androidx.compose.ui.test.ExperimentalTestApi
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.SubscriptionSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the app does by itself when it opens, and that it does it when it opens.
 *
 * "Connect on launch" was the screen's to remember, and an iPhone's screen is
 * built again on every return to the app ([BoardKeptTest] says why). So each
 * return was a launch: somebody who had disconnected, looked at another app and
 * come back was connected again, by an app that had been running all along.
 */
@OptIn(ExperimentalTestApi::class)
class LaunchActionsTest {
    private val phone = Phone("iphone", 393, 852)
    private val app = AppFixture()

    @BeforeTest fun begin() = app.begin()
    @AfterTest fun end() = app.end()

    private fun stored(settings: SubscriptionSettings.() -> SubscriptionSettings): LocationBundleV4 =
        AppFixture.stored().let { it.copy(settings = it.settings.settings()) }

    @Test fun anAppToldToConnectOnLaunchConnectsWhenItOpens() {
        app.onTheScreen(phone, app.models(Memory(stored { copy(connectOnLaunch = true) }))) {
            waitForIdle()
            assertEquals(1, app.toggled, "the app did not connect when it opened")
        }
    }

    @Test fun itDoesNotConnectAgainWhenItsScreenIsBuiltAgain() {
        app.onTheScreen(phone, app.models(Memory(stored { copy(connectOnLaunch = true) }))) { buildAgain ->
            waitForIdle()
            assertEquals(1, app.toggled, "the app did not connect when it opened")

            buildAgain()
            buildAgain()

            assertEquals(1, app.toggled, "a screen built again was taken for the app opening")
        }
    }

    @Test fun anAppNotToldToConnectsNothingByItself() {
        app.onTheScreen(phone, app.models(Memory(stored { this }))) { buildAgain ->
            waitForIdle()
            buildAgain()
            assertEquals(0, app.toggled, "the app connected without being told to")
        }
    }
}
