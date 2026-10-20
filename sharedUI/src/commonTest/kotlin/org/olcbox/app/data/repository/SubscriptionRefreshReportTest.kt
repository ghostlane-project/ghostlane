package org.olcbox.app.data.repository

import org.olcbox.app.data.model.DeviceRefusal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a refresh's report decides for the screens: whether a running tunnel is
 * started again after it, the one rule the home screen and the settings screens
 * on Android, iOS and the desktop ask; and what a refusal says in English.
 */
class SubscriptionRefreshReportTest {
    private fun refused(refusal: DeviceRefusal = DeviceRefusal.LimitReached, message: String = "") =
        SubscriptionRefreshFailure("https://partner.example/sub", SubscriptionRefreshError.DeviceRefused(message, refusal))

    private val selectionRefused = SubscriptionRefreshReport(failures = listOf(refused()), refusedSelection = true)
    private val served = SubscriptionRefreshReport(updatedCount = 1)

    // The list the selected server came from refused this device: nothing is
    // selected, and a start would only take down a session the host carries.
    @Test fun aRefusalOfTheSelectedServersListStartsNothingAgain() {
        assertFalse(selectionRefused.restartsRunningTunnel(selectionMoved = true))
        assertFalse(selectionRefused.restartsRunningTunnel(selectionMoved = false))
        assertFalse(selectionRefused.restartsRunningTunnel())
    }

    @Test fun aServedRefreshStartsTheTunnelAgainAsBefore() {
        // The home screen: when the selected location's own config moved, and only then.
        assertTrue(served.restartsRunningTunnel(selectionMoved = true))
        assertFalse(served.restartsRunningTunnel(selectionMoved = false))
        // The settings screens, which do not ask what moved: after every refresh.
        assertTrue(served.restartsRunningTunnel())
        // A refusal of another list is no reason to hold back.
        val anotherRefused = served.copy(failures = listOf(refused()))
        assertTrue(anotherRefused.restartsRunningTunnel(selectionMoved = true))
        assertTrue(anotherRefused.restartsRunningTunnel())
    }

    @Test fun aRefusalSaysTheProvidersWordsOrOursForItsKind() {
        assertEquals(DEVICE_LIMIT_REACHED_MESSAGE, refused(DeviceRefusal.LimitReached).message())
        assertEquals(DEVICE_NOT_SUPPORTED_MESSAGE, refused(DeviceRefusal.NotSupported).message())
        assertEquals("No room", refused(DeviceRefusal.NotSupported, "No room").message())
        // A refusal named without its kind is the list's limit.
        assertEquals(DeviceRefusal.LimitReached, SubscriptionRefreshError.DeviceRefused("").refusal)
    }
}
