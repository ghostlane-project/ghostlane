package org.olcbox.app.ui.features.home

import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.vpn.KILL_SWITCH_HOLDS_TRAFFIC
import org.olcbox.app.vpn.OlcrtcFailure
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.failureKeptAcrossAttempts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeScreenStateTest {

    private val idle = HomeScreenState(
        isVpnConnected = false,
        isVpnLoading = false,
        selectedLocation = null,
        configData = LocationConfig(),
        shouldShowConfigInvalidReminder = false,
        canStartVpn = true,
        startBlockedReason = null
    )

    /** The screen after one connect has failed and the platform said why. */
    private val failed = idle.applying(VpnStatus.Error("carrier auth failed"))

    // ── what an error does ────────────────────────────────────────────────

    @Test
    fun anErrorIsTheNoticeOnScreen() {
        assertEquals("carrier auth failed", failed.notice())
        assertFalse(failed.isVpnLoading)
        assertFalse(failed.isVpnConnected)
    }

    @Test
    fun anEngineProtocolErrorIsTranslatedForTheUser() {
        val state = idle.applying(VpnStatus.Error("handshake: peer speaks an incompatible olcrtc protocol"))
        assertEquals(OlcrtcFailure.PROTOCOL, state.notice())
    }

    /**
     * A server that dropped our key never answers, which the engine reports as a
     * silent peer. On its own that names no cause; when the status probe has
     * already said the key is gone, the notice can name one.
     */
    @Test
    fun aRevokedKeyExplainsASilentServer() {
        val state = idle.applying(VpnStatus.Error("handshake: peer did not answer the handshake"))
        assertEquals(OlcrtcFailure.SILENT, state.notice())
        assertEquals(OlcrtcFailure.KEY_GONE, state.notice(keyGone = true))
        assertEquals("carrier auth failed", failed.notice(keyGone = true))
    }

    // ── what clears it ────────────────────────────────────────────────────
    //
    // The banner used to outlive everything but a relaunch: a stop went
    // Stopping → Disconnected and neither touched it, so a user who read the
    // message, pressed stop, and moved on kept a red box about a connection
    // that no longer existed.

    @Test
    fun stoppingClearsTheLastFailure() {
        assertNull(failed.applying(VpnStatus.Stopping).failure)
    }

    @Test
    fun disconnectingClearsTheLastFailure() {
        assertNull(failed.applying(VpnStatus.Disconnected).failure)
    }

    @Test
    fun connectingAgainClearsTheLastFailure() {
        assertNull(failed.applying(VpnStatus.Connecting).failure)
    }

    @Test
    fun connectingSuccessfullyClearsTheLastFailure() {
        assertNull(failed.applying(VpnStatus.Connected).failure)
    }

    // ── what keeps it ─────────────────────────────────────────────────────

    @Test
    fun aReconnectAttemptKeepsTheFailureItIsRetryingFrom() {
        assertEquals("carrier auth failed", failed.applying(VpnStatus.Reconnecting).failure)
    }

    // ── what cannot be waved away ─────────────────────────────────────────
    //
    // A failure is about an attempt that is over, and can be dismissed. The
    // Linux desktop's kill switch holding traffic is about now: the machine has
    // no network, the status behind the sentence does not change, so it would
    // not be said again, and without it the screen reads "not connected".

    @Test
    fun theKillSwitchSentenceStaysForAsLongAsTheBlockDoes() {
        val held = idle.applying(VpnStatus.Error(KILL_SWITCH_HOLDS_TRAFFIC))
        assertEquals(KILL_SWITCH_HOLDS_TRAFFIC, held.notice())
        assertFalse(held.noticeDismissible)
        // Any other failure is dismissed as before, and with none there is nothing to dismiss.
        assertTrue(failed.noticeDismissible)
        assertFalse(idle.noticeDismissible)
        // A reconnect that begins clears it, as it clears any failure.
        assertNull(held.applying(VpnStatus.Connecting).failure)
    }

    // An attempt can end before it reaches the platform (no valid location, a
    // transport the server does not speak). The status is then what it was,
    // nothing reports it again, and a sentence cleared when the attempt began
    // would be gone with the block still standing.
    @Test
    fun onlyTheKillSwitchSentenceIsKeptWhenAnAttemptBegins() {
        assertEquals(
            KILL_SWITCH_HOLDS_TRAFFIC,
            VpnStatus.Error(KILL_SWITCH_HOLDS_TRAFFIC).failureKeptAcrossAttempts()
        )
        assertNull(VpnStatus.Error("carrier auth failed").failureKeptAcrossAttempts())
        assertNull(VpnStatus.Disconnected.failureKeptAcrossAttempts())
        assertNull(VpnStatus.Connecting.failureKeptAcrossAttempts())
    }
}
