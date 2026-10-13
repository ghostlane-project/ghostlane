package org.olcbox.app.vpn

/**
 * What the Linux desktop says while its kill switch holds the machine's traffic
 * and no tunnel is up (`LinuxKillSwitch` in the desktop code has the rest; the
 * Mac's sentence is below).
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
 * The same said on a Mac (`MacKillSwitch`), where neither way out asks for a
 * password: the block is the tunnel helper's to load and to take away, and the
 * helper was approved once, when it was installed.
 */
internal const val KILL_SWITCH_HOLDS_TRAFFIC_MAC =
    "The tunnel is down and the kill switch is blocking traffic. Connect again, or turn the kill switch off " +
        "in the connection settings to unblock it."

/**
 * Whether this status is one of those sentences. The shared screen asks, for
 * the two places where it would otherwise work against the hold: the notice is
 * not one to wave away while it is the only thing saying why there is no
 * network, and the "Lowest latency" selection does not stop the session to
 * measure.
 */
internal fun VpnStatus.isKillSwitchHold(): Boolean =
    this is VpnStatus.Error && message.isKillSwitchHoldSentence()

internal fun String?.isKillSwitchHoldSentence(): Boolean =
    this == KILL_SWITCH_HOLDS_TRAFFIC || this == KILL_SWITCH_HOLDS_TRAFFIC_MAC

/**
 * The failure the screen starts a new attempt from: none, except the sentence
 * this status still is. Any other failure is about an attempt that is over,
 * and goes when the next one begins. This one is about the machine, and an
 * attempt that ends before it reaches the platform leaves the status as it
 * was, so nothing would say it again.
 */
internal fun VpnStatus.failureKeptAcrossAttempts(): String? =
    (this as? VpnStatus.Error)?.message?.takeIf { it.isKillSwitchHoldSentence() }

/**
 * What the user is shown for [status] while a kill switch's block stands with
 * no tunnel in front of it ([holdsTraffic]), in the words of the platform whose
 * block it is ([sentence]).
 *
 * "Disconnected", or the error that ended the session, would both be true and
 * both leave out the one thing that matters: this machine's traffic is held,
 * and here is how to change that. So both become one sentence, the same
 * wherever the block was met: the tunnel's death, a reconnect that failed, a
 * cleanup that was refused, a block left by a previous run. What went wrong is
 * in the log. A status that says something is being done (connecting,
 * stopping) is left as it is.
 */
internal fun shownUnderKillSwitch(status: VpnStatus, holdsTraffic: Boolean, sentence: String): VpnStatus = when {
    !holdsTraffic -> status
    status is VpnStatus.Disconnected || status is VpnStatus.Error -> VpnStatus.Error(sentence)
    else -> status
}

/**
 * What an attempt failed of, when the screen shows a kill switch's sentence in
 * its place ([shownUnderKillSwitch]); null when the screen shows the failure
 * itself, or there is none.
 *
 * The sentence is the same before a connect and after one that failed, so on
 * the screen alone an attempt that failed looked like one that had not been
 * made: "what went wrong is in the log" left a person looking at the same
 * words and wondering whether pressing Connect had done anything. The reason
 * is said beside the sentence, once, where the app's other one-off messages
 * appear, and the sentence stays: the block is still what matters most.
 */
internal fun failureHiddenByHold(requested: VpnStatus, shown: VpnStatus): String? =
    (requested as? VpnStatus.Error)?.message
        ?.takeIf { shown.isKillSwitchHold() && !it.isKillSwitchHoldSentence() }
