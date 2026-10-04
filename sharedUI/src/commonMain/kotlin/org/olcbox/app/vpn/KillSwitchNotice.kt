package org.olcbox.app.vpn

/**
 * What the Linux desktop says while its kill switch holds the machine's traffic
 * and no tunnel is up (`LinuxKillSwitch` in the desktop code has the rest).
 *
 * It lives here, away from the code that raises it, because the screen that
 * shows it is shared: a failure the app words itself is a fixed English
 * sentence, which the screen looks up to show in the user's language
 * (`Notices`), and that lookup is common code.
 *
 * It names the two ways out the screen has. There is no Disconnect while an
 * error is shown: the button reads Connect. Connecting again replaces the block
 * with a tunnel without opening it, and the switch itself takes it away.
 */
internal const val KILL_SWITCH_HOLDS_TRAFFIC =
    "The tunnel is down and the kill switch is blocking traffic. Connect again, or turn the kill switch off " +
        "in the connection settings to unblock it. Either one asks for the administrator password."

/**
 * Whether this status is that sentence. The shared screen asks, for the two
 * places where it would otherwise work against the hold: the notice is not one
 * to wave away while it is the only thing saying why there is no network, and
 * the "Lowest latency" selection does not stop the session to measure.
 */
internal fun VpnStatus.isKillSwitchHold(): Boolean =
    this is VpnStatus.Error && message == KILL_SWITCH_HOLDS_TRAFFIC

/**
 * The failure the screen starts a new attempt from: none, except that sentence
 * while this status is still it. Any other failure is about an attempt that is
 * over, and goes when the next one begins. This one is about the machine, and
 * an attempt that ends before it reaches the platform leaves the status as it
 * was, so nothing would say it again.
 */
internal fun VpnStatus.failureKeptAcrossAttempts(): String? =
    KILL_SWITCH_HOLDS_TRAFFIC.takeIf { isKillSwitchHold() }
